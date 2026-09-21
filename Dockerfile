# One image, one run command. See docs/SETUP.md ("Fully containerised") and
# docs/DEPLOYMENT.md for the four ways this is meant to be run.
#
# Three stages, and the split is about more than image size: the frontend is
# built by Node, the jar is built by Maven, and the thing that ships needs
# neither of those toolchains - only a JRE and a mongod.
#
# IMPORTANT: this build deliberately runs no tests (`-DskipTests` below, and no
# `vitest` anywhere). That is not an oversight - the quick-start path has to boot
# regardless of test state, so a red suite can never be the reason `docker run`
# fails. The CI pipelines in /harness are where tests gate anything; this file is
# not a quality gate and is not pretending to be one. See docs/SETUP.md,
# "`docker build` and `docker run` never run tests - by design".


# ---------------------------------------------------------------------------
# Stage 1: the frontend bundle.
#
# node:20 (Debian) rather than an -alpine tag. Alpine would be smaller, but this
# stage's output is 'dist/' - a directory of static files that stage 2 copies -
# so the size of this image never reaches the final one. What Alpine would buy in
# megabytes it charges back in Rollup's musl-vs-glibc optional native binaries,
# and the matching node:20 tag is what the /harness pipelines already use for
# this same step.
# ---------------------------------------------------------------------------
FROM node:20 AS frontend

WORKDIR /build/frontend

# The manifests alone first, so `npm ci` is only re-run when dependencies
# actually change rather than on every source edit. Copying the whole frontend
# up front is the usual way to accidentally make every one-line CSS change
# reinstall node_modules.
COPY frontend/package.json frontend/package-lock.json ./
# `ci`, not `install`: it installs exactly what package-lock.json pins and fails
# if the two disagree, so an image can never quietly build against a dependency
# tree nobody committed.
RUN npm ci

COPY frontend/ ./

# `npm run build` is `tsc -b && vite build` - the type check is part of the
# build, not a separate gate, so a type error fails here rather than shipping.
# That is not in tension with the no-tests rule above: this is compilation, and a
# bundle that does not compile is not a bundle.
RUN npm run build


# ---------------------------------------------------------------------------
# Stage 2: the fat jar, with the frontend baked into it.
#
# maven:3.9-eclipse-temurin-25 - Java 25 to match <maven.compiler.release>, and
# the tag the /harness pipelines use. There is no Maven wrapper committed in this
# repo, so the Maven version comes from the base image rather than from mvnw.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS backend

WORKDIR /build

# Same trick as `npm ci` above, for the same reason: resolve dependencies from
# the pom alone so editing Java does not re-download the world. This layer is
# also why the build tolerates a slow or rate-limited registry only once.
COPY backend/pom.xml ./backend/pom.xml
RUN mvn -B -f backend/pom.xml -DskipTests dependency:go-offline

COPY backend/src ./backend/src

# This is the line that makes the single-origin deployment work: Spring Boot
# serves anything under src/main/resources/static/ straight out of the jar, so
# the built frontend and the API end up behind one port with no reverse proxy and
# no CORS in play. It is copied in rather than committed, which is why
# `frontend/dist` is in .gitignore *and* in .dockerignore - the only dist that
# ever reaches the jar is the one stage 1 just built.
COPY --from=frontend /build/frontend/dist ./backend/src/main/resources/static/

# <finalName>app</finalName> in the pom is what makes this predictably
# target/app.jar instead of target/saas-product-investigator-0.1.0.jar, so the
# COPY in stage 3 does not need updating every time the version bumps.
RUN mvn -B -f backend/pom.xml -DskipTests package


# ---------------------------------------------------------------------------
# Stage 3: what actually ships.
#
# eclipse-temurin:25-jre-noble, and every part of that tag is load-bearing:
#
#   -jre       not -jdk. No compiler ships to production.
#   -noble     Ubuntu 24.04, pinned explicitly. The bare `25-jre` tag currently
#              resolves to Ubuntu 26.04 ("resolute"), which MongoDB publishes no
#              apt repository for - the `apt-get install` below would fail with
#              nothing but a 404 to explain why. Pinning the distro is what keeps
#              this build reproducible when Temurin moves its default.
#   not alpine MongoDB's official server binaries are built against glibc and are
#              not supported on musl. This stage needs a real mongod (see the
#              embedded fallback in entrypoint.sh), so Alpine is off the table
#              regardless of what it would save.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-noble

# MongoDB Server, for the embedded single-container fallback only. When
# MONGODB_URI is set - which every sample under /deploy does - this mongod is
# installed and never started.
#
# `mongodb-org-server` alone, not the `mongodb-org` metapackage: that would pull
# in the shell, tools, and mongos, none of which the app uses. `mongodb-mongosh`
# is a genuinely handy addition if you expect to `docker exec` in and inspect the
# embedded database by hand; it is left out here because the deployed
# configurations point at an external Mongo where a shell in this container would
# be talking to nothing.
#
# curl is not incidental either: the ECS task definition's container health check
# runs `curl` from inside this image, and a missing binary there fails the check
# forever and makes ECS kill healthy tasks on a loop. It is also what the
# HEALTHCHECK below uses.
#
# The signing key is vendored at docker/mongodb-server-8.0.asc rather than fetched
# from pgp.mongodb.com during the build, and the repository is read over http
# rather than https. Both halves of that are deliberate, and together they are
# what lets a plain `docker build .` work on a corporate network:
#
#   - Fetching the key over https fails behind a TLS-intercepting proxy (Zscaler,
#     Netskope, most corporate networks) with "curl: (60) SSL certificate problem:
#     unable to get local issuer certificate", because the proxy terminates TLS
#     and presents a certificate signed by a private root that no base image has
#     any reason to trust. Vendoring the key removes that request entirely - and
#     with it a build-time dependency on pgp.mongodb.com being reachable at all.
#   - http for the repository is not the downgrade it looks like. apt authenticates
#     packages by verifying the repository's signature against the key named in
#     signed-by= - the vendored one - so a tampered mirror fails the signature
#     check whether or not TLS was used. What https would add here is privacy
#     about which packages are being downloaded, not integrity. Plain http also
#     passes through an intercepting proxy untouched, which https cannot.
#
# The vendored file is MongoDB's published 8.0 release signing key:
#
#   pub   rsa4096 2024-01-11 [SC]
#         4B07 52C1 BCA2 38C0 B4EE  14DC 41DE 058A 4E7D CA05
#   uid   MongoDB 8.0 Release Signing Key <packaging@mongodb.com>
#
# Check that fingerprint against https://pgp.mongodb.com/server-8.0.asc rather
# than taking this file's word for it, and re-vendor if MongoDB rotates the key or
# this image moves off 8.0. Nothing silently falls back to fetching it.
COPY docker/mongodb-server-8.0.asc /usr/share/keyrings/mongodb-server-8.0.asc

# The armored key is used as-is via signed-by=; apt reads ASCII-armored keys
# directly, which is why no gnupg is installed here just to dearmor it and then
# purged again to keep the layer clean.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/mongodb-server-8.0.asc] http://repo.mongodb.org/apt/ubuntu noble/mongodb-org/8.0 multiverse" \
         > /etc/apt/sources.list.d/mongodb-org-8.0.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends mongodb-org-server \
    # Same layer, or the deleted files stay in the image anyway.
    && rm -rf /var/lib/apt/lists/*

# uid/gid 1000 explicitly, because deploy/k8s/deployment.yaml sets
# runAsNonRoot: true with runAsUser/runAsGroup 1000. A numeric match is what
# makes /data below writable by the user Kubernetes actually runs this as -
# `runAsNonRoot` only checks that the uid is not 0, so a mismatch here surfaces
# much later as a permission error inside the entrypoint.
#
# The userdel is not defensive boilerplate: Ubuntu 24.04 base images ship a
# placeholder `ubuntu` account already holding uid/gid 1000, so `groupadd --gid
# 1000` fails outright with "GID 1000 already exists" (exit 4). Reclaiming the id
# is better than picking a free one, because 1000 is what the Kubernetes manifest
# names. Done conditionally so this still works on a base image that drops the
# placeholder later.
RUN if getent passwd 1000 >/dev/null; then \
        userdel --remove "$(getent passwd 1000 | cut -d: -f1)"; \
    fi \
    && groupadd --gid 1000 app \
    && useradd --uid 1000 --gid 1000 --create-home --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=backend /build/backend/target/app.jar /app/app.jar
COPY entrypoint.sh /entrypoint.sh

# chmod here rather than relying on the checked-in file mode: a clone on a
# filesystem that does not carry the executable bit would otherwise produce an
# image whose ENTRYPOINT cannot run.
RUN chmod +x /entrypoint.sh

# Both container-level fallbacks write here and nowhere else - the embedded
# mongod's data and the generated encryption key - which is what lets a single
# `-v saas-data:/data` persist them together, or nothing persist them together.
# Owned by 1000 so the fallbacks work as the non-root user; a named volume
# inherits this ownership when Docker initialises it from the image.
RUN mkdir -p /data && chown 1000:1000 /data

# Deliberately NO `VOLUME ["/data"]`. Declaring it would make Docker attach a
# fresh anonymous volume on every `docker run`, which breaks three things at once:
# the data would survive container removal as a dangling volume, contradicting
# the "lost if the container is removed" contract in docs/SETUP.md; every run
# would litter the host with another unnamed volume; and because an anonymous
# volume is writable even under `--read-only`, the fail-fast that
# deploy/k8s/deployment.yaml depends on - no MONGODB_URI plus a read-only
# filesystem must abort rather than quietly start a private database - would
# silently stop working. Persistence is the caller's explicit choice
# (`-v saas-data:/data`), not a default.

USER 1000:1000

# One port, and it serves the UI as well as the API - the frontend copied into
# static/ in stage 2 is served from the jar, which is why no NGINX is installed
# above and no second port is opened here.
#
# 8080 is the default; SERVER_PORT overrides it at runtime. EXPOSE is only
# metadata and cannot read an env var set later, so changing SERVER_PORT means
# publishing whatever port you chose rather than this one.
#
# To reach the UI on port 80, publish it - `-p 80:8080` - rather than setting
# SERVER_PORT=80. Docker sets net.ipv4.ip_unprivileged_port_start=0 so the uid
# 1000 above *can* bind 80 there, but containerd under Kubernetes does not, so
# that variant boots locally and crash-loops in a cluster. `-p 80:80` on its own
# publishes to a container port nothing is listening on.
EXPOSE 8080

# Convenience for `docker run` and compose, where nothing else is watching. The
# orchestrated deployments ignore this and define their own: Kubernetes splits
# liveness from readiness (see deploy/k8s/deployment.yaml), and ECS declares its
# own healthCheck block. /actuator/health aggregates Mongo too, so this reports
# unhealthy during a database outage - the right answer for a single container,
# and the wrong one for a replica set, which is exactly why the manifests
# override it.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health || exit 1

# MaxRAMPercentage rather than a fixed -Xmx: the JVM reads the container's own
# memory limit, so the same image sizes its heap correctly under the 1536Mi
# Kubernetes limit and the 2GB Fargate task without either number appearing here.
# Override JAVA_OPTS to add flags; the entrypoint word-splits it on purpose.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["/entrypoint.sh"]
