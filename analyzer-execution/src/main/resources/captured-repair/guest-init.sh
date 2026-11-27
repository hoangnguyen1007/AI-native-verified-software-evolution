#!/bin/sh
set -eu
export PATH=/bin:/jdk/bin:/maven/bin:/gradle/bin JAVA_HOME=/jdk LC_ALL=C HOME=/work MAVEN_OPTS="-Xmx1536m" GRADLE_OPTS="-Dorg.gradle.daemon=false -Xmx1536m"
# Non-exported per-run control marker prefix injected by run.py (never exposed to target subprocess env).
_EVO_P="__EVO_CTRL_PREFIX__"
if [ "${_EVO_P}" = "__""EVO_CTRL_PREFIX__" ]; then
  _EVO_P=""
fi

emit() {
  printf '%s%s\n' "${_EVO_P}" "$1"
}

emit_b64() {
  TAG="$1"
  FILE="$2"
  emit "${TAG}_BEGIN"
  base64 "${FILE}" | sed "s/^/${_EVO_P}${TAG}:/"
  emit "${TAG}_END"
}

# Networkless initramfs guest: no host disks, mounts, or external NICs.
finish() { poweroff -f; }
trap finish EXIT

mount -t proc -o nosuid,nodev,noexec proc /proc
mount -t sysfs -o ro,nosuid,nodev,noexec sysfs /sys
mount -t devtmpfs devtmpfs /dev
mount -t tmpfs -o size=2048m,nosuid,nodev tmpfs /work
mount -t tmpfs -o size=512m,nosuid,nodev tmpfs /tmp

test "$(ls /sys/class/net)" = lo
test -z "$(ls /sys/block | grep -Ev '^(loop|ram)[0-9]+$')"

cd /inputs
sha256sum -cs manifest.sha256
. /inputs/build-spec.env

emit "EVO_VM_INPUTS $(sha256sum /inputs/manifest.sha256 | cut -d ' ' -f 1)"
emit "EVO_VM_ISOLATION network=loopback-only disks=none"
emit "EVO_BUILD_TOOL ${EVO_BUILD_TOOL}"

mkdir -p /work/repo
cp -a /inputs/candidate/. /work/repo/
if [ -d /inputs/probes ]; then cp -a /inputs/probes/. /work/repo/; fi
cd /work/repo

COMPILE_EXIT=1
TEST_EXIT=1
: > /tmp/compile.log
: > /tmp/test.log

if [ "${EVO_BUILD_TOOL}" = "gradle" ]; then
  GRADLE_ARGS="--offline --no-daemon -Dgradle.user.home=/inputs/gradle-home -Duser.home=/work"
  set +e
  gradle ${GRADLE_ARGS} testClasses > /tmp/compile.log 2>&1
  COMPILE_EXIT=$?
  set -e
  emit "EVO_COMPILE_EXIT ${COMPILE_EXIT}"

  if [ "${COMPILE_EXIT}" -eq 0 ]; then
    emit "EVO_CHECK candidate.compile"
    if [ "${EVO_SKIP_TESTS:-false}" = "true" ]; then
      TEST_EXIT=0
      emit "EVO_TESTS_SKIPPED true"
    else
      set +e
      gradle ${GRADLE_ARGS} test > /tmp/test.log 2>&1
      TEST_EXIT=$?
      set -e
    fi
  fi
else
  MVN_ARGS="-o -B -ntp -Dmaven.repo.local=/inputs/m2 -Duser.home=/work"
  if [ -n "${EVO_MVN_PL:-}" ]; then
    case "${EVO_MVN_PL}" in
      *[!A-Za-z0-9._/,-]*|*..*)
        emit "EVO_INVALID_MVN_PL"
        exit 1
        ;;
    esac
    MVN_ARGS="${MVN_ARGS} -pl ${EVO_MVN_PL} -am"
  fi

  set +e
  mvn ${MVN_ARGS} test-compile > /tmp/compile.log 2>&1
  COMPILE_EXIT=$?
  set -e
  emit "EVO_COMPILE_EXIT ${COMPILE_EXIT}"

  if [ "${COMPILE_EXIT}" -eq 0 ]; then
    emit "EVO_CHECK candidate.compile"
    if [ "${EVO_SKIP_TESTS:-false}" = "true" ]; then
      TEST_EXIT=0
      emit "EVO_TESTS_SKIPPED true"
    else
      set +e
      mvn ${MVN_ARGS} -DfailIfNoTests=false test > /tmp/test.log 2>&1
      TEST_EXIT=$?
      set -e
    fi
  fi
fi
emit "EVO_TEST_EXIT ${TEST_EXIT}"

java -cp /census ClassDependencyCensus /work/repo /tmp/census.json /tmp/census.env /tmp/failures.json /tmp/fail-ids.txt
. /tmp/census.env

emit "EVO_TESTS run=${TESTS_RUN} failed=${TESTS_FAILED} errors=${TESTS_ERRORS}"
emit "EVO_CLASS_COUNT ${CLASS_COUNT}"
emit "EVO_CLASS_CENSUS ${CLASS_CENSUS_SHA256}"
if [ "${EVO_SKIP_TESTS:-false}" != "true" ] && [ "${COMPILE_EXIT}" -eq 0 ] && [ "${TEST_EXIT}" -eq 0 ] && [ "${TESTS_FAILED}" -eq 0 ] && [ "${TESTS_ERRORS}" -eq 0 ]; then
  emit "EVO_CHECK candidate.test"
fi
if [ "${EVO_SKIP_TESTS:-false}" != "true" ] && [ "${EVO_BEHAVIORAL_DIFF:-false}" = "true" ] && [ -d /inputs/baseline ]; then
  mkdir -p /work/base-repo
  cp -a /inputs/baseline/. /work/base-repo/
  if [ -d /inputs/probes ]; then cp -a /inputs/probes/. /work/base-repo/; fi
  cd /work/base-repo
  BASE_TEST_EXIT=1
  : > /tmp/base-test.log
  if [ "${EVO_BUILD_TOOL}" = "gradle" ]; then
    set +e
    gradle --offline --no-daemon -Dgradle.user.home=/inputs/gradle-home -Duser.home=/work test > /tmp/base-test.log 2>&1
    BASE_TEST_EXIT=$?
    set -e
  else
    BASE_MVN_ARGS="-o -B -ntp -Dmaven.repo.local=/inputs/m2 -Duser.home=/work"
    if [ -n "${EVO_MVN_PL:-}" ]; then
      BASE_MVN_ARGS="${BASE_MVN_ARGS} -pl ${EVO_MVN_PL} -am"
    fi
    set +e
    mvn ${BASE_MVN_ARGS} -DfailIfNoTests=false test > /tmp/base-test.log 2>&1
    BASE_TEST_EXIT=$?
    set -e
  fi
  java -cp /census ClassDependencyCensus /work/base-repo /tmp/base-census.json /tmp/base-census.env /tmp/base-failures.json /tmp/base-fail-ids.txt || true
  BASE_RUN=0; BASE_FAILED=0; BASE_ERRORS=0
  if [ -f /tmp/base-census.env ]; then
    BASE_RUN=$(grep '^TESTS_RUN=' /tmp/base-census.env | cut -d= -f2 || echo 0)
    BASE_FAILED=$(grep '^TESTS_FAILED=' /tmp/base-census.env | cut -d= -f2 || echo 0)
    BASE_ERRORS=$(grep '^TESTS_ERRORS=' /tmp/base-census.env | cut -d= -f2 || echo 0)
  fi
  BASE_TOTAL_FAIL=$((BASE_FAILED + BASE_ERRORS))
  CAND_TOTAL_FAIL=$((TESTS_FAILED + TESTS_ERRORS))
  CAND_LOG_SRC="/tmp/test.log"
  if [ ! -s /tmp/test.log ] && [ -s /tmp/compile.log ]; then CAND_LOG_SRC="/tmp/compile.log"; fi
  OUT_SHA=$( { tail -c 2048 /tmp/base-test.log; echo "---"; tail -c 2048 "${CAND_LOG_SRC}"; } | sha256sum | cut -d' ' -f1 )
  emit "EVO_BEHAVIORAL_DIFF baseExit=${BASE_TEST_EXIT} candExit=${TEST_EXIT} baseRun=${BASE_RUN} candRun=${TESTS_RUN} baseFail=${BASE_TOTAL_FAIL} candFail=${CAND_TOTAL_FAIL} outSha=${OUT_SHA}"
  java -cp /census ClassDependencyCensus --behavioral \
    "${BASE_TEST_EXIT}" "${TEST_EXIT}" "${BASE_RUN}" "${BASE_TOTAL_FAIL}" "${TESTS_RUN}" "${CAND_TOTAL_FAIL}" \
    "${OUT_SHA}" /tmp/base-fail-ids.txt /tmp/fail-ids.txt /tmp/behavioral.json
  emit_b64 "BEHAVIORAL_B64" /tmp/behavioral.json
  IDS_DIFFER=0
  if [ -f /tmp/base-fail-ids.txt ] || [ -f /tmp/fail-ids.txt ]; then
    if ! cmp -s /tmp/base-fail-ids.txt /tmp/fail-ids.txt 2>/dev/null; then IDS_DIFFER=1; fi
  fi
  if [ "${BASE_TEST_EXIT}" -ne "${TEST_EXIT}" ] || [ "${BASE_TOTAL_FAIL}" -ne "${CAND_TOTAL_FAIL}" ] || [ "${TESTS_RUN}" -lt "${BASE_RUN}" ] || [ "${IDS_DIFFER}" -ne 0 ]; then
    { echo "=== BASELINE ==="; tail -c 1024 /tmp/base-test.log; echo ""; echo "=== CANDIDATE ==="; tail -c 1024 "${CAND_LOG_SRC}"; } | head -c 2048 > /tmp/behavioral-excerpt.txt 2>/dev/null || true
    if [ -s /tmp/behavioral-excerpt.txt ]; then
      emit_b64 "BEHAVIORAL_EXCERPT_B64" /tmp/behavioral-excerpt.txt
    fi
  fi
fi
if [ -s /tmp/failures.json ] && [ "$(cat /tmp/failures.json)" != "[]" ]; then
  emit_b64 "FAILURES_B64" /tmp/failures.json
fi
if [ "${COMPILE_EXIT}" -ne 0 ] || [ "${TEST_EXIT}" -ne 0 ] || [ "${TESTS_FAILED}" -ne 0 ] || [ "${TESTS_ERRORS}" -ne 0 ]; then
  { tail -c 4096 /tmp/compile.log; echo ""; tail -c 4096 /tmp/test.log; } > /tmp/logtail.txt 2>/dev/null || true
  if [ -s /tmp/logtail.txt ]; then
    emit_b64 "LOG_TAIL_B64" /tmp/logtail.txt
  fi
fi
emit_b64 "CENSUS_B64" /tmp/census.json
emit "EVO_VM_COMPLETE"
