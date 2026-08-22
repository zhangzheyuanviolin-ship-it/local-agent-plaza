#!/usr/bin/env python3
"""Recreate the exact MCP248 eight-model allowlist pool from immutable MCP248 acceptance data.

MCP249 must not drift when a Hugging Face repository moves its default branch. This script performs
no network lookup and changes no model artifact identity relative to the user's MCP248 build.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import sys

REPO_ROOT = Path(__file__).resolve().parents[2]
ANDROID_ROOT = REPO_ROOT / "Android/src"
OUT = Path(os.environ.get("MCP249_FREEZE_JSON", "/tmp/mcp249_model_freeze.json"))


def fail(msg: str) -> None:
    print(f"MCP249 model-pool pin failure: {msg}", file=sys.stderr)
    raise SystemExit(1)


MODELS = [
    {
        "key":"ministral3b","name":"Ministral-3-3B-Instruct-2512 LiteRT","repo":"litert-community/Ministral-3-3B-Instruct-2512",
        "file":"Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm","revision":"e69d446849a7723eb6eceac970deca94be97dc0c","size":2340982768,"min_ram":10,
        "description":"Ministral 3B Instruct LiteRT-LM blockwise INT4/OCTAV text model. 4096 KV context; experimental cross-family Agent control model.",
        "config":{"topK":40,"topP":0.9,"temperature":0.7,"maxTokens":2048,"accelerators":"gpu,cpu","maxContextLength":4096},
        "taskTypes":["llm_chat","llm_prompt_lab","llm_agent_chat"],
    },
    {
        "key":"phi4mini","name":"Phi-4-mini-instruct Q8 4096 LiteRT","repo":"litert-community/Phi-4-mini-instruct",
        "file":"Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm","revision":"8cd368be75fdb94d5a6f6f5b40f1ab22a6c2543e","size":3910090752,"min_ram":10,
        "description":"Microsoft Phi-4 mini instruct LiteRT-LM Q8 model with 4096 KV context. Function-calling-trained upstream; experimental Agent candidate.",
        "config":{"topK":40,"topP":0.9,"temperature":0.7,"maxTokens":2048,"accelerators":"gpu,cpu","maxContextLength":4096},
        "taskTypes":["llm_chat","llm_prompt_lab","llm_agent_chat"],
    },
    {
        "key":"llama32_3b","name":"Llama-3.2-3B-Instruct LiteRT block32","repo":"mlboydaisuke/Llama-3.2-3B-Instruct-LiteRT",
        "file":"model.litertlm","revision":"c1ddfa1879bb812752db254e3f2e6eb65fe38b6a","size":2210301872,"min_ram":10,
        "description":"Llama 3.2 3B Instruct LiteRT-LM blockwise INT4 + INT8 embedding conversion, 4096 KV context; direct-answering control model.",
        "config":{"topK":40,"topP":0.9,"temperature":0.7,"maxTokens":2048,"accelerators":"gpu,cpu","maxContextLength":4096},
        "taskTypes":["llm_chat","llm_prompt_lab","llm_agent_chat"],
    },
    {
        "key":"falcon_h1_3b","name":"Falcon-H1-3B-Instruct INT8 LiteRT","repo":"litert-community/Falcon-H1-3B-Instruct",
        "file":"Falcon-H1-3B-Instruct_int8.litertlm","revision":"d64b51d448c3313468ba0dbad463d0c12f01f47c","size":3385302368,"min_ram":10,
        "description":"Falcon-H1 3B Instruct hybrid attention + Mamba2 LiteRT-LM INT8 model. Requires LiteRT-LM 0.15+; experimental architecture-compatibility control.",
        "config":{"topK":40,"topP":0.9,"temperature":0.7,"maxTokens":2048,"accelerators":"gpu,cpu","maxContextLength":4096},
        "taskTypes":["llm_chat","llm_prompt_lab","llm_agent_chat"],
    },
    {
        "key":"jan_nano","name":"Jan-nano 4B reasoning LiteRT","repo":"litert-community/Jan-nano",
        "file":"model.litertlm","revision":"492a1af5794a6d1ea573cd360655ba935b4f3b8f","size":2474357680,"min_ram":10,
        "description":"Jan-nano 4B deep-research/MCP-oriented Qwen3 derivative, LiteRT-LM blockwise INT4. Reasoning model; 4096 KV context.",
        "config":{"topK":20,"topP":0.8,"temperature":0.6,"maxTokens":4096,"accelerators":"gpu,cpu","maxContextLength":4096},
        "taskTypes":["llm_chat","llm_prompt_lab","llm_agent_chat"],"capabilities":["llm_thinking"],
        "capabilityToTaskTypes":{"llm_thinking":["llm_chat","llm_prompt_lab","llm_agent_chat"]},
    },
    {
        "key":"fastcontext4b","name":"FastContext-1.0-4B-SFT LiteRT block32","repo":"litert-community/FastContext-1.0-4B-SFT",
        "file":"model.litertlm","revision":"2eebcbbacbb644bd656137a243e0248e465a6e80","size":2662888368,"min_ram":10,
        "description":"FastContext 4B repository-exploration/tool-calling SFT LiteRT-LM block32 quality build, 4096 KV context.",
        "config":{"topK":20,"topP":0.8,"temperature":0.6,"maxTokens":2048,"accelerators":"gpu,cpu","maxContextLength":4096},
        "taskTypes":["llm_chat","llm_prompt_lab","llm_agent_chat"],"bestForTaskTypes":["llm_agent_chat"],
    },
    {
        "key":"laguna_xs2","name":"Laguna XS.2 phone k4 fold3 LiteRT experimental","repo":"poolside-laguna-hackathon/laguna-xs2-phone-k4-fold3-litert",
        "file":"model.litertlm","revision":"db2a76388c4f8105790334cb4ff2a81ea7a8b15c","size":3037564832,"min_ram":14,
        "description":"Laguna XS.2 phone k4 fold3 LiteRT experimental MoE/agentic-coding root phone artifact. Treat as a reasoning/tool experiment; English/coding oriented.",
        "config":{"topK":40,"topP":0.9,"temperature":0.6,"maxTokens":4096,"accelerators":"gpu,cpu","maxContextLength":4096},
        "taskTypes":["llm_chat","llm_prompt_lab","llm_agent_chat"],"capabilities":["llm_thinking"],
        "capabilityToTaskTypes":{"llm_thinking":["llm_chat","llm_prompt_lab","llm_agent_chat"]},
    },
    {
        "key":"gemma4_26b_a4b","name":"Gemma-4-26B-A4B-it Box web artifact experimental","repo":"litert-community/gemma-4-26B-A4B-it-litert-lm",
        "file":"gemma-4-26B-A4B-it-web.litertlm","revision":"755026618afd72ebb6d970f784d42effa67398bc","size":15786524672,"min_ram":16,
        "description":"Experimental Gemma 4 26B-A4B text model using the exact public web LiteRT-LM artifact and configuration proven by Box v3.3.2 on the target phone. 15.8 GB; GPU only.",
        "config":{"topK":64,"topP":0.95,"temperature":1.0,"maxTokens":4000,"accelerators":"gpu","maxContextLength":32000},
        "taskTypes":["llm_chat","llm_prompt_lab","llm_agent_chat"],"bestForTaskTypes":["llm_chat","llm_prompt_lab"],
    },
]

if len(MODELS) != 8 or len({(m["repo"],m["file"],m["revision"]) for m in MODELS}) != 8:
    fail("pinned model inventory is not exactly eight unique identities")
if any(len(m["revision"]) != 40 or int(m["size"]) < 100_000_000 for m in MODELS):
    fail("invalid immutable revision or size")

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps({
    "schema":"local-agent-plaza.mcp249-model-freeze.v1",
    "source":"Exact immutable model identities copied from the MCP248 FULLY_VERIFIED_PASS acceptance manifest; no mutable HEAD lookup",
    "models":MODELS,
}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

allowlists = [
    REPO_ROOT / "model_allowlists/1_0_14.json",
    ANDROID_ROOT / "app/src/main/assets/model_allowlists/1_0_14.json",
]
if allowlists[0].read_bytes() != allowlists[1].read_bytes():
    fail("the two MCP247 allowlists differ before MCP249 pin")
data = json.loads(allowlists[0].read_text(encoding="utf-8"))
models = data.get("models")
if not isinstance(models, list):
    fail("allowlist models missing")
qwen = "Qwen3.5-2B LiteRT-LM Q8 32768 Plaza MCP247"
if sum(1 for m in models if m.get("name") == qwen) != 1:
    fail("canonical MCP247 Qwen missing")
if any(m.get("modelId") == "paulsp94/Qwen3.5-2B-LiteRT-LM" for m in models):
    fail("obsolete Qwen entry present")

existing={(m.get("modelId"),m.get("modelFile"),m.get("commitHash")) for m in models}
for spec in MODELS:
    ident=(spec["repo"],spec["file"],spec["revision"])
    if ident in existing:
        fail(f"duplicate identity before insert: {ident}")
    entry={
        "name":spec["name"],"modelId":spec["repo"],"modelFile":spec["file"],
        "description":spec["description"],"sizeInBytes":spec["size"],
        "minDeviceMemoryInGb":spec["min_ram"],"commitHash":spec["revision"],
        "defaultConfig":spec["config"],"taskTypes":spec["taskTypes"],
    }
    for key in ("capabilities","capabilityToTaskTypes","bestForTaskTypes"):
        if key in spec: entry[key]=spec[key]
    models.append(entry); existing.add(ident)

out=json.dumps(data,ensure_ascii=False,indent=2)+"\n"
for p in allowlists: p.write_text(out,encoding="utf-8")
if allowlists[0].read_bytes()!=allowlists[1].read_bytes(): fail("allowlists diverged after pin")
parsed=json.loads(out)
for spec in MODELS:
    hits=[m for m in parsed["models"] if (m.get("modelId"),m.get("modelFile"),m.get("commitHash"))==(spec["repo"],spec["file"],spec["revision"])]
    if len(hits)!=1 or int(hits[0].get("sizeInBytes",-1))!=spec["size"]: fail(f"postcondition failed: {spec['key']}")

print("MCP249_EXACT_MCP248_PINNED_MODEL_POOL_PASS")
for m in MODELS: print(f"{m['key']}|{m['revision']}|{m['size']}")
