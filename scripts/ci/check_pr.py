import json
import os
from pathlib import Path
import re
import sys


SECTIONS = ("対応要件", "変更範囲", "成果", "証跡", "安全・権利・プライバシー", "未確認")
ID_PATTERNS = (r"\b(?:FR|NFR)-[A-Z]+-\d+\b", r"\bDD-[A-Z]+-\d+\b", r"\bTEST-[A-Z]+-\d+\b")


def validate(body):
    body = re.sub(r"<!--.*?-->", "", body or "", flags=re.DOTALL)
    parts = re.split(r"^##[ \t]+([^\n]+)\n?", body, flags=re.MULTILINE)
    sections = {parts[i].strip(): parts[i + 1].strip() for i in range(1, len(parts), 2)}
    errors = [f"「{name}」を記入してください。" for name in SECTIONS if not sections.get(name)]
    requirements = sections.get("対応要件", "")
    for label, pattern in zip(("FR/NFR", "DD", "TEST"), ID_PATTERNS):
        if not re.search(pattern, requirements):
            errors.append(f"「対応要件」に{label} IDを記入してください。")
    return errors


def main():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    errors = validate(event["pull_request"].get("body"))
    for error in errors:
        print(error, file=sys.stderr)
    return bool(errors)


if __name__ == "__main__":
    sys.exit(main())
