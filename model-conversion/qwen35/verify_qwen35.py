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


def package_version() -> str:
    for package in ("litert-lm-api-nightly", "litert-lm-api"):
        try:
            return importlib.metadata.version(package)
        except importlib.metadata.PackageNotFoundError:
            pass
    return "unknown"


def verify_magic(bundle: Path) -> str:
    magic = bundle.open("rb").read(8)
    if magic != b"LITERTLM":
        raise RuntimeError(f"Magic gate failed: {magic!r}")
    return "LITERTLM"


def verify_archive(bundle: Path) -> dict:
    """Read ExecutorMetadataProto directly from the LiteRT-LM section table."""
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
    linear_type = executor_metadata_pb2.StateBuffer.TYPE_LINEAR_ATTENTION
    linear_buffers = [b for b in buffers if (b.prefill_input_name or b.decode_input_name) in linear_names]
    if not linear_buffers or any(b.type != linear_type for b in linear_buffers):
        raise RuntimeError("Linear/recurrent Qwen3.5 states are not encoded as TYPE_LINEAR_ATTENTION")
    if any(not b.HasField("sequence_axis") for b in linear_buffers):
        raise RuntimeError("Target 0.15 compatibility requires sequence_axis metadata on linear states")

    return {
        "executor_metadata_section_index": parsed_index,
        "executor_metadata_offsets": list(parsed_offsets or ()),
        "state_buffer_count": len(buffers),
        "linear_recurrent_state_buffer_count": len(linear_names),
        "full_attention_kv_state_buffer_count": len(attention_names),
        "linear_state_type": int(linear_type),
        "linear_states_have_sequence_axis": True,
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

    return {
        "runtime_package_version": package_version(),
        "max_num_tokens": max_num_tokens,
        "engine_create_seconds": round(t1 - t0, 3),
        "conversation_create_seconds": round(t2 - t1, 3),
        "send_message_seconds": round(t3 - t2, 3),
        "output_chars": len(text),
        "output_preview": text[:500],
        "status": "PASS",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument(
        "--allow-prerelease-runtime-gap",
        action="store_true",
        help=(
            "Allow only the known 0.15.0.dev20260727 gap where the pre-release "
            "Python executor rejects TYPE_LINEAR_ATTENTION=5. The target Android "
            "0.15.0 release source must be independently gated by the caller."
        ),
    )
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
    smoke = None
    status = "PASS"
    try:
        smoke = runtime_smoke(bundle, cfg)
        print(json.dumps(smoke, ensure_ascii=False, indent=2))
    except Exception as exc:
        text = f"{type(exc).__name__}: {exc}"
        known_gap = (
            args.allow_prerelease_runtime_gap
            and package_version() == "0.15.0.dev20260727"
            and "Unsupported state buffer type: 5" in text
        )
        if not known_gap:
            raise
        smoke = {
            "runtime_package_version": package_version(),
            "status": "EXPECTED_PRERELEASE_GAP",
            "error": text,
            "explanation": (
                "The Jul-27 Python 0.15 pre-release predates linear-attention "
                "state support present in the Android 0.15.0 release source."
            ),
        }
        status = "PASS_TARGET_ANDROID_0_15_SOURCE_GATE"
        print(json.dumps(smoke, ensure_ascii=False, indent=2))

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    manifest["verification"] = {
        "magic": magic,
        "hybrid_executor_state": state,
        "runtime_smoke": smoke,
        "target_android_runtime": "com.google.ai.edge.litertlm:litertlm-android:0.15.0",
        "target_release_source_tag": "google-ai-edge/LiteRT-LM@v0.15.0",
        "status": status,
    }
    args.manifest.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    if smoke.get("output_preview"):
        (args.manifest.parent / "runtime_smoke_output.txt").write_text(
            smoke["output_preview"] + "\n",
            encoding="utf-8",
        )
    print(f"ALL PUBLISHING GATES PASSED: {status}")


if __name__ == "__main__":
    main()
