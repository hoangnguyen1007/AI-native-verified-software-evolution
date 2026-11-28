FROM eclipse-temurin:21-jdk-alpine

RUN apk add --no-cache git curl ca-certificates tzdata libc6-compat libstdc++ \
    && addgroup -g 10001 -S evo \
    && adduser -u 10001 -S -G evo evo \
    && mkdir -p /app /data/evo-store /data/evo-store/m2-cache /data/evo-store/sqlite-tmp \
    && chown -R evo:evo /app /data/evo-store

WORKDIR /app

COPY --chown=evo:evo backend/target/evo-workbench.jar /app/evo-workbench.jar

ENV EVO_ONLINE_MODE=true \
    EVO_BIND_HOST=0.0.0.0 \
    EVO_PORT=8080 \
    EVO_STATE_DIR=/data/evo-store \
    EVO_M2_REPOSITORY=/data/evo-store/m2-cache \
    EVO_TRUST_PROXY_HEADERS=true \
    JAVA_OPTS="-Xms512m -Xmx4g -XX:+UseG1GC -XX:SoftRefLRUPolicyMSPerMB=1"

USER 10001:10001

VOLUME ["/data/evo-store"]

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${EVO_PORT:-8080}/api/v1/health" >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "mkdir -p \"${EVO_STATE_DIR:-/data/evo-store}/sqlite-tmp\" \"${EVO_M2_REPOSITORY:-${EVO_STATE_DIR:-/data/evo-store}/m2-cache}\" && exec java $JAVA_OPTS -Dfile.encoding=UTF-8 \"-Dorg.sqlite.tmpdir=${EVO_STATE_DIR:-/data/evo-store}/sqlite-tmp\" -jar /app/evo-workbench.jar --online"]
