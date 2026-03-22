#!/usr/bin/env bash
# Run batch-e2e-tests (Testcontainers: Postgres x2, Kafka, MinIO).
# On macOS + Docker Desktop, if tests are skipped, either:
#   - Enable Docker Desktop → Settings → Advanced → "Allow the default Docker socket to be used", or
#   - export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="$HOME/.docker/run/docker.sock"
# The batch-e2e-tests POM also sets TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE for macOS via a Maven profile.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-$HOME/.docker/run/docker.sock}"
# Docker Engine 29+ requires API ≥ 1.44; docker-java default 1.32 causes Testcontainers discovery to fail with HTTP 400.
export DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.44}"

exec mvn -pl batch-e2e-tests -am test \
  -Dtest=ImportPipelineE2eIT,ExportPipelineE2eIT,DispatchPipelineE2eIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  "$@"
