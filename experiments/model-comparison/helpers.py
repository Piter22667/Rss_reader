"""Standalone Ollama trial. Python standard library only; no project/DB changes."""
import argparse
import html
import json
import re
import time
import urllib.request
from pathlib import Path
from datetime import datetime

ROOT = Path(__file__).resolve().parent
EXTRA = """
Додаткові правила точності:
Відокремлюй автора публікації від учасників події. Не приписуй автору дії або події.
Час публікації не є часом події. Якщо час події не вказаний, не додавай його.
Зберігай ступінь визначеності: плани, обговорення, заяви та припущення не є здійсненими діями.
Зберігай атрибуцію оцінок та непідтверджених повідомлень: «за словами», «за даними».
Якщо є явно позначене оновлення щодо того самого показника, використовуй нові дані.
Не підсумовуй різні групи постраждалих самостійно, якщо їх перетин не визначений.
Не називай інцидент терактом, якщо це не встановлено в основному тексті.
Ігноруй меню, рекламу, підписи фотографій, рекомендації та сторонні заголовки.
Вибери головну подію та найважливіші результати. Не повторюй заголовок у першому реченні.
Не додавай вступів «у статті йдеться», не повторюй однакові факти.
Пиши природною українською. Перед відповіддю перевір імена, числа та зв'язки між ними.
Поверни лише JSON-масив з одним об'єктом і всіма п'ятьма обов'язковими полями.
"""


def clean_rbc(raw):
    # Deliberately site-specific. Fail instead of silently truncating unknown layouts.
    heading = re.search(r"(?m)^# (.+)$", raw)
    marker = "Додати як бажане джерело в Google"
    if not heading:
        raise ValueError("Немає заголовка #; потрібна перевірка формату")
    start = raw.find(marker, heading.end())
    end = raw.find("[Не пропустіть", start)
    if start < 0 or end < 0:
        raise ValueError("Не знайдено меж статті; автоматичне очищення зупинено")
    text = raw[start + len(marker):end]
    # Remove only trailing related-story paragraphs, keeping updates and earlier content.
    text = re.split(r"(?m)^(?:Раніше РБК-Україна повідомлял[оа]|Також ми писали)", text)[0]
    text = re.sub(r"!\[[^\]]*\]\([^\n]*?\)", "", text)
    text = re.sub(r"\[([^\]\n]+)\]\([^\s)]+(?:\s+\"[^\"]*\")?\)", r"\1", text)
    text = re.sub(r"https?://[^\s]+", "", text)
    text = re.sub(r"\\([_*])", r"\1", text)
    text = text.replace("**", "")
    text = re.sub(r"\n{3,}", "\n\n", html.unescape(text)).strip()
    return heading.group(1).strip(), text


def schema(article_id):
    return {
        "type": "array", "minItems": 1, "maxItems": 1,
        "items": {
            "type": "object", "additionalProperties": False,
            "required": ["article_id", "status", "generated_title", "summary_text", "hidden_reason"],
            "properties": {
                "article_id": {"type": "integer", "enum": [article_id]},
                "status": {"type": "string", "enum": ["OK", "HIDDEN"]},
                "generated_title": {"type": ["string", "null"]},
                "summary_text": {"type": ["string", "null"]},
                "hidden_reason": {"type": ["string", "null"]},
            },
        },
    }


def validate(content, article_id):
    data = json.loads(content)
    if not isinstance(data, list) or len(data) != 1 or not isinstance(data[0], dict):
        raise ValueError("Очікувався масив з одним об'єктом")
    obj = data[0]
    if set(obj) != set(schema(article_id)["items"]["required"]):
        raise ValueError("Неправильний набір полів")
    if type(obj["article_id"]) is not int or obj["article_id"] != article_id:
        raise ValueError("Неправильний article_id")
    def nonempty(value):
        return isinstance(value, str) and bool(value.strip())
    if obj["status"] == "OK":
        if not (nonempty(obj["generated_title"]) and nonempty(obj["summary_text"]) and obj["hidden_reason"] is None):
            raise ValueError("Неправильні поля для OK")
    elif obj["status"] == "HIDDEN":
        if not (obj["generated_title"] is None and obj["summary_text"] is None and nonempty(obj["hidden_reason"])):
            raise ValueError("Неправильні поля для HIDDEN")
    else:
        raise ValueError("Невідомий status")
    return obj


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="lfm3:1.7b")
    parser.add_argument("--variant", choices=["baseline", "prompt", "schema", "cleaned"], default="cleaned")
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    out = ROOT / "results" / (datetime.now().strftime("%Y%m%d_%H%M%S_%f") + "_" + args.variant)
    out.mkdir(parents=True)
    original = (ROOT / "original_prompt.txt").read_text(encoding="utf-8-sig")
    for article_id, path in enumerate(sorted((ROOT / "inputs").glob("*.txt")), 1):
        raw = path.read_text(encoding="utf-8-sig")
        title, cleaned = clean_rbc(raw)
        (out / (path.stem + "_cleaned.txt")).write_text(title + "\n\n" + cleaned, encoding="utf-8")
        text = cleaned if args.variant == "cleaned" else raw
        system = original if args.variant == "baseline" else original + "\n" + EXTRA
        user = (f"article_id: {article_id}\nМова: українська\nОрієнтовна довжина: 40–70 слів\n"
                "Стиль: нейтральний: спокійний виклад фактів без оцінок\n"
                f"Додаткові побажання користувача:\nНемає.\n\nЗаголовок статті:\n{title}\n\n"
                f"<article>\n{text}\n</article>")
        payload = {"model": args.model, "stream": False, "think": False,
                   "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                   "options": {"temperature": 0, "num_ctx": 8192, "num_predict": 1024}}
        if args.variant in ("schema", "cleaned"):
            payload["format"] = schema(article_id)
        prefix = out / path.stem
        Path(str(prefix) + "_request.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n{article_id}. {title}\nСимволів: {len(raw)} → {len(text)}", flush=True)
        if args.prepare_only:
            continue
        started = time.perf_counter()
        record = {"article_id": article_id, "model": args.model, "variant": args.variant}
        try:
            request = urllib.request.Request("http://localhost:11434/api/chat",
                data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=600) as response:
                result = json.load(response)
            record["response"] = result
            content = result["message"]["content"]
            print(content, flush=True)
            record["structure_ok"] = False
            try:
                obj = validate(content, article_id)
                record["structure_ok"] = True
                record["summary_words"] = len((obj["summary_text"] or "").split())
                record["length_ok"] = obj["status"] == "HIDDEN" or 40 <= record["summary_words"] <= 70
            except (ValueError, TypeError, KeyError) as error:
                record["validation_error"] = str(error)
            record["truncated"] = result.get("done_reason") == "length"
            record["timings_seconds"] = {key: round(result[key] / 1e9, 3) for key in
                ("load_duration", "prompt_eval_duration", "eval_duration", "total_duration") if key in result}
        except Exception as error:
            record["error"] = str(error)
            print("ПОМИЛКА:", error, flush=True)
        record["wall_seconds"] = round(time.perf_counter() - started, 2)
        Path(str(prefix) + "_result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Час: {record['wall_seconds']} с; структура: {record.get('structure_ok')}; "
              f"40–70 слів: {record.get('length_ok')}; обрізання: {record.get('truncated')}", flush=True)
    print(f"\nРезультати: {out}\nДостовірність і українську потрібно перевірити вручну.")


if __name__ == "__main__":
    main()
