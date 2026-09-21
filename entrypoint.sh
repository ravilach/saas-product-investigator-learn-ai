#!/bin/sh
#
# What makes "just `docker run` and a port" actually work.
#
# This script exists to resolve the two things that cannot be Spring property
# defaults, because the JVM needs them to already be true before it starts: a
# reachable MongoDB, and an encryption key to protect what it stores. Everything
# else in CONFIG / ENV VARS is handled inside the app once it is running -
# JWT_SECRET is generated and persisted on first boot, and the LLM API keys can
# be added from the Admin Console - because by that point both of these exist.
#
# Both fallbacks write under /data on purpose. Mount one volume there and the
# embedded database *and* the generated key persist together; mount nothing and
# they are ephemeral together. There is deliberately no arrangement where one
# outlives the other, because a key without its database is useless and a
# database without its key is unreadable.
#
# Neither fallback is a deployment strategy. Both print a WARNING, and either
# warning showing up in a deployed environment means something above this
# container is not wired up - see docs/SETUP.md, "Container fallbacks, and where
# the line is".

set -e

# ---------------------------------------------------------------------------
# MongoDB: use MONGODB_URI if given, else run one locally in this container.
# ---------------------------------------------------------------------------
if [ -z "$MONGODB_URI" ]; then
  # A read-only root filesystem with no volume at /data fails here, immediately
  # and loudly. That is the correct outcome rather than a bug to work around: the
  # alternative is every replica quietly booting its own private database with
  # its own generated key. deploy/k8s/deployment.yaml relies on exactly this.
  if ! mkdir -p /data/db 2>/dev/null; then
    echo "ERROR: MONGODB_URI is not set, so an embedded MongoDB is needed, but /data is not writable." >&2
    echo "       Either set MONGODB_URI to point at a real database (what every sample under /deploy does)," >&2
    echo "       or make /data writable - 'docker run -v saas-data:/data', or a volume in your pod spec." >&2
    exit 1
  fi

  # --fork returns only once mongod has finished starting up and is listening, so
  # what follows needs no wait loop or retry: if this command succeeds, the
  # database is already accepting connections.
  #
  # --bind_ip 127.0.0.1 keeps it reachable only from inside this container. An
  # embedded database with no authentication must not be exposed on a published
  # port; if you want to reach Mongo from outside, you want the two-container
  # deploy/docker-compose.yml instead.
  mongod --dbpath /data/db --bind_ip 127.0.0.1 --fork --logpath /data/mongod.log

  export MONGODB_URI="mongodb://127.0.0.1:27017/saas-investigator"
  echo "WARNING: no MONGODB_URI set - started an embedded local MongoDB. Data lives in /data/db in this container and is LOST if the container is removed, unless you mount a volume at /data."
fi

# ---------------------------------------------------------------------------
# Credential encryption key: use the env var if given, else generate one once
# and keep it in /data.
# ---------------------------------------------------------------------------
if [ -z "$CREDENTIAL_ENCRYPTION_KEY" ]; then
  KEY_FILE=/data/credential-key

  if [ ! -f "$KEY_FILE" ]; then
    if ! mkdir -p /data 2>/dev/null; then
      echo "ERROR: CREDENTIAL_ENCRYPTION_KEY is not set and /data is not writable, so one cannot be generated." >&2
      echo "       Set CREDENTIAL_ENCRYPTION_KEY explicitly - 'head -c 32 /dev/urandom | base64' - or make /data writable." >&2
      exit 1
    fi
    # Written with a restrictive mode from the start rather than chmod'ed after:
    # this is the root of trust for every other secret the app stores, and it
    # should never exist as a world-readable file even briefly.
    ( umask 077 && head -c 32 /dev/urandom | base64 > "$KEY_FILE" )
  fi

  CREDENTIAL_ENCRYPTION_KEY="$(cat "$KEY_FILE")"
  export CREDENTIAL_ENCRYPTION_KEY
  echo "WARNING: no CREDENTIAL_ENCRYPTION_KEY set - generated one and stored it at $KEY_FILE. Fine for a local demo; if you later set MONGODB_URI to a real external database, set this explicitly too from that point on - otherwise anything encrypted now becomes unrecoverable the moment this container is recreated."
fi

# exec, so the JVM becomes PID 1 and receives SIGTERM directly. Without it this
# shell would hold PID 1, swallow the signal, and every stop would be a 30-second
# wait followed by SIGKILL - which for this app means killing in-flight runs
# instead of letting them finish inside terminationGracePeriodSeconds.
#
# $JAVA_OPTS is intentionally unquoted: it holds multiple flags that must
# word-split into separate arguments.
# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar /app/app.jar
