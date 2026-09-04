#!/usr/bin/env python3
"""Convert an official Qwen3.5 checkpoint into a standard LiteRT-LM bundle.

This driver intentionally relies on Google's Qwen3.5 Full Model Reauthoring in
litert-torch. It also refuses to run if the installed environment lacks the
linear/recurrent-state executor metadata needed by Qwen3.5 hybrid layers.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import inspect
import json
import os
from pathlib import Path
import platform
import shutil
import sys
import time


def sha256_file(path: Path, chunk_size: int = 8 * 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def load_config(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as f:
        cfg = json.load(f)
    required = {
        "model_id",
        "quantization_recipe",
        "cache_length",
        "prefill_lengths",
        "output_filename",
    }
    missing = sorted(required - set(cfg))
    if missing:
        raise ValueError(f"Missing config keys: {missing}")
    return cfg


def environment_preflight(model_id: str, cache_length: int) -> dict:
    import torch
    import transformers
    import litert_torch
    from transformers import AutoConfig
    from litert_torch.generative.export_hf.model_ext import metadata_builder

    # Hard gate: the dedicated Qwen3.5 Full Model Reauthoring must exist.
    qwen_exportable = importlib.import_module(
        "litert_torch.generative.export_hf.model_ext.qwen3_5.exportable_module"
    )
    qwen_static = importlib.import_module(
        "litert_torch.generative.export_hf.model_ext.qwen3_5.modeling_qwen3_5_static"
    )

    if not hasattr(qwen_exportable, "LiteRTExportableModuleForQwen3_5Prefill"):
        raise RuntimeError("Installed litert-torch lacks Qwen3.5 prefill reauthoring")
    if not hasattr(qwen_exportable, "LiteRTExportableModuleForQwen3_5Generate"):
        raise RuntimeError("Installed litert-torch lacks Qwen3.5 decode reauthoring")
    if not hasattr(qwen_static, "Qwen3_5StaticGatedDeltaNet"):
        raise RuntimeError("Installed litert-torch lacks Qwen3.5 GatedDeltaNet reauthoring")

    executor_builder = getattr(metadata_builder, "build_executor_metadata", None)
    if executor_builder is None:
        raise RuntimeError(
            "litert-lm-builder executor metadata proto is unavailable; refusing to "
            "produce a Qwen3.5 bundle that could lose recurrent/KV state metadata"
        )
    executor_src = inspect.getsource(executor_builder)
    for token in ("TYPE_LINEAR_ATTENTION", "kv_cache_c_", "kv_cache_r_", "kv_cache_k_", "kv_cache_v_"):
        if token not in executor_src:
            raise RuntimeError(f"Executor metadata builder is missing required token: {token}")

    hf_cfg = AutoConfig.from_pretrained(model_id)
    model_type = getattr(hf_cfg, "model_type", "")
    text_cfg = getattr(hf_cfg, "text_config", hf_cfg)
    if model_type != "qwen3_5" and getattr(text_cfg, "model_type", "") != "qwen3_5":
        raise RuntimeError(
            f"Expected Qwen3.5 config, got model_type={model_type!r}, "
            f"text_model_type={getattr(text_cfg, 'model_type', None)!r}"
        )
    max_positions = int(getattr(text_cfg, "max_position_embeddings", 0) or 0)
    if max_positions and cache_length > max_positions:
        raise RuntimeError(
            f"Requested cache_length={cache_length} exceeds model max_position_embeddings={max_positions}"
        )
    layer_types = list(getattr(text_cfg, "layer_types", []) or [])
    if not layer_types or "linear_attention" not in layer_types or "full_attention" not in layer_types:
        raise RuntimeError(
            "Qwen3.5 hybrid layer_types were not found; refusing generic conversion"
        )

    return {
        "python": sys.version,
        "platform": platform.platform(),
        "torch": getattr(torch, "__version__", "unknown"),
        "transformers": getattr(transformers, "__version__", "unknown"),
        "litert_torch": getattr(litert_torch, "__version__", "unknown"),
        "model_type": model_type,
        "max_position_embeddings": max_positions,
        "num_hidden_layers": int(getattr(text_cfg, "num_hidden_layers", 0) or 0),
        "linear_attention_layers": sum(x == "linear_attention" for x in layer_types),
        "full_attention_layers": sum(x == "full_attention" for x in layer_types),
        "executor_metadata": "linear+recurrent+kv-state-aware",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument(
        "--lightweight",
        choices=("true", "false"),
        default="true",
        help="Use litert-torch experimental lightweight conversion first.",
    )
    args = parser.parse_args()

    cfg = load_config(args.config)
    out_dir = args.output_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    # Keep retries deterministic: a fallback conversion starts from a clean output directory.
    for item in list(out_dir.iterdir()):
        if item.is_dir():
            shutil.rmtree(item)
        else:
            item.unlink()

    model_id = str(cfg["model_id"])
    cache_length = int(cfg["cache_length"])
    preflight = environment_preflight(model_id, cache_length)
    print("=== Qwen3.5 conversion preflight ===")
    print(json.dumps(preflight, ensure_ascii=False, indent=2))

    from litert_torch.generative.export_hf import export as litert_export_module

    lightweight = args.lightweight == "true"
    start = time.time()
    litert_export_module.export(
        model=model_id,
        output_dir=str(out_dir),
        task="text_generation",
        keep_temporary_files=False,
        prefill_lengths=[int(x) for x in cfg["prefill_lengths"]],
        cache_length=cache_length,
        quantization_recipe=str(cfg["quantization_recipe"]),
        externalize_embedder=bool(cfg.get("externalize_embedder", True)),
        single_token_embedder=bool(cfg.get("single_token_embedder", True)),
        cache_implementation="LiteRTLMCache",
        k_ts_idx=2,
        v_ts_idx=3,
        use_jinja_template=bool(cfg.get("use_jinja_template", True)),
        bundle_litert_lm=True,
        export_vision_encoder=False,
        export_audio_encoder=False,
        experimental_lightweight_conversion=lightweight,
        sampler_top_k=int(cfg.get("sampler_top_k", 1)),
        sampler_top_p=float(cfg.get("sampler_top_p", 1.0)),
        sampler_temperature=float(cfg.get("sampler_temperature", 0.0)),
    )
    elapsed = time.time() - start

    generated = out_dir / "model.litertlm"
    if not generated.is_file():
        candidates = sorted(out_dir.glob("*.litertlm"))
        if len(candidates) != 1:
            raise RuntimeError(
                f"Expected exactly one LiteRT-LM bundle, found: {[p.name for p in candidates]}"
            )
        generated = candidates[0]

    final_path = out_dir / str(cfg["output_filename"])
    if generated != final_path:
        generated.replace(final_path)

    with final_path.open("rb") as f:
        magic = f.read(8)
    if magic != b"LITERTLM":
        raise RuntimeError(f"Invalid LiteRT-LM magic after conversion: {magic!r}")

    digest = sha256_file(final_path)
    sha_path = final_path.with_suffix(final_path.suffix + ".sha256")
    sha_path.write_text(f"{digest}  {final_path.name}\n", encoding="utf-8")

    manifest = {
        "schema": "qwen35-litert-conversion-result.v1",
        "source_config": cfg,
        "environment": preflight,
        "experimental_lightweight_conversion": lightweight,
        "elapsed_seconds": round(elapsed, 3),
        "output_file": final_path.name,
        "output_bytes": final_path.stat().st_size,
        "sha256": digest,
        "magic_ascii": magic.decode("ascii"),
        "github_sha": os.getenv("GITHUB_SHA", ""),
        "github_run_id": os.getenv("GITHUB_RUN_ID", ""),
    }
    (out_dir / "conversion_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    print("=== Conversion complete ===")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
