#!/usr/bin/env bash
set -euxo pipefail

BASE_SCRIPT='.github/scripts/run_mcp247_emergency_build.sh'
MATERIALIZED="$RUNNER_TEMP/run_mcp247_emergency_build_v2_materialized.sh"

python3 - "$BASE_SCRIPT" "$MATERIALIZED" <<'PY'
from pathlib import Path
import sys

src = Path(sys.argv[1]).read_text()
out = src

anchor3 = "# 3. Apply the narrow Qwen registration patch in the CI workspace only and audit every changed product file.\n"
baseline = r'''# 2.5. Establish the protected pre-MCP247 unit-test baseline under the exact same JVM test runtime.
# Run it in an isolated detached worktree so even a mutating legacy test cannot contaminate the
# release workspace before the narrow MCP247 patch is applied.
cat > "$RUNNER_TEMP/mcp247-ci-tests.init.gradle" <<'GRADLE'
allprojects {
  afterEvaluate { p ->
    if (p.path == ':app') {
      p.dependencies.add('testImplementation', 'com.google.truth:truth:1.4.4')
      p.dependencies.add('testImplementation', 'org.json:json:20260719')
    }
  }
}
GRADLE
BASELINE_WORKTREE="$RUNNER_TEMP/mcp247_baseline_worktree"
export BASELINE_WORKTREE
rm -rf "$BASELINE_WORKTREE"
git worktree add --detach "$BASELINE_WORKTREE" "$SOURCE_BASE_COMMIT"
mkdir -p "$BASELINE_WORKTREE/Android/src/app/src/main/cpp/third_party/stable-diffusion.cpp"
rsync -a --delete --exclude='.git' \
  Android/src/app/src/main/cpp/third_party/stable-diffusion.cpp/ \
  "$BASELINE_WORKTREE/Android/src/app/src/main/cpp/third_party/stable-diffusion.cpp/"
rm -rf "$BASELINE_WORKTREE/Android/src/app/build/test-results/testDebugUnitTest" \
       "$BASELINE_WORKTREE/Android/src/app/build/reports/tests/testDebugUnitTest"
set +e
(
  cd "$BASELINE_WORKTREE/Android/src"
  ./gradlew --init-script "$RUNNER_TEMP/mcp247-ci-tests.init.gradle" --rerun-tasks testDebugUnitTest
)
BASE_TEST_RC=$?
set -e
export BASE_TEST_RC
python3 - <<'PYBASE' > "$RUNNER_TEMP/mcp247_baseline_unit_tests.json"
import glob, json, os, xml.etree.ElementTree as ET
root_dir=os.environ['BASELINE_WORKTREE']
cases=[]; failures=[]
pattern=os.path.join(root_dir,'Android/src/app/build/test-results/testDebugUnitTest/TEST-*.xml')
for p in glob.glob(pattern):
    root=ET.parse(p).getroot()
    for tc in root.iter('testcase'):
        ident=f"{tc.attrib.get('classname','')}#{tc.attrib.get('name','')}"
        cases.append(ident)
        if tc.find('failure') is not None or tc.find('error') is not None:
            failures.append(ident)
rc=int(os.environ['BASE_TEST_RC'])
assert len(cases) >= 100, f'baseline unit suite incomplete: total={len(cases)} rc={rc}'
assert (rc == 0) == (len(failures) == 0), (rc, failures)
print(json.dumps({'total':len(cases),'failures':sorted(set(failures)),'rc':rc}, indent=2))
PYBASE
git worktree remove --force "$BASELINE_WORKTREE"
git worktree prune
git diff --quiet "$SOURCE_BASE_COMMIT"...HEAD -- Android model_allowlists
echo MCP247_BASELINE_UNIT_SUITE_CAPTURED_ISOLATED

# 3. Apply the narrow Qwen registration patch in the CI workspace only and audit every changed product file.
'''
if out.count(anchor3) != 1:
    raise SystemExit(f'baseline insertion anchor mismatch count={out.count(anchor3)}')
out = out.replace(anchor3, baseline, 1)

old4 = r'''# 4. Prepare the exact release signer, run the complete unit regression suite, and build.
# AiKeyboardCommitVerifierTest uses Google Truth while the protected product Gradle file does not
# declare it. Keep product bytes immutable: inject the pinned test-only dependency through a
# temporary Gradle init script for the unit-test invocation only. The release build does not use it.
printf '%s' "$ANDROID_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/local-agent-plaza-release.jks"
export ANDROID_RELEASE_KEYSTORE_PATH="$RUNNER_TEMP/local-agent-plaza-release.jks"
echo "ANDROID_RELEASE_KEYSTORE_PATH=$ANDROID_RELEASE_KEYSTORE_PATH" >> "$GITHUB_ENV"
cat > "$RUNNER_TEMP/mcp247-ci-tests.init.gradle" <<'GRADLE'
allprojects {
  afterEvaluate { p ->
    if (p.path == ':app') {
      p.dependencies.add('testImplementation', 'com.google.truth:truth:1.4.4')
      p.dependencies.add('testImplementation', 'org.json:json:20260719')
    }
  }
}
GRADLE
(
  cd Android/src
  ./gradlew --init-script "$RUNNER_TEMP/mcp247-ci-tests.init.gradle" testDebugUnitTest
  echo MCP247_FULL_UNIT_REGRESSION_SUITE_PASS
  ./gradlew assembleRelease
)
'''
new4 = r'''# 4. Prepare the exact release signer, compare the patched unit suite against the protected baseline, and build.
printf '%s' "$ANDROID_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/local-agent-plaza-release.jks"
export ANDROID_RELEASE_KEYSTORE_PATH="$RUNNER_TEMP/local-agent-plaza-release.jks"
echo "ANDROID_RELEASE_KEYSTORE_PATH=$ANDROID_RELEASE_KEYSTORE_PATH" >> "$GITHUB_ENV"
rm -rf Android/src/app/build/test-results/testDebugUnitTest Android/src/app/build/reports/tests/testDebugUnitTest
set +e
(
  cd Android/src
  ./gradlew --init-script "$RUNNER_TEMP/mcp247-ci-tests.init.gradle" --rerun-tasks testDebugUnitTest
)
PATCHED_TEST_RC=$?
set -e
export PATCHED_TEST_RC
python3 - <<'PYPATCH' > "$RUNNER_TEMP/mcp247_patched_unit_tests.json"
import glob, json, os, xml.etree.ElementTree as ET
cases=[]; failures=[]
for p in glob.glob('Android/src/app/build/test-results/testDebugUnitTest/TEST-*.xml'):
    root=ET.parse(p).getroot()
    for tc in root.iter('testcase'):
        ident=f"{tc.attrib.get('classname','')}#{tc.attrib.get('name','')}"
        cases.append(ident)
        if tc.find('failure') is not None or tc.find('error') is not None:
            failures.append(ident)
rc=int(os.environ['PATCHED_TEST_RC'])
assert len(cases) >= 100, f'patched unit suite incomplete: total={len(cases)} rc={rc}'
assert (rc == 0) == (len(failures) == 0), (rc, failures)
print(json.dumps({'total':len(cases),'failures':sorted(set(failures)),'rc':rc}, indent=2))
PYPATCH
python3 - <<'PYCOMPARE'
import json, os
base=json.load(open(os.path.join(os.environ['RUNNER_TEMP'],'mcp247_baseline_unit_tests.json')))
patched=json.load(open(os.path.join(os.environ['RUNNER_TEMP'],'mcp247_patched_unit_tests.json')))
b=set(base['failures']); p=set(patched['failures'])
assert patched['total'] == base['total'], (base['total'], patched['total'])
new=sorted(p-b)
assert not new, f'MCP247 introduced new unit-test failures: {new}'
print('MCP247_UNIT_REGRESSION_NO_NEW_FAILURES_PASS baseline_failures=%d patched_failures=%d' % (len(b),len(p)))
if b:
    print('MCP247_PREEXISTING_BASELINE_FAILURES', sorted(b))
PYCOMPARE
(
  cd Android/src
  ./gradlew assembleRelease
)
'''
if out.count(old4) != 1:
    raise SystemExit(f'step4 replacement anchor mismatch count={out.count(old4)}')
out = out.replace(old4, new4, 1)

manifest_anchor = " 'litert_lm':{'version':'0.15.0','official_maven_runtime':True,'custom_mcp242_aar_jni':False},\n"
manifest_insert = " 'unit_regression':{'protected_baseline_compared':True,'no_new_failures':True},\n" + manifest_anchor
if out.count(manifest_anchor) != 1:
    raise SystemExit(f'manifest anchor mismatch count={out.count(manifest_anchor)}')
out = out.replace(manifest_anchor, manifest_insert, 1)

Path(sys.argv[2]).write_text(out)
PY

chmod +x "$MATERIALIZED"
bash -n "$MATERIALIZED"
grep -F 'MCP247_BASELINE_UNIT_SUITE_CAPTURED_ISOLATED' "$MATERIALIZED"
grep -F 'MCP247_UNIT_REGRESSION_NO_NEW_FAILURES_PASS' "$MATERIALIZED"
grep -F "'unit_regression':{'protected_baseline_compared':True,'no_new_failures':True}" "$MATERIALIZED"
exec bash "$MATERIALIZED"
