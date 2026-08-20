#!/usr/bin/env python3
"""MCP239: force the verified Qwen3.5-2B Q8/4096 model onto CPU.

MCP238 proved that the rebuilt LiteRT-LM container is accepted far enough to reach
compiled-model creation, but physical Android initialization still fails when the
model is sent to the GPU backend. Qwen3.5's gated-delta hybrid graph is currently
known to require CPU on the released LiteRT-LM 0.15 Android stack.

This patch deliberately keeps the exact MCP238 model name, version, download path,
file size, SHA and multipart assets so an in-place APK upgrade can reuse the already
downloaded 4.78 GB model. It only changes backend eligibility and adds a host-level
CPU guard so persisted MCP238 GPU preferences cannot leak into MCP239.
"""

from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ROOT.parents[1]
MODEL_NAME = "Qwen3.5-2B LiteRT-LM Q8 4096 Plaza"


def fail(message: str) -> None:
    print(f"MCP239 patch failure: {message}", file=sys.stderr)
    raise SystemExit(1)


def patch_allowlist(path: Path) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    models = data.get("models")
    if not isinstance(models, list):
        fail(f"allowlist has no models list: {path}")
    matches = [m for m in models if m.get("name") == MODEL_NAME]
    if len(matches) != 1:
        fail(f"expected exactly one {MODEL_NAME!r} in {path}, found {len(matches)}")
    model = matches[0]
    default = model.get("defaultConfig")
    if not isinstance(default, dict):
        fail(f"model has no defaultConfig in {path}")
    default["accelerators"] = "cpu"
    desc = str(model.get("description", ""))
    note = " MCP239 Android validation: CPU-only because the released Qwen3.5 gated-delta graph is not accepted by the current GPU delegate."
    if note.strip() not in desc:
        model["description"] = desc.rstrip() + note
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"MCP239 allowlist CPU-only: {path.relative_to(REPO_ROOT)}")


for allowlist in (
    REPO_ROOT / "model_allowlists/1_0_14.json",
    ROOT / "app/src/main/assets/model_allowlists/1_0_14.json",
):
    patch_allowlist(allowlist)

helper = ROOT / "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt"
text = helper.read_text(encoding="utf-8")
sentinel = "// MCP239_QWEN35_CPU_ONLY"
if sentinel not in text:
    old = """    val accelerator =\n      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)\n"""
    new = f"""    // MCP239_QWEN35_CPU_ONLY: the Qwen3.5 gated-delta graph currently fails Android GPU\n    // compiled-model creation. Keep a host-level guard so a persisted MCP238 GPU preference cannot\n    // override the CPU-only allowlist after an in-place app upgrade.\n    val configuredAccelerator =\n      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)\n    val accelerator =\n      if (model.name == \"{MODEL_NAME}\") Accelerator.CPU.label else configuredAccelerator\n"""
    count = text.count(old)
    if count != 1:
        fail(f"expected one accelerator anchor in {helper}, found {count}")
    text = text.replace(old, new, 1)
    helper.write_text(text, encoding="utf-8")
    print(f"MCP239 host CPU guard patched: {helper.relative_to(REPO_ROOT)}")
else:
    print("MCP239 host CPU guard already present")

# Fail-fast postconditions.
for allowlist in (
    REPO_ROOT / "model_allowlists/1_0_14.json",
    ROOT / "app/src/main/assets/model_allowlists/1_0_14.json",
):
    data = json.loads(allowlist.read_text(encoding="utf-8"))
    model = next(m for m in data["models"] if m.get("name") == MODEL_NAME)
    if model.get("defaultConfig", {}).get("accelerators") != "cpu":
        fail(f"CPU-only postcondition failed: {allowlist}")

if sentinel not in helper.read_text(encoding="utf-8"):
    fail("host CPU guard postcondition failed")

print("MCP239 Qwen3.5 CPU-only patch complete")
