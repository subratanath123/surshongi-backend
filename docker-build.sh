#!/usr/bin/env bash
set -euo pipefail

# Builds the SurShongi API image.
#   ./docker-build.sh --env prod --tag 0.1 --push
#   ./docker-build.sh --env qa   --tag 0.1 --load

ENV_TARGET="prod"
TAG="${TAG:-0.1}"
REGISTRY="${REGISTRY:-}"
ACTION="--load"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env|-e) ENV_TARGET="$2"; shift 2 ;;
    --tag|-t) TAG="$2"; shift 2 ;;
    --registry|-r) REGISTRY="$2"; shift 2 ;;
    --push) ACTION="--push"; shift ;;
    --load) ACTION="--load"; shift ;;
    *) echo "Usage: ./docker-build.sh [--env qa|pps|prod|all] [--tag 0.1] [--registry docker.io] [--push|--load]"; exit 1 ;;
  esac
done

GIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo 'local')"
export ENV_TARGET TAG REGISTRY GIT_SHA

echo "Building ${ENV_TARGET} image ${REGISTRY:+${REGISTRY}/}${ENV_TARGET}/surshongi-api:${TAG} (${GIT_SHA})"

docker buildx bake --file docker-bake.hcl "${ACTION}" "${ENV_TARGET}"
