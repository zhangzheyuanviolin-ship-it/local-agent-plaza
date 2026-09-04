#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

BOX_REPO = "https://github.com/zhangzheyuanviolin-ship-it/box-local-music-android.git"
BOX_BRANCH = "golden/box-local-music-0.4.9-final-stable"
BOX_COMMIT = "30036c4c3db7da656672a9490f9f821105068daf"
PATCHES = [
    "patch_041.py",
    "patch_042.py",
    "patch_043.py",
    "patch_043_streaming_fix.py",
    "patch_044.py",
    "patch_045.py",
    "patch_045_fix.py",
    "patch_046.py",
    "patch_047.py",
    "patch_048.py",
    "patch_049.py",
]
TARGET_PACKAGE = "com.google.ai.edge.gallery.customtasks.musicgeneration.box049"
SOURCE_PACKAGE = "com.boxlocal.music"

root = Path(__file__).resolve().parents[1]
work = root / "build" / "box049-golden-source"
dest = (
    root
    / "build"
    / "generated"
    / "box049"
    / "java"
    / "com"
    / "google"
    / "ai"
    / "edge"
    / "gallery"
    / "customtasks"
    / "musicgeneration"
    / "box049"
)

if work.exists():
    shutil.rmtree(work)
if dest.exists():
    shutil.rmtree(dest)
dest.mkdir(parents=True, exist_ok=True)

env = dict(os.environ)
env["GIT_TERMINAL_PROMPT"] = "0"
subprocess.run(
    [
        "git",
        "clone",
        "--depth",
        "1",
        "--single-branch",
        "--branch",
        BOX_BRANCH,
        BOX_REPO,
        str(work),
    ],
    check=True,
    env=env,
)
actual = subprocess.check_output(
    ["git", "-C", str(work), "rev-parse", "HEAD"], text=True
).strip()
if actual != BOX_COMMIT:
    raise SystemExit(f"Box golden commit mismatch: {actual} != {BOX_COMMIT}")

for patch in PATCHES:
    patch_path = work / "scripts" / patch
    subprocess.run([sys.executable, str(patch_path)], cwd=work, check=True)

engine_path = work / "app/src/main/java/com/boxlocal/music/OfficialSoundGenEngine.java"
model_path = work / "app/src/main/java/com/boxlocal/music/ModelSpec.java"
file_path = work / "app/src/main/java/com/boxlocal/music/ModelFileSpec.java"
engine = engine_path.read_text(encoding="utf-8")

required = [
    "enum AcceleratorMode",
    "static String accelerationReport()",
    "new String[][] {",
    '{"AUTOMATIC", "DEFAULT"}',
    '{"OPENCL", "FP16"}',
    '{"OPENCL", "FP32"}',
    '{"OPENGL", "FP16"}',
    '{"OPENGL", "FP32"}',
    "decoder=CPU（Long 稳定策略）",
    "method.invoke(model, inputs, outputs, 0)",
    "writeStereoWavStreaming",
]
missing = [token for token in required if token not in engine]
if missing:
    raise SystemExit("Golden Box runtime replay audit failed; missing: " + repr(missing))

def rewrite_package(text: str) -> str:
    needle = f"package {SOURCE_PACKAGE};"
    if needle not in text:
        raise SystemExit(f"Missing source package declaration {needle}")
    return text.replace(needle, f"package {TARGET_PACKAGE};", 1)

for src, name in [
    (engine_path, "OfficialSoundGenEngine.java"),
    (model_path, "ModelSpec.java"),
    (file_path, "ModelFileSpec.java"),
]:
    dest.joinpath(name).write_text(
        rewrite_package(src.read_text(encoding="utf-8")), encoding="utf-8"
    )

bridge = f'''package {TARGET_PACKAGE};

import android.content.Context;
import java.io.File;
import java.util.function.Consumer;

public final class Box049Bridge {{
  public static final String GOLDEN_COMMIT = "{BOX_COMMIT}";

  private Box049Bridge() {{}}

  private static ModelSpec findModel(String modelId) {{
    for (ModelSpec model : ModelSpec.officialBoxModels()) {{
      if (model.id.equals(modelId)) return model;
    }}
    throw new IllegalArgumentException("Unknown Box 0.4.9 model id: " + modelId);
  }}

  public static void verifyFiles(String modelId, File modelDir) {{
    ModelSpec model = findModel(modelId);
    for (ModelFileSpec spec : model.files) {{
      File file = spec.localFile(modelDir);
      long actual = file.isFile() ? file.length() : -1L;
      if (actual != spec.sizeBytes) {{
        throw new IllegalStateException(
            "Box 0.4.9 model file integrity failure: "
                + spec.outputName
                + " actual="
                + actual
                + " expected="
                + spec.sizeBytes
                + ". Delete/re-download this model.");
      }}
    }}
  }}

  public static String generate(
      Context context,
      String modelId,
      File modelDir,
      String prompt,
      float durationSeconds,
      String accelerationMode,
      Consumer<Float> progress)
      throws Exception {{
    verifyFiles(modelId, modelDir);
    ModelSpec model = findModel(modelId);
    OfficialSoundGenEngine.AcceleratorMode mode =
        OfficialSoundGenEngine.AcceleratorMode.valueOf(accelerationMode);
    return OfficialSoundGenEngine.generate(
        context, model, modelDir, prompt, durationSeconds, mode, progress);
  }}

  public static String accelerationReport() {{
    return OfficialSoundGenEngine.accelerationReport();
  }}
}}
'''
dest.joinpath("Box049Bridge.java").write_text(bridge, encoding="utf-8")

# Cross-audit the Plaza integration against the generated golden runtime before Gradle compiles.
task_source = (
    root
    / "app/src/main/java/com/google/ai/edge/gallery/customtasks/musicgeneration/MusicGenerationTask.kt"
).read_text(encoding="utf-8")
models_source = (
    root
    / "app/src/main/java/com/google/ai/edge/gallery/customtasks/musicgeneration/MusicGenerationModels.kt"
).read_text(encoding="utf-8")
if "GoldenBox049RuntimeEngine(context = context, model = model)" not in task_source:
    raise SystemExit("Plaza music task is not routed to GoldenBox049RuntimeEngine")
if 'SOUNDGEN_VERSION = "box-0.4.9-golden-runtime-r1"' not in models_source:
    raise SystemExit("Plaza music cache version was not invalidated for the golden runtime")

golden_model_source = model_path.read_text(encoding="utf-8")
download_contract_tokens = [
    "dit_model.tflite", "344293232L",
    "conditioners_float32.tflite", "440190572L",
    "autoencoder_model.tflite", "312588244L",
    "spiece.model", "791656L",
    "dit_L256_int8.tflite", "1468553968L",
    "ae_dec_L256_int8.tflite", "434121120L",
    "dit_L2048_int8.tflite", "1469012720L",
    "ae_dec_L2048_int8.tflite", "447063056L",
    "t5gemma_enc_int8.tflite", "286972704L",
    "tokenizer.model", "4241003L",
]
for token in download_contract_tokens:
    if token not in golden_model_source:
        raise SystemExit("Golden ModelSpec unexpectedly changed: missing " + token)
    normalized = token
    if token.endswith("L") and token[:-1].isdigit():
        normalized = f"{int(token[:-1]):_}L"
    if normalized not in models_source:
        raise SystemExit("Plaza download contract differs from golden ModelSpec: missing " + normalized)

digest = hashlib.sha256(engine.encode("utf-8")).hexdigest()
print(f"BOX049_GOLDEN_COMMIT={BOX_COMMIT}")
print(f"BOX049_EFFECTIVE_ENGINE_SHA256={digest}")
print(f"BOX049_GENERATED_SOURCE={dest}")
