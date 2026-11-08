"""Offline checks; no model requests or charges."""
import json
from compare import ROOT, build_request
from helpers import clean_rbc, validate

messages = [{"role": "system", "content": "test"}, {"role": "user", "content": "article"}]
local = build_request("local", "lfm3:1.7b", messages, 0, 1024, 8192)
cloud = build_request("openrouter", "openai/gpt-6-luna", messages, 0, 1024, 8192)
assert local["messages"] == cloud["messages"]
assert local["options"]["num_predict"] == cloud["max_tokens"]
for body in (local, cloud):
    assert not any(key in body for key in ("format", "response_format", "tools", "plugins"))
for path in sorted((ROOT / "inputs").glob("*.txt")):
    raw = path.read_text(encoding="utf-8-sig")
    title, cleaned = clean_rbc(raw)
    assert title and 500 < len(cleaned) < len(raw)
    assert "/author/" not in cleaned
    assert "© LLC" not in cleaned
    if path.stem.startswith("03"):
        assert "Оновлено о 20:00" in cleaned and "12 осіб" in cleaned
valid = [{"article_id": 1, "status": "OK", "generated_title": "Заголовок", "summary_text": "Текст", "hidden_reason": None}]
validate(json.dumps(valid), 1)
for invalid in (valid[0], [{**valid[0], "article_id": 2}], [{**valid[0], "hidden_reason": "wrong"}]):
    try:
        validate(json.dumps(invalid), 1)
    except ValueError:
        pass
    else:
        raise AssertionError("Validator accepted invalid output")
print("OK: однакові messages/ліміти; без примусового JSON; очищення зберігає оновлення; перевірка структури працює.")
