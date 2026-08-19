#!/usr/bin/env python3
"""Validate a converted Qwen3.5 LiteRT-LM bundle before publishing it."""
from __future__ import annotations

import argparse
import importlib.metadata
import io
import json
import time
from pathlib import Path


def extract_text(message) -> str:
    if isinstance(message, str):
        return message
    if isinstance(message, dict):
        content = message.get("content")
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            out = []
            for item in content:
                if isinstance(item, str):
                    out.append(item)
                elif isinstance(item, dict) and isinstance(item.get("text"), str):
                    out.append(item["text"])
            return "".join(out)
        if isinstance(message.get("text"), str):
            return message["text"]
    return str(message)


def verify_magic(bundle: Path) -> str:
    magic = bundle.open("rb").read(8)
    if magic != b"LITERTLM":
        raise RuntimeError(f"Magic gate failed: {magic!r}")
    return "LITERTLM"


def verify_archive(bundle: Path) -> dict:
    """Read ExecutorMetadataProto directly from the LiteRT-LM section table.

    LitertLmFileBuilder.unpack() writes the executor metadata as a pbtext file
    named `ExecutorMetadataProto.pbtext`; the old verifier searched for a
    lowercase binary filename and therefore produced a false negative. Reading
    the section bytes from the official LiteRT-LM header is both faster and
    format-accurate for a multi-gigabyte bundle.
    """
    from litert_lm_builder import litertlm_core, litertlm_peek
    from litert_lm_builder.runtime.proto import executor_metadata_pb2

    output = io.StringIO()
    metadata = litertlm_peek.read_litertlm_header(str(bundle), output)
    sections = metadata.SectionMetadata()
    if sections is None:
        raise RuntimeError("LiteRT-LM header has no section metadata")

    executor_sections = []
    with litertlm_core.open_file(str(bundle), "rb") as stream:
        for index in range(sections.ObjectsLength()):
            section = sections.Objects(index)
            if section is None:
                continue
            data_type = litertlm_core.any_section_data_type_to_string(section.DataType())
            if data_type != "ExecutorMetadataProto":
                continue
            begin = int(section.BeginOffset())
            end = int(section.EndOffset())
            if end <= begin:
                raise RuntimeError(
                    f"ExecutorMetadataProto section {index} has invalid offsets {begin}..{end}"
                )
            stream.seek(begin)
            payload = stream.read(end - begin)
            executor_sections.append((index, begin, end, payload))

    if not executor_sections:
        section_types = []
        for index in range(sections.ObjectsLength()):
            section = sections.Objects(index)
            if section is not None:
                section_types.append(
                    litertlm_core.any_section_data_type_to_string(section.DataType())
                )
        raise RuntimeError(
            "Archive has no ExecutorMetadataProto section; "
            f"section_types={section_types}"
        )

    parsed = None
    parsed_index = None
    parsed_offsets = None
    parse_errors = []
    for index, begin, end, payload in executor_sections:
        meta = executor_metadata_pb2.ExecutorMetadata()
        try:
            meta.ParseFromString(payload)
            if meta.HasField("llm_executor_metadata"):
                parsed = meta
                parsed_index = index
                parsed_offsets = (begin, end)
                break
            parse_errors.append(f"section {index}: parsed but llm_executor_metadata missing")
        except Exception as exc:
            parse_errors.append(f"section {index}: {type(exc).__name__}: {exc}")

    if parsed is None:
        raise RuntimeError(
            "Could not parse a usable ExecutorMetadataProto section: "
            + "; ".join(parse_errors)
        )

    buffers = list(parsed.llm_executor_metadata.state_buffers)
    names = [b.prefill_input_name or b.decode_input_name for b in buffers]
    prefixes = ("kv_cache_c_", "kv_cache_r_", "kv_cache_k_", "kv_cache_v_")
    missing = [prefix for prefix in prefixes if not any(name.startswith(prefix) for name in names)]
    if missing:
        raise RuntimeError(
            f"Missing Qwen3.5 state prefixes {missing}; sample={names[:40]}"
        )

    linear_names = [n for n in names if n.startswith("kv_cache_c_") or n.startswith("kv_cache_r_")]
    attention_names = [n for n in names if n.startswith("kv_cache_k_") or n.startswith("kv_cache_v_")]
    return {
        "executor_metadata_section_index": parsed_index,
        "executor_metadata_offsets": list(parsed_offsets or ()),
        "state_buffer_count": len(buffers),
        "linear_recurrent_state_buffer_count": len(linear_names),
        "full_attention_kv_state_buffer_count": len(attention_names),
        "max_history_size": int(parsed.llm_executor_metadata.max_history_size),
        "state_name_sample": names[:40],
    }


def runtime_smoke(bundle: Path, cfg: dict) -> dict:
    import litert_lm

    max_num_tokens = int(cfg.get("smoke_max_num_tokens", cfg["cache_length"]))
    prompt = str(cfg.get("smoke_prompt", "你好"))
    t0 = time.time()
    with litert_lm.Engine(str(bundle), max_num_tokens=max_num_tokens) as engine:
        t1 = time.time()
        with engine.create_conversation() as conversation:
            t2 = time.time()
            message = conversation.send_message(prompt)
            t3 = time.time()
    text = extract_text(message).strip()
    if len(text) < int(cfg.get("smoke_min_output_chars", 1)):
        raise RuntimeError(f"Runtime output too short: {text!r}")

    version = "unknown"
    for package in ("litert-lm-api-nightly", "litert-lm-api"):
        try:
            version = importlib.metadata.version(package)
            break
        except importlib.metadata.PackageNotFoundError:
            pass
    return {
        "runtime_package_version": version,
        "max_num_tokens": max_num_tokens,
        "engine_create_seconds": round(t1 - t0, 3),
        "conversation_create_seconds": round(t2 - t1, 3),
        "send_message_seconds": round(t3 - t2, 3),
        "output_chars": len(text),
        "output_preview": text[:500],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    args = parser.parse_args()

    cfg = json.loads(args.config.read_text(encoding="utf-8"))
    bundle = args.bundle.resolve()

    print("=== Gate 1 magic ===")
    magic = verify_magic(bundle)
    print(magic)

    print("=== Gate 2 hybrid executor state ===")
    state = verify_archive(bundle)
    print(json.dumps(state, ensure_ascii=False, indent=2))

    print("=== Gate 3 runtime smoke ===")
    smoke = runtime_smoke(bundle, cfg)
    print(json.dumps(smoke, ensure_ascii=False, indent=2))

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    manifest["verification"] = {
        "magic": magic,
        "hybrid_executor_state": state,
        "runtime_smoke": smoke,
        "status": "PASS",
    }
    args.manifest.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (args.manifest.parent / "runtime_smoke_output.txt").write_text(
        smoke["output_preview"] + "\n",
        encoding="utf-8",
    )
    print("ALL VALIDATION GATES PASSED")


if __name__ == "__main__":
    main()
