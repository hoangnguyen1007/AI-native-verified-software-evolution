ARG BASE
FROM ${BASE}

# Prepare miniature Alpine/Linux QEMU guest with JDK 21, Maven/Gradle, and ClassDependencyCensus.
RUN set -eu; mkdir -p /guest/bin /guest/usr/lib /guest/etc /guest/proc /guest/sys /guest/dev /guest/tmp /guest/work /guest/census /guest/maven /guest/gradle; \
    cp -a /lib /guest/lib; cp -a /usr/lib/. /guest/usr/lib/; cp /bin/busybox /guest/bin/; \
    for tool in $(/bin/busybox --list); do [ "$tool" = busybox ] || ln -sf busybox "/guest/bin/$tool"; done; \
    cp /etc/passwd /etc/group /guest/etc/; \
    "$JAVA_HOME/bin/jlink" \
        --add-modules java.se,jdk.compiler,jdk.unsupported,jdk.zipfs,jdk.crypto.ec,jdk.management,java.instrument \
        --strip-debug --no-header-files --no-man-pages --compress=2 --output /guest/jdk

RUN apk add --no-cache linux-virt qemu-system-x86_64 cpio unzip && \
    test -f /boot/vmlinuz-virt && command -v qemu-system-x86_64

COPY tool-dist/ /tmp/tool-dist/
RUN set -eu; \
    if [ -f /tmp/tool-dist/maven.zip ]; then \
        unzip -q /tmp/tool-dist/maven.zip -d /tmp/maven-dist && \
        cp -a /tmp/maven-dist/apache-maven-*/* /guest/maven/; \
    fi; \
    if [ -f /tmp/tool-dist/gradle.zip ]; then \
        unzip -q /tmp/tool-dist/gradle.zip -d /tmp/gradle-dist && \
        cp -a /tmp/gradle-dist/gradle-*/* /guest/gradle/; \
    fi; \
    rm -rf /tmp/tool-dist /tmp/maven-dist /tmp/gradle-dist

COPY inputs/ClassDependencyCensus.java /tmp/ClassDependencyCensus.java
RUN "$JAVA_HOME/bin/javac" -d /guest/census /tmp/ClassDependencyCensus.java && rm -f /tmp/ClassDependencyCensus.java

COPY guest-init.sh /guest/init
COPY inputs/ /guest/inputs/
RUN --network=none sed -i 's/\r$//' /guest/init && chmod 0555 /guest/init && \
    cd /guest && find . | LC_ALL=C sort | cpio -o -H newc | gzip -1 > /guest.cpio.gz

ENTRYPOINT ["/bin/busybox", "timeout", "-s", "KILL", "900", "qemu-system-x86_64", "-no-user-config", "-nodefaults", "-nic", "none", "-display", "none", "-monitor", "none", "-serial", "stdio", "-machine", "q35,accel=tcg", "-cpu", "max", "-smp", "2", "-m", "2560", "-no-reboot", "-kernel", "/boot/vmlinuz-virt", "-initrd", "/guest.cpio.gz", "-append", "console=ttyS0 quiet loglevel=3 rdinit=/init panic=-1"]
