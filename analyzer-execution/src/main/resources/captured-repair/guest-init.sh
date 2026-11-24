#!/bin/sh
set -eu
export PATH=/bin:/jdk/bin:/maven/bin:/gradle/bin JAVA_HOME=/jdk LC_ALL=C HOME=/work MAVEN_OPTS="-Xmx512m" GRADLE_OPTS="-Dorg.gradle.daemon=false -Xmx512m"

# Networkless initramfs guest: no host disks, mounts, or external NICs.
finish() { poweroff -f; }
trap finish EXIT

mount -t proc -o nosuid,nodev,noexec proc /proc
mount -t sysfs -o ro,nosuid,nodev,noexec sysfs /sys
mount -t devtmpfs devtmpfs /dev
mount -t tmpfs -o size=1024m,nosuid,nodev tmpfs /work
mount -t tmpfs -o size=256m,nosuid,nodev tmpfs /tmp

test "$(ls /sys/class/net)" = lo
test -z "$(ls /sys/block | grep -Ev '^(loop|ram)[0-9]+$')"

cd /inputs
sha256sum -cs manifest.sha256
. /inputs/build-spec.env

echo "EVO_VM_INPUTS $(sha256sum /inputs/manifest.sha256 | cut -d ' ' -f 1)"
echo "EVO_VM_ISOLATION network=loopback-only disks=none"
echo "EVO_BUILD_TOOL ${EVO_BUILD_TOOL}"

mkdir -p /work/repo
cp -a /inputs/candidate/. /work/repo/
cd /work/repo

COMPILE_EXIT=1
TEST_EXIT=1
if [ "${EVO_BUILD_TOOL}" = "gradle" ]; then
  GRADLE_ARGS="--offline --no-daemon -Dgradle.user.home=/inputs/gradle-home -Duser.home=/work"
  set +e
  gradle ${GRADLE_ARGS} testClasses > /tmp/compile.log 2>&1
  COMPILE_EXIT=$?
  set -e
  tail -n 120 /tmp/compile.log || true
  echo "EVO_COMPILE_EXIT ${COMPILE_EXIT}"

  if [ "${COMPILE_EXIT}" -eq 0 ]; then
    echo "EVO_CHECK candidate.compile"
    if [ "${EVO_SKIP_TESTS:-false}" = "true" ]; then
      TEST_EXIT=0
      echo "EVO_TESTS_SKIPPED true"
    else
      set +e
      gradle ${GRADLE_ARGS} test > /tmp/test.log 2>&1
      TEST_EXIT=$?
      set -e
      tail -n 120 /tmp/test.log || true
    fi
  fi
else
  MVN_ARGS="-o -B -ntp -Dmaven.repo.local=/inputs/m2 -Duser.home=/work"
  if [ -n "${EVO_MVN_PL:-}" ]; then
    case "${EVO_MVN_PL}" in
      *[!A-Za-z0-9._/,-]*|*..*)
        echo "EVO_INVALID_MVN_PL"
        exit 1
        ;;
    esac
    MVN_ARGS="${MVN_ARGS} -pl ${EVO_MVN_PL} -am"
  fi

  set +e
  mvn ${MVN_ARGS} test-compile > /tmp/compile.log 2>&1
  COMPILE_EXIT=$?
  set -e
  tail -n 120 /tmp/compile.log || true
  echo "EVO_COMPILE_EXIT ${COMPILE_EXIT}"

  if [ "${COMPILE_EXIT}" -eq 0 ]; then
    echo "EVO_CHECK candidate.compile"
    if [ "${EVO_SKIP_TESTS:-false}" = "true" ]; then
      TEST_EXIT=0
      echo "EVO_TESTS_SKIPPED true"
    else
      set +e
      mvn ${MVN_ARGS} -DfailIfNoTests=false test > /tmp/test.log 2>&1
      TEST_EXIT=$?
      set -e
      tail -n 120 /tmp/test.log || true
    fi
  fi
fi
echo "EVO_TEST_EXIT ${TEST_EXIT}"

java -cp /census ClassDependencyCensus /work/repo /tmp/census.json /tmp/census.env
. /tmp/census.env

echo "EVO_TESTS run=${TESTS_RUN} failed=${TESTS_FAILED} errors=${TESTS_ERRORS}"
echo "EVO_CLASS_COUNT ${CLASS_COUNT}"
echo "EVO_CLASS_CENSUS ${CLASS_CENSUS_SHA256}"
if [ "${EVO_SKIP_TESTS:-false}" != "true" ] && [ "${COMPILE_EXIT}" -eq 0 ] && [ "${TEST_EXIT}" -eq 0 ] && [ "${TESTS_FAILED}" -eq 0 ] && [ "${TESTS_ERRORS}" -eq 0 ]; then
  echo "EVO_CHECK candidate.test"
fi
echo "CENSUS_B64_BEGIN"
base64 /tmp/census.json | sed 's/^/CENSUS_B64:/'
echo "CENSUS_B64_END"
echo "EVO_VM_COMPLETE"
