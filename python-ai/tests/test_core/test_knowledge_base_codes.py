from pathlib import Path


def test_repository_does_not_contain_removed_demo_knowledge_base_code():
    removed_code = "flight_policy" + "_kb"
    removed_name = "Flight Policy" + " KB"
    root = Path(__file__).resolve().parents[3]
    matches = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix in {".py", ".md", ".sql", ".ts", ".tsx", ".json"}:
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            if removed_code in text or removed_name in text:
                matches.append(str(path.relative_to(root)))

    assert matches == []
