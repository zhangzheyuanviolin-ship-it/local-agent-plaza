#!/usr/bin/env python3
"""MCP242: restore real Qwen3.5 soft repetition control on LiteRT-LM 0.15.

Two narrowly-scoped operations are supported:

1. --runtime-source <LiteRT-LM v0.15.0 checkout>
   Backport padded-model-vocabulary support into RepetitionPenaltyProcessor.
   v0.15 assumes model logits width == tokenizer vocabulary size. Qwen3.5's
   exported graph can have a padded logits vocabulary, so the processor aborts
   before token 1. The backport uses the actual model-vocab width as the batch
   stride while applying penalties only to valid tokenizer token IDs.

2. --app
   Replace the Maven LiteRT-LM AAR dependency with the locally supplied patched
   AAR. The MCP240 app patch remains responsible for enabling the official
   Qwen3.5 non-thinking presence/repetition penalty configuration.

No output truncation, repetition watchdog, or hard no-repeat ngram is added.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

SCRIPT_ROOT = Path(__file__).resolve().parents[1]  # Android/src


def fail(message: str) -> None:
    print(f"MCP242 patch failure: {message}", file=sys.stderr)
    raise SystemExit(1)


def patch_runtime(source: Path) -> None:
    source = source.resolve()
    version = source / ".bazelversion"
    if not version.exists() or version.read_text().strip() != "7.6.1":
        fail(f"unexpected LiteRT-LM source or Bazel version: {source}")

    path = source / "runtime/components/logits_processor/repetition_penalty_processor.cc"
    if not path.exists():
        fail(f"repetition penalty processor missing: {path}")
    text = path.read_text(encoding="utf-8")

    marker = "MCP242 backport: support padded model vocabulary"
    if marker in text:
        print(f"MCP242 runtime already patched: {path}")
        return

    old = '''  if (logits_dims.size() != 3 || logits_dims[0] != batch_states_.size() ||
      logits_dims[1] != 1 || logits_dims[2] != vocab_size_) {
    return absl::InvalidArgumentError(
        "Logits dimensions must be [batch_size, 1, vocab_size].");
  }

  if (logits.size() != vocab_size_ * batch_states_.size()) {
    return absl::InvalidArgumentError("Logits span size incorrectly mapped.");
  }

  for (int batch_idx = 0; batch_idx < batch_states_.size(); ++batch_idx) {
    BatchState& batch_state = batch_states_[batch_idx];
    int batch_offset = batch_idx * vocab_size_;

    for (int vocab_idx = 0; vocab_idx < vocab_size_; ++vocab_idx) {
'''
    new = '''  // MCP242 backport: support padded model vocabulary. Some LiteRT-LM
  // exports expose logits wider than the tokenizer vocabulary. v0.15 required
  // exact equality and aborted before the first token. Use the model's actual
  // logits width as the per-batch stride, while penalties remain limited to
  // valid tokenizer token IDs. Padded-tail logits are intentionally untouched.
  if (logits_dims.size() != 3 || logits_dims[0] != batch_states_.size() ||
      logits_dims[1] != 1 || logits_dims[2] < vocab_size_) {
    return absl::InvalidArgumentError(
        "Logits dimensions must be [batch_size, 1, model_vocab_size] with "
        "model_vocab_size >= tokenizer_vocab_size.");
  }

  const int model_vocab_size = logits_dims[2];
  if (logits.size() != model_vocab_size * batch_states_.size()) {
    return absl::InvalidArgumentError("Logits span size incorrectly mapped.");
  }

  for (int batch_idx = 0; batch_idx < batch_states_.size(); ++batch_idx) {
    BatchState& batch_state = batch_states_[batch_idx];
    int batch_offset = batch_idx * model_vocab_size;

    for (int vocab_idx = 0; vocab_idx < vocab_size_; ++vocab_idx) {
'''
    count = text.count(old)
    if count != 1:
        fail(f"expected one v0.15 processor anchor, found {count}")
    patched = text.replace(old, new, 1)

    required = (
        marker,
        "const int model_vocab_size = logits_dims[2];",
        "int batch_offset = batch_idx * model_vocab_size;",
        "for (int vocab_idx = 0; vocab_idx < vocab_size_; ++vocab_idx)",
    )
    for item in required:
        if item not in patched:
            fail(f"runtime postcondition missing: {item}")
    path.write_text(patched, encoding="utf-8")
    print(f"MCP242 runtime patched: {path}")


def patch_app() -> None:
    gradle = SCRIPT_ROOT / "app/build.gradle.kts"
    text = gradle.read_text(encoding="utf-8")
    marker = "// MCP242_LOCAL_LITERTLM_AAR"
    if marker not in text:
        old = "  implementation(libs.litertlm)\n"
        new = (
            "  // MCP242_LOCAL_LITERTLM_AAR: stable 0.15 Kotlin API with the arm64 "
            "padded-vocab penalty backport.\n"
            "  implementation(files(\"libs/litertlm-android-0.15.0-mcp242.aar\"))\n"
        )
        if text.count(old) != 1:
            fail(f"expected one LiteRT-LM Maven dependency anchor, found {text.count(old)}")
        gradle.write_text(text.replace(old, new, 1), encoding="utf-8")

    helper = SCRIPT_ROOT / "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt"
    h = helper.read_text(encoding="utf-8")
    required = (
        "RepetitionPenaltyConfig(",
        "presencePenalty = 2.0f",
        "repetitionPenalty = 1.0f",
        "MCP240_QWEN35_RECURRENT_STATE_RESET",
    )
    for item in required:
        if item not in h:
            fail(f"MCP240 generation-control postcondition missing: {item}")
    if "NoRepeatNgramConfig" in h:
        fail("MCP242 must not add hard no-repeat ngram")
    if "maxOutputToken =" in h:
        fail("MCP242 must not add per-call hard output truncation")

    g = gradle.read_text(encoding="utf-8")
    if marker not in g or "implementation(libs.litertlm)" in g:
        fail("local patched AAR dependency postcondition failed")
    print("MCP242 app patch complete: real soft penalty retained through local patched v0.15 AAR")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime-source", type=Path)
    parser.add_argument("--app", action="store_true")
    args = parser.parse_args()
    if bool(args.runtime_source) == bool(args.app):
        fail("choose exactly one of --runtime-source or --app")
    if args.runtime_source:
        patch_runtime(args.runtime_source)
    else:
        patch_app()


if __name__ == "__main__":
    main()
