#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
DEV = ROOT / ".github" / "workflows" / "build-alpha.yml"
PUB = ROOT / ".github" / "workflows" / "publish-alpha.yml"


def require(condition: bool, message: str) -> None:
    if not condition:
        print(f"ERROR: {message}", file=sys.stderr)
        raise SystemExit(1)


def main() -> None:
    dev = DEV.read_text(encoding="utf-8")
    pub = PUB.read_text(encoding="utf-8")

    require("push:" in dev, "development validation must run on push")
    require("contents: read" in dev, "development validation must have read-only contents permission")
    require("actions/upload-artifact@v4" in dev, "development validation must publish an Actions artifact")
    require("${{ github.sha }}" in dev, "development artifact/provenance must identify exact head SHA")
    require("build-provenance.json" in dev, "development artifact must include build provenance")
    require("gh release" not in dev, "development validation must not create/update GitHub releases")
    require("--clobber" not in dev, "development validation must never clobber release assets")

    require("workflow_dispatch:" in pub, "publication must require explicit workflow dispatch")
    require("push:" not in pub, "publication workflow must not run on ordinary pushes")
    require("contents: write" in pub, "publication workflow needs explicit contents write permission")
    require("accepted_sha:" in pub, "publication workflow must require exact accepted SHA")
    require("confirm_release_owner:" in pub, "publication workflow must require CHAT-6 confirmation")
    require('"CHAT-6"' in pub or "'CHAT-6'" in pub, "publication workflow must enforce CHAT-6 ownership")
    require("ref: ${{ inputs.accepted_sha }}" in pub, "publication checkout must pin accepted SHA")
    require("git rev-parse HEAD" in pub and "inputs.accepted_sha" in pub, "publication must verify exact checkout SHA")
    require("gh release view" in pub, "publication must test whether version tag already exists")
    require("already exists" in pub, "publication must fail instead of reusing an existing version/tag")
    require("gh release create" in pub, "publication workflow must create a release explicitly")
    require("--clobber" not in pub, "publication workflow must never silently overwrite release assets")

    print("Release workflow separation: PASS")


if __name__ == "__main__":
    main()
