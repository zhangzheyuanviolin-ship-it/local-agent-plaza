#!/usr/bin/env python3
"""Validate a converted Qwen3.5 LiteRT-LM bundle before publishing it."""

from __future__ import annotations

import argparse
import importlib.metadata
import json
from pathlib import Path
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


def _parse_executor_metadata(path: Path):
    from google.protobuf import text_format
    from litert_lm_builder.runtime.proto import executor_metadata_pb2

    metadata = executor_metadata_pb2.ExecutorMetadata()
    if path.suffix.lower() in {".pbtext", ".textproto", ".txt"}:
        text_format.Parse(path.read_text(encoding="utf-8"), metadata)
    else:
        metadata.ParseFromString(path.read_bytes())
    return metadata


def verify_archive_and_executor_metadata(bundle: Path, expected_cache_length: int) -> dict:
    import litert_lm_builder
    from litert_lm_builder.runtime.proto import executor_metadata_pb2

    with tempfile.TemporaryDirectory(prefix="qwen35-litert-unpack-") as td:
        unpack_dir = Path(td)
        # LiteRT-LM 0.15's unpacker emits ExecutorMetadataProto.pbtext.  The
        # previous verifier looked only for '*executor_metadata*', which misses
        # the canonical CamelCase filename and produced a false negative after
        # an otherwise successful conversion.
        litert_lm_builder.unpack(str(bundle), str(unpack_dir))

        metadata_files = [
            p
            for p in unpack_dir.rglob("*")
            if p.is_file()
            and (
                "executormetadata" in p.name.lower().replace("_", "")
                or "executor_metadata" in p.name.lower()
            )
        ]
        metadata_files = sorted(metadata_files)
        if not metadata_files:
            dumped = sorted(p.name for p in unpack_dir.iterdir())
            raise RuntimeError(
                "Executor metadata gate failed: archive contains no executor metadata; "
                f"unpacked_files={dumped}"
            )

        metadata = None
        parsed_path = None
        last_error = None
        for p in metadata_files:
            try:
                candidate = _parse_executor_metadata(p)
                if candidate.HasField("llm_executor_metadata"):
                    metadata = candidate
                    parsed_path = p
                    break
            except Exception as exc:  # pragma: no cover - diagnostic fallback
                last_error = exc
        if metadata is None or parsed_path is None:
            raise RuntimeError(
                f"Executor metadata gate failed: could not parse {metadata_files}; "
                f"last_error={last_error}"
            )

        buffers = list(metadata.llm_executor_metadata.state_buffers)
        if not buffers:
            raise RuntimeError("Executor metadata gate failed: state_buffers is empty")

        names = []
        type_names = []
        linear = []
        full_k = []
        full_v = []
        for b in buffers:
            name = b.prefill_input_name or b.decode_input_name
            names.append(name)
            try:
                type_names.append(executor_metadata_pb2.StateBuffer.Type.Name(b.type))
            except Exception:
                type_names.append(str(b.type))
            if name.startswith(("kv_cache_c_", "kv_cache_r_")):
                linear.append(b)
            elif name.startswith("kv_cache_k_"):
                full_k.append(b)
            elif name.startswith("kv_cache_v_"):
                full_v.append(b)

        required_prefixes = ("kv_cache_c_", "kv_cache_r_", "kv_cache_k_", "kv_cache_v_")
        missing = [prefix for prefix in required_prefixes if not any(n.startswith(prefix) for n in names)]
        if missing:
            raise RuntimeError(
                f"Executor metadata gate failed: missing Qwen3.5 state prefixes {missing}; "
                f"sample_names={names[:24]}"
            )

        # Qwen3.5-2B has 18 linear-attention layers and 6 full-attention
        # layers.  Each linear layer owns c+r recurrent states; each full
        # attention layer owns k+v states.
        if len(linear) != 36 or len(full_k) != 6 or len(full_v) != 6:
            raise RuntimeError(
                "Hybrid-state count gate failed: "
                f"linear={len(linear)}, full_k={len(full_k)}, full_v={len(full_v)}"
            )

        bad_linear_type = [
            b.prefill_input_name or b.decode_input_name
            for b in linear
            if b.type != executor_metadata_pb2.StateBuffer.TYPE_LINEAR_ATTENTION
        ]
        if bad_linear_type:
            raise RuntimeError(f"Linear state type gate failed: {bad_linear_type}")

        bad_linear_axis = [
            b.prefill_input_name or b.decode_input_name
            for b in linear
            if not b.HasField("sequence_axis") or b.sequence_axis != 0
        ]
        if bad_linear_axis:
            raise RuntimeError(f"Linear sequence_axis gate failed: {bad_linear_axis}")

        bad_k = []
        for b in full_k:
            name = b.prefill_input_name or b.decode_input_name
            if b.type != executor_metadata_pb2.StateBuffer.TYPE_GLOBAL_KEY_CACHE:
                bad_k.append(f"{name}:type={b.type}")
            if not b.HasField("sequence_axis") or b.sequence_axis != 2:
                bad_k.append(f"{name}:axis={getattr(b, 'sequence_axis', None)}")
            if (
                not b.HasField("maximum_sequence_length")
                or b.maximum_sequence_length != expected_cache_length
            ):
                bad_k.append(
                    f"{name}:max={getattr(b, 'maximum_sequence_length', None)}"
                )
        if bad_k:
            raise RuntimeError(f"Full-attention K metadata gate failed: {bad_k}")

        bad_v = []
        for b in full_v:
            name = b.prefill_input_name or b.decode_input_name
            if b.type != executor_metadata_pb2.StateBuffer.TYPE_GLOBAL_VALUE_CACHE:
                bad_v.append(f"{name}:type={b.type}")
            if not b.HasField("sequence_axis") or b.sequence_axis != 3:
                bad_v.append(f"{name}:axis={getattr(b, 'sequence_axis', None)}")
            if (
                not b.HasField("maximum_sequence_length")
                or b.maximum_sequence_length != expected_cache_length
            ):
                bad_v.append(
                    f"{name}:max={getattr(b, 'maximum_sequence_length', None)}"
                )
        if bad_v:
            raise RuntimeError(f"Full-attention V metadata gate failed: {bad_v}")

        return {
            "executor_metadata_file": parsed_path.name,
            "state_buffer_count": len(buffers),
            "linear_recurrent_state_buffer_count": len(linear),
            "full_attention_k_state_buffer_count": len(full_k),
            "full_attention_v_state_buffer_count": len(full_v),
            "full_attention_kv_state_buffer_count": len(full_k) + len(full_v),
            "state_type_names": sorted(set(type_names)),
            "state_name_sample": names[:24],
            "sequence_axis_zero_all_linear_states": True,
            "full_attention_k_sequence_axis": 2,
            "full_attention_v_sequence_axis": 3,
            "full_attention_maximum_sequence_length": expected_cache_length,
            "status": "PASS",
        }


def runtime_smoke(bundle: Path, cfg: dict) -> dict:
    import litert_lm

    requested_max = int(cfg.get("smoke_max_num_tokens", cfg["cache_length"]))
    prompt = str(cfg.get("smoke_prompt", "你好"))
    min_chars = int(cfg.get("smoke_min_output_chars", 1))

    # Preserve the historical MCP238 conversion metadata exactly
    # (top_k=1/top_p=1/temp=0). LiteRT-LM 0.15 CPU does not implement the
    # resulting GREEDY sampler enum. The Plaza app supplies its runtime sampler,
    # so the CPU smoke test uses the same MCP238 runtime values without
    # modifying or repacking the model bundle.
    runtime_sampler = {
        "top_k": 20,
        "top_p": 0.8,
        "temperature": 0.6,
    }

    started = time.time()
    with litert_lm.Engine(str(bundle), max_num_tokens=requested_max) as engine:
        engine_ready = time.time()
        with engine.create_conversation(
            sampler_config=litert_lm.SamplerConfig(**runtime_sampler)
        ) as conversation:
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
        "sampler_override": runtime_sampler,
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
    state_report = verify_archive_and_executor_metadata(
        bundle, int(cfg["cache_length"])
    )
    print(json.dumps(state_report, ensure_ascii=False, indent=2))

    print("=== Gate 4/5: LiteRT-LM 0.15 Engine + Conversation + generation smoke test ===")
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