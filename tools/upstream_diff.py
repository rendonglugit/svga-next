#!/usr/bin/env python3
"""Compare the player with the pinned upstream checkout, ignoring namespace relocation."""
import argparse
import difflib
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("upstream", type=Path, help="SVGAPlayer-Android checkout at the pinned commit")
args = parser.parse_args()
commit = subprocess.check_output(["git", "-C", str(args.upstream), "rev-parse", "HEAD"], text=True).strip()
assert commit == "662d0dc11824e35a3f3fcd247f491efce2bf5ec5", "Select the documented upstream commit"
root = Path(__file__).resolve().parents[1]
patch = []
changed = []
for upstream in sorted((args.upstream / "library/src/main").rglob("*")):
    if not upstream.is_file():
        continue
    relative = str(upstream.relative_to(args.upstream / "library/src/main"))
    relocated = relative.replace("com/opensource/svgaplayer", "dev/readablesvga/player")
    current = root / "svga-player/src/main" / relocated
    before = upstream.read_text().replace("com.opensource.svgaplayer", "dev.readablesvga.player")
    after = current.read_text()
    if before != after:
        changed.append(relocated)
        patch.extend(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                         fromfile="upstream-relocated/" + relocated,
                                         tofile="svga-player/src/main/" + relocated))
(root / "docs/upstream-changes.patch").write_text("".join(patch))
print("Changed upstream files beyond namespace relocation:")
print("\n".join(changed))
