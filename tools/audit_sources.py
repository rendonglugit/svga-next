#!/usr/bin/env python3
"""Check the distribution's source boundaries and retained upstream notices."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
assert "Apache License" in (root / "LICENSE").read_text()
assert "662d0dc11824e35a3f3fcd247f491efce2bf5ec5" in (root / "NOTICE").read_text()
source_files = []
for module in ("svga-core", "svga-player", "svga-runtime", "benchmark-upstream", "sample"):
    for path in sorted((root / module / "src").rglob("*")):
        if not path.is_file():
            continue
        assert path.suffix not in {".smali", ".dex", ".apk", ".aar"}, path
        if path.suffix in {".kt", ".java", ".xml"}:
            text = path.read_text()
            assert "com.opensource.svgaplayer" not in text, path
            if path.suffix in {".kt", ".java"}:
                assert "package dev.svganext." in text, path
        source_files.append({"path": str(path.relative_to(root)), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
for module in ("svga-core", "svga-player", "svga-runtime", "benchmark-upstream"):
    legal = root / module / "src/main/resources/META-INF/svga-next" / module
    assert (legal / "LICENSE").read_bytes() == (root / "LICENSE").read_bytes()
    assert (legal / "NOTICE").read_bytes() == (root / "NOTICE").read_bytes()
baseline = json.loads((root / "docs/upstream-snapshot.json").read_text())
assert baseline["commit"] == "662d0dc11824e35a3f3fcd247f491efce2bf5ec5"
for entry in baseline["files"]:
    if not entry["upstream_path"].endswith(".kt"):
        continue
    path = root / "svga-player/src/main" / entry["upstream_path"].removeprefix("library/src/main/").replace("com/opensource/svgaplayer", "dev/svganext/player")
    text = path.read_text()
    for comment in entry.get("attribution_comments", []):
        assert comment.replace("com.opensource.svgaplayer", "dev.svganext.player") in text, path
print(json.dumps({"status": "passed", "source_files_checked": len(source_files), "upstream_commit": baseline["commit"], "limits": "Text/path boundary check; not a legal opinion or proof of every code origin"}, indent=2))
