#!/bin/sh
set -eu
export PATH=/bin:/jdk/bin LC_ALL=C HOME=/work

# No disks, host mounts, NICs or read/write channels are attached to this guest.
finish() { poweroff -f; }
trap finish EXIT
mount -t proc -o nosuid,nodev,noexec proc /proc
mount -t sysfs -o ro,nosuid,nodev,noexec sysfs /sys
mount -t devtmpfs devtmpfs /dev
mount -t tmpfs -o size=64m,nosuid,nodev,noexec tmpfs /work
mount -t tmpfs -o size=32m,nosuid,nodev,noexec tmpfs /tmp
test "$(ls /sys/class/net)" = lo
test -z "$(ls /sys/block | grep -Ev '^(loop|ram)[0-9]+$')"
cd /inputs
sha256sum -cs manifest.sha256
echo "EVO_VM_INPUTS $(sha256sum manifest.sha256 | cut -d ' ' -f 1)"
echo 'EVO_VM_ISOLATION network=loopback-only disks=none'

javac -J-Xmx128m -proc:none -cp '/inputs/lib/*' -d /work /inputs/ProfileCheck.java
for tree in baseline candidate control; do
    mkdir "/work/$tree"
    javac -J-Xmx128m -proc:none --release 21 -cp '/inputs/lib/*' -d "/work/$tree" /inputs/"$tree"/src/main/java/app/*.java
    echo "EVO_CHECK $tree.build"
    for mode in off on; do
        java -Xms16m -Xmx128m -Djava.io.tmpdir=/tmp -cp "/work:/work/$tree:/inputs/lib/*" ProfileCheck "$mode" "$tree"
        echo "EVO_CHECK $tree.dev-$mode"
    done
done
echo 'EVO_VM_COMPLETE'
