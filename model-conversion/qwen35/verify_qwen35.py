#!/usr/bin/env python3
"""Validate a converted Qwen3.5 LiteRT-LM bundle before publishing it."""

from __future__ import annotations

import argparse
import importlib.metadata
import json
from pathlib import Path
import shutil
import sys
import tempfile
import time


def load_config(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def extract_text(message) -> str:
    if isinstance(message, str):
        return message
    if isinstance(message, dict):
        content = message.get("content")
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            chunks = []
            for item in content:
                if isinstance(item, str):
                    chunks.append(item)
                elif isinstance(item, dict) and isinstance(item.get("text"), str):
                    chunks.append(item["text"])
            return "".join(chunks)
        if isinstance(message.get("text"), str):
            return message["text"]
    return str(message)


def verify_magic(bundle: Path) -> str:
    with bundle.open("rb") as f:
        magic = f.read(8)
    if magic != b"LITERTLM":
        raise RuntimeError(f"Magic gate failed: expected b'LITERTLM', got {magic!r}")
    return magic.decode("ascii")


def verify_archive_and_executor_metadata(bundle: Path) -> dict:
    import litert_lm_builder
    from litert_lm_builder.runtime.proto import executor_metadata_pb2

    with tempfile.TemporaryDirectory(prefix="qwen35-litert-unpack-") as td:
        unpack_dir = Path(td)
        litert_lm_builder.LitertLmFileBuilder.unpack(str(bundle), str(unpack_dir))

        metadata_files = sorted(unpack_dir.rglob("*executor_metadata*"))
        metadata_files = [p for p in metadata_files if p.is_file()]
        if not metadata_files:
            raise RuntimeError(
                "Executor metadata gate failed: archive contains no executor_metadata section"
            )

        metadata = executor_metadata_pb2.ExecutorMetadata()
        parsed_path = None
        last_error = None
        for p in metadata_files:
            try:
                metadata.ParseFromString(p.read_bytes())
                if metadata.HasField("llm_executor_metadata"):
                    parsed_path = p
                    break
            except Exception as exc:  # pragma: no cover - diagnostic fallback
                last_error = exc
        if parsed_path is None:
            raise RuntimeError(
                f"Executor metadata gate failed: could not parse {metadata_files}; last_error={last_error}"
            )

        buffers = list(metadata.llm_executor_metadata.state_buffers)
        if not buffers:
            raise RuntimeError("Executor metadata gate failed: state_buffers is empty")

        names = []
        type_names = []
        for b in buffers:
            name = b.prefill_input_name or b.decode_input_name
            names.append(name)
            try:
                type_names.append(executor_metadata_pb2.StateBuffer.Type.Name(b.type))
            except Exception:
                type_names.append(str(b.type))

        required_prefixes = ("kv_cache_c_", "kv_cache_r_", "kv_cache_k_", "kv_cache_v_")
        missing = [prefix for prefix in required_prefixes if not any(n.startswith(prefix) for n in names)]
        if missing:
            raise RuntimeError(
                f"Executor metadata gate failed: missing Qwen3.5 state prefixes {missing}; "
                f"sample_names={names[:24]}"
            )

        linear_count = sum(n.startswith("kv_cache_c_") or n.startswith("kv_cache_r_") for n in names)
        kv_count = sum(n.startswith("kv_cache_k_") or n.startswith("kv_cache_v_") for n in names)
        if linear_count == 0 or kv_count == 0:
            raise RuntimeError(
                f"Hybrid-state gate failed: linear_count={linear_count}, kv_count={kv_count}"
            )

        return {
            "executor_metadata_file": parsed_path.name,
            "state_buffer_count": len(buffers),
            "linear_recurrent_state_buffer_count": linear_count,
            "full_attention_kv_state_buffer_count": kv_count,
            "state_type_names": sorted(set(type_names)),
            "state_name_sample": names[:24],
        }


def runtime_smoke(bundle: Path, cfg: dict) -> dict:
    import litert_lm

    requested_max = int(cfg.get("smoke_max_num_tokens", cfg["cache_length"]))
    prompt = str(cfg.get("smoke_prompt", "你好"))
    min_chars = int(cfg.get("smoke_min_output_chars", 1))

    started = time.time()
    with litert_lm.Engine(str(bundle), max_num_tokens=requested_max) as engine:
        engine_ready = time.time()
        with engine.create_conversation() as conversation:
            conv_ready = time.time()
            message = conversation.send_message(prompt)
            finished = time.time()

    text = extract_text(message).strip()
    if len(text) < min_chars:
        raise RuntimeError(
            f"Runtime smoke gate failed: output too short ({len(text)} chars): {text!r}"
        )

    try:
        runtime_version = importlib.metadata.version("litert-lm-api-nightly")
    except importlib.metadata.PackageNotFoundError:
        try:
            runtime_version = importlib.metadata.version("litert-lm-api")
        except importlib.metadata.PackageNotFoundError:
            runtime_version = "unknown"

    return {
        "runtime_package_version": runtime_version,
        "max_num_tokens": requested_max,
        "engine_create_seconds": round(engine_ready - started, 3),
        "conversation_create_seconds": round(conv_ready - engine_ready, 3),
        "send_message_seconds": round(finished - conv_ready, 3),
        "output_chars": len(text),
        "output_preview": text[:500],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    args = parser.parse_args()

    cfg = load_config(args.config)
    bundle = args.bundle.resolve()
    if not bundle.is_file():
        raise FileNotFoundError(bundle)

    print("=== Gate 1: LiteRT-LM magic ===")
    magic = verify_magic(bundle)
    print(f"magic={magic}")

    print("=== Gate 2/3: archive + Qwen3.5 hybrid executor state metadata ===")
    state_report = verify_archive_and_executor_metadata(bundle)
    print(json.dumps(state_report, ensure_ascii=False, indent=2))

    print("=== Gate 4/5: LiteRT-LM 0.15-era Engine + Conversation + generation smoke test ===")
    smoke_report = runtime_smoke(bundle, cfg)
    print(json.dumps(smoke_report, ensure_ascii=False, indent=2))

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    manifest["verification"] = {
        "magic": magic,
        "hybrid_executor_state": state_report,
        "runtime_smoke": smoke_report,
        "status": "PASS",
    }
    args.manifest.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (args.manifest.parent / "runtime_smoke_output.txt").write_text(
        smoke_report["output_preview"] + "\n", encoding="utf-8"
    )
    print("ALL VALIDATION GATES PASSED")


if __name__ == "__main__":
    main()
