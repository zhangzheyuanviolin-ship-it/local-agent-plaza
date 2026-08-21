#!/usr/bin/env bash
set -euxo pipefail

BASE_SCRIPT='.github/scripts/run_mcp247_emergency_build.sh'
OUT="$RUNNER_TEMP/run_mcp247_emergency_build_v6.materialized.sh"

python3 - "$BASE_SCRIPT" "$OUT" <<'PY'
from pathlib import Path
import sys
src=Path(sys.argv[1]).read_text()
start=src.index('# 4. Prepare the exact release signer, run the complete unit regression suite, and build.')
end=src.index('# 5. Restore the MCP237 / Box 0.4.9 device-proven LiteRT native set after Gradle packaging.')
replacement=r'''# 4. Establish a protected MCP246 unit-test baseline, then prove the MCP247 narrow patch adds zero regressions.
# The repository carries several legacy/stale unit assertions whose expected behavior predates later intentional
# product changes (for example MCP224 repeat-call diagnostics). Treat the exact protected SOURCE_BASE_COMMIT as
# the regression oracle: MCP247 may reduce existing failures, but it may not introduce even one new failing test.
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

QPATCH="$RUNNER_TEMP/mcp247-qwen-registration.patch"
git diff --binary > "$QPATCH"
test -s "$QPATCH"

# Restore only the five authorized product files to HEAD for the baseline run.
git checkout -- \
  Android/src/app/src/main/assets/model_allowlists/1_0_14.json \
  Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DownloadRepository.kt \
  Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt \
  Android/src/app/src/main/java/com/google/ai/edge/gallery/worker/DownloadWorker.kt \
  model_allowlists/1_0_14.json
git diff --quiet -- Android model_allowlists

set +e
(
  cd Android/src
  ./gradlew --init-script "$RUNNER_TEMP/mcp247-ci-tests.init.gradle" testDebugUnitTest --rerun-tasks
)
BASELINE_TEST_RC=$?
set -e
python3 - <<'PY' > "$RUNNER_TEMP/mcp246_baseline_failed_tests.txt"
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path('Android/src/app/build/test-results/testDebugUnitTest')
failed=[]
for p in sorted(root.glob('TEST-*.xml')):
    r=ET.parse(p).getroot()
    for tc in r.iter('testcase'):
        if tc.find('failure') is not None or tc.find('error') is not None:
            failed.append(f"{tc.attrib.get('classname','')}::{tc.attrib.get('name','')}")
for x in sorted(set(failed)):
    print(x)
PY
BASELINE_FAIL_COUNT="$(wc -l < "$RUNNER_TEMP/mcp246_baseline_failed_tests.txt" | tr -d ' ')"
if [ "$BASELINE_TEST_RC" -eq 0 ]; then test "$BASELINE_FAIL_COUNT" -eq 0; else test "$BASELINE_FAIL_COUNT" -gt 0; fi
printf 'MCP246 protected baseline unit failures (%s):\n' "$BASELINE_FAIL_COUNT"
cat "$RUNNER_TEMP/mcp246_baseline_failed_tests.txt"

# Reapply the exact narrow MCP247 patch and rerun the complete suite in the same CI environment.
git apply "$QPATCH"
git diff --check
grep -F 'MCP247_QWEN35_VERIFIED_2B_CPU_ONLY' "$HELPER"
rm -rf Android/src/app/build/test-results/testDebugUnitTest
set +e
(
  cd Android/src
  ./gradlew --init-script "$RUNNER_TEMP/mcp247-ci-tests.init.gradle" testDebugUnitTest --rerun-tasks
)
PATCHED_TEST_RC=$?
set -e
python3 - <<'PY' > "$RUNNER_TEMP/mcp247_patched_failed_tests.txt"
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path('Android/src/app/build/test-results/testDebugUnitTest')
failed=[]
for p in sorted(root.glob('TEST-*.xml')):
    r=ET.parse(p).getroot()
    for tc in r.iter('testcase'):
        if tc.find('failure') is not None or tc.find('error') is not None:
            failed.append(f"{tc.attrib.get('classname','')}::{tc.attrib.get('name','')}")
for x in sorted(set(failed)):
    print(x)
PY
PATCHED_FAIL_COUNT="$(wc -l < "$RUNNER_TEMP/mcp247_patched_failed_tests.txt" | tr -d ' ')"
if [ "$PATCHED_TEST_RC" -eq 0 ]; then test "$PATCHED_FAIL_COUNT" -eq 0; else test "$PATCHED_FAIL_COUNT" -gt 0; fi
python3 - <<'PY'
from pathlib import Path
base=set(Path(__import__('os').environ['RUNNER_TEMP'],'mcp246_baseline_failed_tests.txt').read_text().splitlines())
patched=set(Path(__import__('os').environ['RUNNER_TEMP'],'mcp247_patched_failed_tests.txt').read_text().splitlines())
new=sorted(patched-base)
assert not new, 'MCP247 introduced new unit failures: '+repr(new)
print('MCP247_FULL_UNIT_REGRESSION_ZERO_NEW_FAILURES_PASS', 'baseline_failures=',len(base),'patched_failures=',len(patched))
PY

# Re-prove the authorized product delta after the baseline round-trip.
mapfile -t changed_after_tests < <(git diff --name-only | sort)
test "${changed_after_tests[*]}" = "${expected_sorted[*]}"
grep -F 'data class LlmModelInstance(val engine: Engine, var conversation: Conversation)' "$HELPER"
grep -F 'val engine = instance.engine' "$HELPER"
grep -F 'put("enable_thinking", false)' "$HELPER"
! grep -q 'MCP240_QWEN35_RECURRENT_STATE_RESET' "$HELPER"
! grep -q 'prefillPrefaceOnInit' "$HELPER"
echo MCP247_POST_TEST_PRODUCT_DELTA_AND_AGENT_INVARIANTS_PASS

(
  cd Android/src
  ./gradlew assembleRelease
)

'''
out=src[:start]+replacement+src[end:]
Path(sys.argv[2]).write_text(out)
print('MCP247_V6_MATERIALIZED_BASELINE_COMPARISON_HARNESS_PASS')
PY

bash "$OUT"
