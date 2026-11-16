ARG BASE
FROM ${BASE}

# Prepare the miniature Linux guest. Keep QEMU packages out of its root filesystem.
RUN set -eu; mkdir -p /guest/bin /guest/usr/lib /guest/etc /guest/proc /guest/sys /guest/dev /guest/tmp /guest/work; \
    cp -a /lib /guest/lib; cp -a /usr/lib/. /guest/usr/lib/; cp /bin/busybox /guest/bin/; \
    for tool in sh mount poweroff ls grep sha256sum cut mkdir; do ln -s busybox "/guest/bin/$tool"; done; \
    cp /etc/passwd /etc/group /guest/etc/; \
    "$JAVA_HOME/bin/jlink" --add-modules java.base,jdk.compiler,java.desktop,java.logging,java.management,java.naming,java.sql,java.xml,jdk.unsupported,jdk.zipfs \
        --strip-debug --no-header-files --no-man-pages --compress=2 --output /guest/jdk
RUN apk add --no-cache linux-virt qemu-system-x86_64 cpio; \
    test -f /boot/vmlinuz-virt; command -v qemu-system-x86_64

# Authored sources are bundled as immutable input. javac and Spring run after guest boot.
COPY guest-init.sh /guest/init
COPY inputs/ /guest/inputs/
RUN --network=none chmod 0555 /guest/init && cd /guest && find . | LC_ALL=C sort | cpio -o -H newc | gzip -1 > /guest.cpio.gz

ENTRYPOINT ["/bin/busybox", "timeout", "-s", "KILL", "240", "qemu-system-x86_64", "-no-user-config", "-nodefaults", "-nic", "none", "-display", "none", "-monitor", "none", "-serial", "stdio", "-machine", "q35,accel=tcg", "-smp", "1", "-m", "1024", "-no-reboot", "-kernel", "/boot/vmlinuz-virt", "-initrd", "/guest.cpio.gz", "-append", "console=ttyS0 quiet loglevel=3 rdinit=/init panic=-1"]
