#!/usr/bin/env bash
set -euxo pipefail

: "${SOURCE_BASE_COMMIT:?}"
: "${MODEL_RELEASE_TAG:?}"
: "${MODEL_FILE:?}"
: "${MODEL_NAME:?}"
: "${MODEL_SIZE:?}"
: "${MODEL_SHA256:?}"
: "${LOCAL_VERSION_NAME:?}"
: "${LOCAL_VERSION_CODE:?}"
: "${APK_RELEASE_TAG:?}"
: "${APK_NAME:?}"
: "${ANDROID_RELEASE_KEYSTORE_BASE64:?}"
: "${ANDROID_RELEASE_KEYSTORE_PASSWORD:?}"
: "${ANDROID_RELEASE_KEY_ALIAS:?}"
: "${ANDROID_RELEASE_KEY_PASSWORD:?}"

publish_json_to_experimental() {
  local src="$1" path="$2" message="$3"
  local content api response sha
  content="$(base64 -w0 "$src")"
  api="repos/$GITHUB_REPOSITORY/contents/$path"
  response="$(gh api "$api?ref=experimental" 2>/dev/null || true)"
  sha="$(printf '%s' "$response" | python -c 'import json,sys; d=json.load(sys.stdin); print(d.get("sha", ""))' 2>/dev/null || true)"
  if [ -n "$sha" ]; then
    gh api --method PUT "$api" -f message="$message" -f branch=experimental -f sha="$sha" -f content="$content"
  else
    gh api --method PUT "$api" -f message="$message" -f branch=experimental -f content="$content"
  fi
}

# Expose the exact run before long validation starts.
python - <<'PY' > "$RUNNER_TEMP/mcp247_run.json"
import json, os
print(json.dumps({
  'schema':'local-agent-plaza.mcp247-emergency-run.v2',
  'run_id':int(os.environ['GITHUB_RUN_ID']),
  'run_number':int(os.environ['GITHUB_RUN_NUMBER']),
  'head_sha':os.environ['GITHUB_SHA'],
  'html_url':f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
}, indent=2))
PY
publish_json_to_experimental "$RUNNER_TEMP/mcp247_run.json" docs/mcp247_emergency_run.json 'Update MCP247 emergency run marker'

# 1. Protect the complete pre-MCP247 Android/product source tree.
git cat-file -e "$SOURCE_BASE_COMMIT^{commit}"
git diff --quiet "$SOURCE_BASE_COMMIT"...HEAD -- Android model_allowlists
grep -F 'implementation(libs.litertlm)' Android/src/app/build.gradle.kts
grep -F 'litertlm = "0.15.0"' Android/src/gradle/libs.versions.toml
grep -F 'litert = "2.1.6"' Android/src/gradle/libs.versions.toml
! grep -q 'litertlm-android-0.15.0-mcp242.aar' Android/src/app/build.gradle.kts
! grep -q 'MCP242_LOCAL_LITERTLM_AAR' Android/src/app/build.gradle.kts
HELPER='Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt'
grep -F 'data class LlmModelInstance(val engine: Engine, var conversation: Conversation)' "$HELPER"
grep -F 'val engine = instance.engine' "$HELPER"
grep -F 'COMPAT_FRESH_REASON_TOP_LEVEL' "$HELPER"
grep -F 'COMPAT_FRESH_REASON_TOOL_CONTINUATION' "$HELPER"
grep -F 'put("enable_thinking", false)' "$HELPER"
grep -F 'put("thinking_token_budget", 0)' "$HELPER"
! grep -q 'MCP240_QWEN35_RECURRENT_STATE_RESET' "$HELPER"
! grep -q 'MCP241_QWEN35_AGENT_LOGITS_FIX' "$HELPER"
! grep -q 'prefillPrefaceOnInit' "$HELPER"
! grep -q 'instance.engineConfig' "$HELPER"
test -f Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/CompatSearchRequiredPolicy.kt
test -f Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/CompatToolCallWireAdapter.kt
test -f Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentCompatRuntimeCoordinator.kt
git grep -q 'SEARCH_REQUIRED=true' -- Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat
test -f Android/src/scripts/patch_box_music_apk.py
test -f Android/src/scripts/audit_box_music_apk.py
test -f Android/src/scripts/prepare_box049_runtime.py
git grep -q 'GoldenBox049RuntimeEngine' -- Android/src/app/src/main/java
git grep -q 'Bonsai' -- Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation
git grep -q 'FLUX' -- Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation
git grep -q 'Z-Image' -- Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/visualcreation
echo MCP247_PROTECTED_PRODUCT_BASELINE_PASS

# 2. Independently reconstruct the published, previously verified Qwen3.5 bundle.
QWORK="$RUNNER_TEMP/qwen35_verify"
mkdir -p "$QWORK/parts"
gh release view "$MODEL_RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --json assets > "$QWORK/release.json"
python - <<'PY'
import json, os
p=os.path.join(os.environ['RUNNER_TEMP'],'qwen35_verify','release.json')
d=json.load(open(p))
assets={a['name']:int(a['size']) for a in d['assets']}
f=os.environ['MODEL_FILE']
sizes=[480_000_000]*9+[460_966_112]
for i,size in enumerate(sizes):
    name=f'{f}.part{i:02d}'
    assert assets.get(name)==size,(name,assets.get(name),size)
parts=sorted(name for name in assets if name.startswith(f+'.part'))
assert len(parts)==10,parts
print('QWEN35_EXACT_TEN_PART_INVENTORY_PASS', parts)
PY
for n in $(seq 0 9); do
  i="$(printf '%02d' "$n")"
  gh release download "$MODEL_RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --pattern "$MODEL_FILE.part$i" --dir "$QWORK/parts"
done
cat $(find "$QWORK/parts" -maxdepth 1 -type f -name "$MODEL_FILE.part*" | sort) > "$QWORK/full.litertlm"
test "$(head -c 8 "$QWORK/full.litertlm")" = LITERTLM
test "$(stat -c '%s' "$QWORK/full.litertlm")" = "$MODEL_SIZE"
test "$(sha256sum "$QWORK/full.litertlm" | awk '{print $1}')" = "$MODEL_SHA256"
rm -rf "$QWORK"
echo QWEN35_FULL_RELEASE_RECONSTRUCTION_SHA_PASS

# 3. Apply the narrow Qwen registration patch in the CI workspace only and audit every changed product file.
python3 -m py_compile .github/scripts/patch_mcp247_qwen35_verified_2b_32k.py
python3 .github/scripts/patch_mcp247_qwen35_verified_2b_32k.py
git diff --check
mapfile -t changed < <(git diff --name-only | sort)
expected=(
  'Android/src/app/src/main/assets/model_allowlists/1_0_14.json'
  'Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DownloadRepository.kt'
  'Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt'
  'Android/src/app/src/main/java/com/google/ai/edge/gallery/worker/DownloadWorker.kt'
  'model_allowlists/1_0_14.json'
)
mapfile -t expected_sorted < <(printf '%s\n' "${expected[@]}" | sort)
printf 'MCP247 workspace delta:\n%s\n' "${changed[*]}"
test "${changed[*]}" = "${expected_sorted[*]}"
grep -F 'MCP247_QWEN35_VERIFIED_2B_CPU_ONLY' "$HELPER"
grep -F 'data class LlmModelInstance(val engine: Engine, var conversation: Conversation)' "$HELPER"
grep -F 'val engine = instance.engine' "$HELPER"
grep -F 'put("enable_thinking", false)' "$HELPER"
! grep -q 'MCP240_QWEN35_RECURRENT_STATE_RESET' "$HELPER"
! grep -q 'prefillPrefaceOnInit' "$HELPER"
echo MCP247_NARROW_PATCH_AND_AGENT_INVARIANTS_PASS

# 4. Prepare the exact release signer, run regression tests, and build.
printf '%s' "$ANDROID_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/local-agent-plaza-release.jks"
export ANDROID_RELEASE_KEYSTORE_PATH="$RUNNER_TEMP/local-agent-plaza-release.jks"
echo "ANDROID_RELEASE_KEYSTORE_PATH=$ANDROID_RELEASE_KEYSTORE_PATH" >> "$GITHUB_ENV"
(
  cd Android/src
  ./gradlew testDebugUnitTest
  ./gradlew assembleRelease
)

# 5. Restore the MCP237 / Box 0.4.9 device-proven LiteRT native set after Gradle packaging.
cd Android/src
APK='app/build/outputs/apk/release/app-release.apk'
PATCHED="$RUNNER_TEMP/mcp247-golden-runtime-unsigned.apk"
ALIGNED="$RUNNER_TEMP/mcp247-golden-runtime-aligned.apk"
SIGNED="$RUNNER_TEMP/mcp247-golden-runtime-signed.apk"
BUILD_TOOLS="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -n 1)"
python3 scripts/patch_box_music_apk.py "$APK" "$PATCHED"
"$BUILD_TOOLS/zipalign" -f 4 "$PATCHED" "$ALIGNED"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$ANDROID_RELEASE_KEYSTORE_PATH" \
  --ks-key-alias "$ANDROID_RELEASE_KEY_ALIAS" \
  --ks-pass env:ANDROID_RELEASE_KEYSTORE_PASSWORD \
  --key-pass env:ANDROID_RELEASE_KEY_PASSWORD \
  --out "$SIGNED" "$ALIGNED"
mv "$SIGNED" "$APK"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$APK" | tee "$RUNNER_TEMP/apksigner.txt"
python3 scripts/audit_box_music_apk.py "$APK"
echo MCP247_CANONICAL_BOX_NATIVE_AUDIT_PASS

# 6. A second independent APK audit verifies exact native bytes, ABI symbols, module fingerprints, and embedded Qwen metadata.
AUDIT="$RUNNER_TEMP/final_apk_audit"
mkdir -p "$AUDIT"
python - <<'PY'
import hashlib,json,os,re,zipfile
apk='app/build/outputs/apk/release/app-release.apk'
out=os.path.join(os.environ['RUNNER_TEMP'],'final_apk_audit')
expected={
 'libLiteRt.so':'da27c0d6e59460248b1032610e924bc0f518a1229d2e3b081e0a229be51ab1c8',
 'libLiteRtClGlAccelerator.so':'4b19f18f4ba9b1bde6060def4388b74d07f939db798c8c77c4f4e5125aeabcb1',
 'liblitert_jni.so':'a98ea95cbb5ac4581bf483fa90df66454e15b965f4fa94a1b169a60319f4dc9a'}
with zipfile.ZipFile(apk) as z:
    names=set(z.namelist())
    for lib,sha in expected.items():
        entry=f'lib/arm64-v8a/{lib}'
        assert entry in names,entry
        data=z.read(entry)
        actual=hashlib.sha256(data).hexdigest()
        assert actual==sha,(lib,actual,sha)
        open(os.path.join(out,lib),'wb').write(data)
    lm='lib/arm64-v8a/liblitertlm_jni.so'
    assert lm in names,lm
    open(os.path.join(out,'liblitertlm_jni.so'),'wb').write(z.read(lm))
    allow='assets/model_allowlists/1_0_14.json'
    assert allow in names,allow
    d=json.loads(z.read(allow))
    matches=[m for m in d['models'] if m.get('name')==os.environ['MODEL_NAME']]
    assert len(matches)==1,len(matches)
    m=matches[0]; cfg=m['defaultConfig']
    assert m['modelFile']==os.environ['MODEL_FILE']
    assert m['commitHash']==os.environ['MODEL_RELEASE_TAG']
    assert int(m['sizeInBytes'])==int(os.environ['MODEL_SIZE'])
    assert cfg['accelerators']=='cpu'
    assert int(cfg['maxContextLength'])==32768
    assert int(cfg['maxTokens'])==4096
    dex=b''.join(z.read(n) for n in sorted(names) if re.fullmatch(r'classes\d*\.dex',n))
    for marker in (b'MCP247_QWEN35_VERIFIED_2B_CPU_ONLY', os.environ['MODEL_RELEASE_TAG'].encode(),
                   os.environ['MODEL_SHA256'].encode(), b'GoldenBox049RuntimeEngine',
                   b'Bonsai', b'FLUX', b'Z-Image', b'SEARCH_REQUIRED=true'):
        assert marker in dex,marker
print('MCP247_INDEPENDENT_ZIP_DEX_MODEL_NATIVE_HASH_AUDIT_PASS')
PY
readelf -Ws "$AUDIT/libLiteRt.so" | grep -F LiteRtCreateModelFromFd
readelf -Ws "$AUDIT/libLiteRt.so" | grep -F LiteRtGetBlockWiseQuantization
! readelf -d "$AUDIT/liblitertlm_jni.so" | grep -F libLiteRt.so
echo MCP247_CRITICAL_SYMBOL_AND_LITERTLM_ISOLATION_PASS

# 7. Package identity and version code prove it can cover-update MCP246 (code 346).
"$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | tee "$RUNNER_TEMP/final_certs.txt"
BADGING="$("$BUILD_TOOLS/aapt" dump badging "$APK" | head -n 1)"
echo "$BADGING"
echo "$BADGING" | grep "name='com.localagent.plaza.mcp'"
echo "$BADGING" | grep "versionCode='347'"
echo "$BADGING" | grep "versionName='1.0.14-mcp.247'"
CERT_SHA="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$RUNNER_TEMP/final_certs.txt" | head -n1 | tr -d '\r')"
test -n "$CERT_SHA"
echo MCP247_PACKAGE_SIGNATURE_UPGRADE_IDENTITY_PASS

# 8. Publish permanent GitHub Release and then independently download exactly what the user will receive.
cp "$APK" "$APK_NAME"
APK_SHA="$(sha256sum "$APK_NAME" | awk '{print $1}')"
APK_SIZE="$(stat -c '%s' "$APK_NAME")"
printf '%s  %s\n' "$APK_SHA" "$APK_NAME" > "$APK_NAME.sha256"
gh release view "$APK_RELEASE_TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1 || \
  gh release create "$APK_RELEASE_TAG" --repo "$GITHUB_REPOSITORY" \
    --title 'Local Agent Plaza MCP247 emergency media runtime restore' \
    --notes 'Layered emergency restore over MCP246. Preserves stable Agent/search/tool/diagnostic behavior; restores the MCP237/Box 0.4.9 device-proven LiteRT native set; excludes MCP242 custom runtime and MCP246 Qwen Engine reset; registers the independently verified Qwen3.5-2B Q8 32768 bundle on CPU.' \
    --prerelease
gh release upload "$APK_RELEASE_TAG" "$APK_NAME" "$APK_NAME.sha256" --repo "$GITHUB_REPOSITORY" --clobber
VERIFY="$RUNNER_TEMP/release_verify"
mkdir -p "$VERIFY"
gh release download "$APK_RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --pattern "$APK_NAME" --dir "$VERIFY"
test "$(sha256sum "$VERIFY/$APK_NAME" | awk '{print $1}')" = "$APK_SHA"
test "$(stat -c '%s' "$VERIFY/$APK_NAME")" = "$APK_SIZE"
"$BUILD_TOOLS/apksigner" verify "$VERIFY/$APK_NAME"
RBADGING="$("$BUILD_TOOLS/aapt" dump badging "$VERIFY/$APK_NAME" | head -n 1)"
echo "$RBADGING" | grep "name='com.localagent.plaza.mcp'"
echo "$RBADGING" | grep "versionCode='347'"
echo MCP247_RELEASE_REDOWNLOAD_EXACT_BYTES_PASS

# 9. Persist machine-readable acceptance only after every prior gate has passed.
export APK_SHA APK_SIZE CERT_SHA
python - <<'PY' > "$RUNNER_TEMP/mcp247_result.json"
import json,os
print(json.dumps({
 'schema':'local-agent-plaza.mcp247-emergency-media-runtime-restore.v2',
 'status':'FULLY_VERIFIED_PASS',
 'run_id':int(os.environ['GITHUB_RUN_ID']), 'head_sha':os.environ['GITHUB_SHA'],
 'version_name':os.environ['LOCAL_VERSION_NAME'], 'version_code':int(os.environ['LOCAL_VERSION_CODE']),
 'package':'com.localagent.plaza.mcp', 'upgrade_target':'MCP246 versionCode 346',
 'apk_name':os.environ['APK_NAME'], 'apk_sha256':os.environ['APK_SHA'], 'apk_size':int(os.environ['APK_SIZE']),
 'certificate_sha256':os.environ['CERT_SHA'], 'release_tag':os.environ['APK_RELEASE_TAG'],
 'release_url':f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/releases/download/{os.environ['APK_RELEASE_TAG']}/{os.environ['APK_NAME']}",
 'agent_runtime':{'persistent_engine':True,'fresh_top_level_conversation':True,'fresh_tool_continuation_conversation':True,
                  'compat_hard_thinking_off':True,'search_required_policy_preserved':True,
                  'mcp212_warm_prefill_excluded':True,'mcp246_qwen_engine_reset_excluded':True},
 'litert_lm':{'version':'0.15.0','official_maven_runtime':True,'custom_mcp242_aar_jni':False},
 'qwen35_2b':{'release_tag':os.environ['MODEL_RELEASE_TAG'],'file':os.environ['MODEL_FILE'],
              'size':int(os.environ['MODEL_SIZE']),'sha256':os.environ['MODEL_SHA256'],'context':32768,
              'backend':'CPU_FORCED','multipart_release_reconstructed_and_verified':True},
 'media_native':{'libLiteRt.so':'da27c0d6e59460248b1032610e924bc0f518a1229d2e3b081e0a229be51ab1c8',
                 'libLiteRtClGlAccelerator.so':'4b19f18f4ba9b1bde6060def4388b74d07f939db798c8c77c4f4e5125aeabcb1',
                 'liblitert_jni.so':'a98ea95cbb5ac4581bf483fa90df66454e15b965f4fa94a1b169a60319f4dc9a',
                 'LiteRtCreateModelFromFd_present':True,'LiteRtGetBlockWiseQuantization_present':True,
                 'litertlm_jni_direct_core_link':False,'canonical_box_apk_audit_passed':True},
 'modules':{'box_music':True,'bonsai_image':True,'flux_image':True,'z_image':True},
 'release_redownload_exact_sha_verified':True
},indent=2))
PY
publish_json_to_experimental "$RUNNER_TEMP/mcp247_result.json" docs/mcp247_emergency_media_restore_result.json 'Update MCP247 emergency restore acceptance'
echo MCP247_FULLY_VERIFIED_PASS
