# syntax=docker/dockerfile:1

# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — build the deployable uberjar (target/adsb.jar).
#
# `bb build` is the one build surface (bb.edn): it runs the shadow-cljs release
# so resources/public/js holds the optimized frontend, then packages the AOT'd
# backend + resources into target/adsb.jar. That needs a JDK, the Clojure CLI,
# Node (shadow-cljs), and babashka. The official clojure image gives us JDK 21 +
# the Clojure CLI; we add Node and babashka on top.
#
# Layering is cache-friendly on purpose: dependency manifests (deps.edn,
# package.json, package-lock.json, shadow-cljs.edn) are copied and warmed FIRST,
# so an edit to src/ reuses the cached dependency layers and only re-runs the
# compile.
# ─────────────────────────────────────────────────────────────────────────────
FROM clojure:temurin-21-tools-deps-bookworm AS build

# Node for shadow-cljs, plus babashka (bb) — the build task lives in bb.edn.
# Pin babashka for reproducibility. NodeSource 20.x for a current-enough Node.
ARG BABASHKA_VERSION=1.12.196
# hadolint ignore=DL3008,DL3009,DL4006
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
        | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
        > /etc/apt/sources.list.d/nodesource.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends nodejs \
    && curl -fsSL https://raw.githubusercontent.com/babashka/babashka/master/install \
        | bash -s -- --version "${BABASHKA_VERSION}" \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 1) Dependency manifests first, then warm the caches. These layers are reused
#    on every build whose manifests are unchanged.
COPY deps.edn shadow-cljs.edn ./
COPY package.json package-lock.json ./
#    Node deps (deterministic from the lockfile), then Maven/git deps for the
#    build + cljs classpaths. `-P` downloads without running anything.
RUN npm ci --no-audit --no-fund \
    && clojure -P -M:cljs \
    && clojure -P -T:build

# 2) Sources and the build task file. resources/public/js and resources/public/db
#    are excluded via .dockerignore — the release rebuilds js; db is optional data.
COPY bb.edn build.clj ./
COPY src ./src
COPY resources ./resources

# 3) Build the uberjar. bb build == shadow-cljs release app + clojure -T:build uber.
RUN bb build

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — runtime. Only the JRE and the jar; no build toolchain ships.
# Alpine's busybox provides `wget` for the HEALTHCHECK, so nothing extra to add.
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Non-root: the app needs no privileges. Create a dedicated system user.
RUN addgroup -S adsb && adduser -S -G adsb -h /app adsb

WORKDIR /app
COPY --from=build --chown=adsb:adsb /app/target/adsb.jar /app/adsb.jar

USER adsb

# Config is environment-only (adsb.main/env->config). PORT defaults to 8280
# (adsb.http.server/default-port); override any of these at runtime.
ENV PORT=8280 \
    JAVA_OPTS=""

EXPOSE 8280

# Liveness of THIS process — /healthz is always 200 while the JVM serves
# (adsb.http.handlers/health). Uses the runtime PORT so it tracks an override.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -q -O - "http://127.0.0.1:${PORT:-8280}/healthz" >/dev/null 2>&1 || exit 1

# Shell form so ${JAVA_OPTS} expands at runtime. `exec` hands PID 1 to the JVM
# so container stop/SIGTERM reaches it directly.
ENTRYPOINT ["/bin/sh", "-c"]
CMD ["exec java ${JAVA_OPTS} -jar /app/adsb.jar"]
