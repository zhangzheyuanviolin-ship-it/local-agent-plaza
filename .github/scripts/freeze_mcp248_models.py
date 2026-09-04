#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ANDROID_ROOT = REPO_ROOT / "Android/src"
FREEZE_OUT = Path(os.environ.get("MCP248_FREEZE_JSON", "/tmp/mcp248_model_freeze.json"))


def fail(msg: str) -> None:
    print(f"MCP248 model-pool freeze failure: {msg}", file=sys.stderr)
    raise SystemExit(1)


def hf_json(repo: str) -> dict:
    url = "https://huggingface.co/api/models/" + urllib.parse.quote(repo, safe="/") + "?blobs=true"
    last = None
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Local-Agent-Plaza-MCP248/1.0"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except Exception as exc:
            last = exc
            time.sleep(2 ** attempt)
    fail(f"Hugging Face API failed for {repo}: {last}")


def sibling_size(s: dict) -> int | None:
    if isinstance(s.get("size"), int):
        return int(s["size"])
    lfs = s.get("lfs") or {}
    if isinstance(lfs.get("size"), int):
        return int(lfs["size"])
    return None


SPECS = [
    {
        "key": "ministral3b",
        "name": "Ministral-3-3B-Instruct-2512 LiteRT",
        "repo": "litert-community/Ministral-3-3B-Instruct-2512",
        "file": "Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm",
        "min_ram": 10,
        "description": "Ministral 3B Instruct LiteRT-LM blockwise INT4/OCTAV text model. 4096 KV context; experimental cross-family Agent control model.",
        "config": {"topK": 40, "topP": 0.9, "temperature": 0.7, "maxTokens": 2048, "accelerators": "gpu,cpu", "maxContextLength": 4096},
        "taskTypes": ["llm_chat", "llm_prompt_lab", "llm_agent_chat"],
    },
    {
        "key": "phi4mini",
        "name": "Phi-4-mini-instruct Q8 4096 LiteRT",
        "repo": "litert-community/Phi-4-mini-instruct",
        "file": "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        "min_ram": 10,
        "description": "Microsoft Phi-4 mini instruct LiteRT-LM Q8 model with 4096 KV context. Function-calling-trained upstream; experimental Agent candidate.",
        "config": {"topK": 40, "topP": 0.9, "temperature": 0.7, "maxTokens": 2048, "accelerators": "gpu,cpu", "maxContextLength": 4096},
        "taskTypes": ["llm_chat", "llm_prompt_lab", "llm_agent_chat"],
    },
    {
        "key": "llama32_3b",
        "name": "Llama-3.2-3B-Instruct LiteRT block32",
        "repo": "mlboydaisuke/Llama-3.2-3B-Instruct-LiteRT",
        "file": "model.litertlm",
        "min_ram": 10,
        "description": "Llama 3.2 3B Instruct LiteRT-LM blockwise INT4 + INT8 embedding conversion, 4096 KV context; direct-answering control model.",
        "config": {"topK": 40, "topP": 0.9, "temperature": 0.7, "maxTokens": 2048, "accelerators": "gpu,cpu", "maxContextLength": 4096},
        "taskTypes": ["llm_chat", "llm_prompt_lab", "llm_agent_chat"],
    },
    {
        "key": "falcon_h1_3b",
        "name": "Falcon-H1-3B-Instruct INT8 LiteRT",
        "repo": "litert-community/Falcon-H1-3B-Instruct",
        "file": "Falcon-H1-3B-Instruct_int8.litertlm",
        "min_ram": 10,
        "description": "Falcon-H1 3B Instruct hybrid attention + Mamba2 LiteRT-LM INT8 model. Requires LiteRT-LM 0.15+; experimental architecture-compatibility control.",
        "config": {"topK": 40, "topP": 0.9, "temperature": 0.7, "maxTokens": 2048, "accelerators": "gpu,cpu", "maxContextLength": 4096},
        "taskTypes": ["llm_chat", "llm_prompt_lab", "llm_agent_chat"],
    },
    {
        "key": "jan_nano",
        "name": "Jan-nano 4B reasoning LiteRT",
        "repo": "litert-community/Jan-nano",
        "file": "model.litertlm",
        "min_ram": 10,
        "description": "Jan-nano 4B deep-research/MCP-oriented Qwen3 derivative, LiteRT-LM blockwise INT4. Reasoning model; 4096 KV context.",
        "config": {"topK": 20, "topP": 0.8, "temperature": 0.6, "maxTokens": 4096, "accelerators": "gpu,cpu", "maxContextLength": 4096},
        "taskTypes": ["llm_chat", "llm_prompt_lab", "llm_agent_chat"],
        "capabilities": ["llm_thinking"],
        "capabilityToTaskTypes": {"llm_thinking": ["llm_chat", "llm_prompt_lab", "llm_agent_chat"]},
    },
    {
        "key": "fastcontext4b",
        "name": "FastContext-1.0-4B-SFT LiteRT block32",
        "repo": "litert-community/FastContext-1.0-4B-SFT",
        "file": "model.litertlm",
        "min_ram": 10,
        "description": "FastContext 4B repository-exploration/tool-calling SFT LiteRT-LM block32 quality build, 4096 KV context.",
        "config": {"topK": 20, "topP": 0.8, "temperature": 0.6, "maxTokens": 2048, "accelerators": "gpu,cpu", "maxContextLength": 4096},
        "taskTypes": ["llm_chat", "llm_prompt_lab", "llm_agent_chat"],
        "bestForTaskTypes": ["llm_agent_chat"],
    },
    {
        "key": "laguna_xs2",
        "name": "Laguna XS.2 phone k4 fold3 LiteRT experimental",
        "repo": "poolside-laguna-hackathon/laguna-xs2-phone-k4-fold3-litert",
        "file": "model.litertlm",
        "min_ram": 14,
        "description": "Laguna XS.2 phone k4 fold3 LiteRT experimental MoE/agentic-coding root phone artifact. Treat as a reasoning/tool experiment; English/coding oriented.",
        "config": {"topK": 40, "topP": 0.9, "temperature": 0.6, "maxTokens": 4096, "accelerators": "gpu,cpu", "maxContextLength": 4096},
        "taskTypes": ["llm_chat", "llm_prompt_lab", "llm_agent_chat"],
        "capabilities": ["llm_thinking"],
        "capabilityToTaskTypes": {"llm_thinking": ["llm_chat", "llm_prompt_lab", "llm_agent_chat"]},
    },
    {
        "key": "gemma4_26b_a4b",
        "name": "Gemma-4-26B-A4B-it Box web artifact experimental",
        "repo": "litert-community/gemma-4-26B-A4B-it-litert-lm",
        "file": "gemma-4-26B-A4B-it-web.litertlm",
        "pinned_revision": "755026618afd72ebb6d970f784d42effa67398bc",
        "pinned_size": 15786524672,
        "min_ram": 16,
        "description": "Experimental Gemma 4 26B-A4B text model using the exact public web LiteRT-LM artifact and configuration proven by Box v3.3.2 on the target phone. 15.8 GB; GPU only.",
        "config": {"topK": 64, "topP": 0.95, "temperature": 1.0, "maxTokens": 4000, "accelerators": "gpu", "maxContextLength": 32000},
        "taskTypes": ["llm_chat", "llm_prompt_lab", "llm_agent_chat"],
        "bestForTaskTypes": ["llm_chat", "llm_prompt_lab"],
    },
]

frozen = []
for spec in SPECS:
    item = dict(spec)
    if spec.get("pinned_revision"):
        revision = spec["pinned_revision"]
        size = int(spec["pinned_size"])
        filename = spec["file"]
    else:
        data = hf_json(spec["repo"])
        revision = data.get("sha")
        if not isinstance(revision, str) or len(revision) != 40:
            fail(f"invalid immutable revision for {spec['repo']}: {revision!r}")
        siblings = data.get("siblings") or []
        filename = spec.get("file")
        if filename is None:
            candidates = [s.get("rfilename") for s in siblings if str(s.get("rfilename", "")).endswith(".litertlm")]
            if len(candidates) != 1:
                fail(f"expected exactly one .litertlm file, got {candidates}")
            filename = candidates[0]
        matches = [s for s in siblings if s.get("rfilename") == filename]
        if len(matches) != 1:
            fail(f"missing/ambiguous file {spec['repo']}/{filename}")
        size = sibling_size(matches[0])
        if size is None:
            fail(f"missing exact byte size for {spec['repo']}/{filename}")
    if int(size) < 100_000_000:
        fail(f"implausible model size for {spec['repo']}/{filename}: {size}")
    item["file"] = filename
    item["revision"] = revision
    item["size"] = int(size)
    item.pop("pinned_revision", None)
    item.pop("pinned_size", None)
    frozen.append(item)

if len(frozen) != 8:
    fail("freeze did not produce exactly 8 models")

freeze_doc = {
    "schema": "local-agent-plaza.mcp248-model-freeze.v1",
    "source": "Hugging Face model API at build time; Gemma 4 26B pinned to Box v3.3.2-proven revision",
    "models": frozen,
}
FREEZE_OUT.parent.mkdir(parents=True, exist_ok=True)
FREEZE_OUT.write_text(json.dumps(freeze_doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

allowlists = [
    REPO_ROOT / "model_allowlists/1_0_14.json",
    ANDROID_ROOT / "app/src/main/assets/model_allowlists/1_0_14.json",
]
raw = [p.read_bytes() for p in allowlists]
if raw[0] != raw[1]:
    fail("the two MCP247 allowlists are not byte-identical before MCP248 patch")

data = json.loads(raw[0])
models = data.get("models")
if not isinstance(models, list):
    fail("allowlist models missing")

qwen_name = "Qwen3.5-2B LiteRT-LM Q8 32768 Plaza MCP247"
if sum(1 for m in models if m.get("name") == qwen_name) != 1:
    fail("MCP247 canonical Qwen3.5 2B entry missing before MCP248 patch")
if any(m.get("modelId") == "paulsp94/Qwen3.5-2B-LiteRT-LM" for m in models):
    fail("obsolete pre-MCP247 Qwen3.5 entries leaked into MCP248 workspace")

existing_identity = {(m.get("modelId"), m.get("modelFile"), m.get("commitHash")) for m in models}
for spec in frozen:
    identity = (spec["repo"], spec["file"], spec["revision"])
    if identity in existing_identity:
        fail(f"exact duplicate already in allowlist: {identity}")
    entry = {
        "name": spec["name"],
        "modelId": spec["repo"],
        "modelFile": spec["file"],
        "description": spec["description"],
        "sizeInBytes": spec["size"],
        "minDeviceMemoryInGb": spec["min_ram"],
        "commitHash": spec["revision"],
        "defaultConfig": spec["config"],
        "taskTypes": spec["taskTypes"],
    }
    for optional in ("capabilities", "capabilityToTaskTypes", "bestForTaskTypes"):
        if optional in spec:
            entry[optional] = spec[optional]
    models.append(entry)
    existing_identity.add(identity)

out = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
for p in allowlists:
    p.write_text(out, encoding="utf-8")

parsed = json.loads(out)
for spec in frozen:
    hits = [m for m in parsed["models"] if m.get("modelId") == spec["repo"] and m.get("modelFile") == spec["file"] and m.get("commitHash") == spec["revision"]]
    if len(hits) != 1:
        fail(f"postcondition mismatch for {spec['key']}: {len(hits)}")
    if int(hits[0].get("sizeInBytes", -1)) != spec["size"]:
        fail(f"size postcondition mismatch for {spec['key']}")

if allowlists[0].read_bytes() != allowlists[1].read_bytes():
    fail("two allowlists diverged after MCP248 patch")

print("MCP248_MODEL_POOL_FREEZE_AND_PATCH_PASS")
for spec in frozen:
    print(f"{spec['key']}|{spec['repo']}|{spec['file']}|{spec['revision']}|{spec['size']}")
