# Qwen3.5 LiteRT-LM conversion pipeline

This directory contains the reproducible conversion and validation path used by the experimental branch to build Qwen3.5 text-only LiteRT-LM bundles for Local Agent Plaza.

## First golden target

- Source: `Qwen/Qwen3.5-2B`
- Text-only
- Weight quantization target: 8-bit (`dynamic_wi8_afp32`, labeled Q8 in project artifacts)
- Static cache/context: 4096 tokens
- Prefill signatures: 32, 64, 128, 256, 512, 1024
- Cache implementation: `LiteRTLMCache`
- External single-token embedder
- Standard LiteRT-LM container magic: `LITERTLM`
- Compatibility smoke target: LiteRT-LM 0.15-era runtime

## Hard validation gates

A model artifact is uploaded only after all of these pass:

1. Google's dedicated Qwen3.5 Full Model Reauthoring is present.
2. The conversion environment exposes hybrid executor metadata for linear/recurrent states and full-attention KV states.
3. The generated container begins with the exact eight-byte `LITERTLM` magic.
4. The packaged executor metadata contains `kv_cache_c_*`, `kv_cache_r_*`, `kv_cache_k_*`, and `kv_cache_v_*` state buffers.
5. A LiteRT-LM 0.15-era `Engine` can open the bundle, create a conversation, and generate non-empty text.

This specifically guards against the two previously observed third-party failures: legacy/nonstandard `LTLM` packaging and bundles whose graph/state metadata reaches `No KV cache inputs found` during engine creation.

## Extension path

Once the 2B Q8 / 4096 build is verified on-device, the same config-driven pipeline can be extended to 8192/16384 context and then to Qwen3.5-4B and Qwen3.5-9B without changing the Android application build pipeline.
