"""Compare raw/cleaned articles on Ollama and OpenRouter, without forced JSON."""
import argparse
import csv
import getpass
import json
import os
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from helpers import clean_rbc, validate

ROOT = Path(__file__).resolve().parent


def build_request(provider, model, messages, temperature, limit, context):
    body = {"model": model, "stream": False, "messages": messages}
    if provider == "local":
        body.update(think=False, options={"temperature": temperature, "num_ctx": context, "num_predict": limit})
    else:
        body.update(temperature=temperature, max_tokens=limit)
    # Intentionally no format, response_format, schema, plugins or tools.
    return body


def send(provider, payload, key, local_url):
    url = local_url.rstrip("/") + "/api/chat" if provider == "local" else "https://openrouter.ai/api/v1/chat/completions"
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if provider == "openrouter":
        headers["Authorization"] = "Bearer " + key
    request = urllib.request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"), headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=600) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        # Do not persist headers or untrusted provider error bodies that could echo secrets.
        raise RuntimeError(f"HTTP {error.code}: {error.reason}") from None


def write_tables(folder, records):
    fields = ["article_id", "provider", "variant", "requested_model", "returned_model", "seconds",
              "structure_ok", "word_count", "length_ok", "truncated", "validation_error", "error",
              "facts_manual", "coverage_manual", "ukrainian_manual", "notes_manual"]
    with (folder / "evaluation.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(records)
    lines = ["# Порівняння моделей", "", "Без примусового JSON: лише початковий промпт проєкту.", "",
             "| Стаття | Сервіс | Текст | Час, с | Структура | Слів | 40–70 слів |",
             "|---|---|---|---:|---|---:|---|"]
    for row in records:
        lines.append(f"| {row['article_id']} | {row['provider']} | {row['variant']} | {row.get('seconds', '')} | "
                     f"{row.get('structure_ok', '')} | {row.get('word_count', '')} | {row.get('length_ok', '')} |")
    lines += ["", "Факти, повноту та українську оцініть вручну в evaluation.csv; порожні поля не означають успіх.",
              "Час включає завантаження/кеш локальної моделі, мережу й очікування хмарного сервісу.",
              "Один запит на комбінацію не є надійним бенчмарком. Обмеження токенів не еквівалентне обмеженню слів.",
              "Помилки й повні відповіді збережені окремо. Текст не виправляється автоматично."]
    (folder / "comparison.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--providers", choices=["both", "local", "openrouter"], default="both")
    parser.add_argument("--local-model", default="lfm3:1.7b")
    parser.add_argument("--cloud-model", default="openai/gpt-6-luna")
    parser.add_argument("--local-url", default="http://localhost:11434")
    parser.add_argument("--temperature", type=float, default=0)
    parser.add_argument("--max-tokens", type=int, default=1024)
    parser.add_argument("--context", type=int, default=8192)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    providers = ["local", "openrouter"] if args.providers == "both" else [args.providers]
    key = ""
    if "openrouter" in providers and not args.dry_run:
        key = os.environ.get("OPENROUTER_API_KEY", "").strip()
        if not key:
            key = getpass.getpass("OpenRouter API key (введення приховане): ").strip()
        if not key:
            raise SystemExit("Ключ не введено; жодних запитів не виконано.")
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    folder = ROOT / "results" / (stamp + ("_dry_run" if args.dry_run else ""))
    folder.mkdir(parents=True)
    system = (ROOT / "system_prompt.txt").read_text(encoding="utf-8-sig")
    (folder / "settings.json").write_text(json.dumps(vars(args), indent=2), encoding="utf-8")
    records = []
    for article_id, path in enumerate(sorted((ROOT / "inputs").glob("*.txt")), 1):
        raw = path.read_text(encoding="utf-8-sig")
        title, cleaned = clean_rbc(raw)
        (folder / f"article_{article_id}_cleaned.txt").write_text(cleaned, encoding="utf-8")
        for variant, text in [("raw", raw), ("cleaned", cleaned)]:
            user = (f"article_id: {article_id}\nМова: українська\nОрієнтовна довжина: 40–70 слів\n"
                    "Стиль: нейтральний: спокійний виклад фактів без оцінок\n"
                    f"Додаткові побажання користувача:\nНемає.\n\nЗаголовок статті:\n{title}\n\n"
                    f"<article>\n{text}\n</article>")
            messages = [{"role": "system", "content": system}, {"role": "user", "content": user}]
            for provider in providers:
                model = args.local_model if provider == "local" else args.cloud_model
                prefix = folder / f"{article_id}_{variant}_{provider}"
                payload = build_request(provider, model, messages, args.temperature, args.max_tokens, args.context)
                Path(str(prefix) + "_request.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
                print(f"\n[{article_id}/3] {variant} / {provider} / {model}", flush=True)
                if args.dry_run:
                    continue
                row = {"article_id": article_id, "provider": provider, "variant": variant, "requested_model": model}
                started = time.perf_counter()
                try:
                    response = send(provider, payload, key, args.local_url)
                    # No request headers/API key are written to disk.
                    Path(str(prefix) + "_response.json").write_text(json.dumps(response, ensure_ascii=False, indent=2), encoding="utf-8")
                    if "error" in response:
                        raise RuntimeError("API повернув error; див. файл response.json")
                    row["returned_model"] = response.get("model", "")
                    if provider == "local":
                        content = response["message"]["content"]
                        row["truncated"] = response.get("done_reason") == "length"
                    else:
                        choice = response["choices"][0]
                        content = choice["message"]["content"]
                        row["truncated"] = choice.get("finish_reason") == "length"
                    if not isinstance(content, str):
                        raise ValueError("Немає текстового content")
                    Path(str(prefix) + "_answer.txt").write_text(content, encoding="utf-8")
                    print(content, flush=True)
                    row["structure_ok"] = False
                    # Count words where possible even when the root container is wrong.
                    try:
                        parsed = json.loads(content)
                        obj = parsed[0] if isinstance(parsed, list) and parsed else parsed
                        summary = obj.get("summary_text") if isinstance(obj, dict) else None
                        if isinstance(summary, str):
                            row["word_count"] = len(summary.split())
                            row["length_ok"] = 40 <= row["word_count"] <= 70
                    except ValueError:
                        pass
                    try:
                        validate(content, article_id)
                        row["structure_ok"] = True
                    except (ValueError, KeyError, TypeError) as error:
                        row["validation_error"] = str(error)
                except Exception as error:
                    row["error"] = str(error).replace(key, "[REDACTED]") if key else str(error)
                    print("ПОМИЛКА:", row["error"], flush=True)
                row["seconds"] = round(time.perf_counter() - started, 2)
                records.append(row)
                Path(str(prefix) + "_checks.json").write_text(json.dumps(row, ensure_ascii=False, indent=2), encoding="utf-8")
                write_tables(folder, records)
                print(f"Час: {row['seconds']} с; структура: {row.get('structure_ok')}; слів: {row.get('word_count')}", flush=True)
                if row.get("error", "").startswith(("HTTP 401", "HTTP 402", "HTTP 403", "HTTP 404")):
                    raise SystemExit(f"Зупинено після помилки доступу/моделі. Результати: {folder}")
    print(f"\nГотово: {folder}", flush=True)


if __name__ == "__main__":
    main()
