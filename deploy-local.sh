#!/usr/bin/env bash
# deploy-local.sh — build Lambda zips and deploy CDK stack
#
# Usage:
#   ./deploy-local.sh                  # build JVM + deploy
#   ./deploy-local.sh --skip-build     # deploy only (reuse existing zips)
#   ./deploy-local.sh --native         # build GraalVM native tenant-service + deploy
#   ./deploy-local.sh --profile <name> # AWS profile to use
#   ./deploy-local.sh --context key=val # extra CDK context (repeatable)
#
set -euo pipefail

SKIP_BUILD=false
NATIVE=false
AWS_PROFILE_ARG=""
CDK_CONTEXT_ARGS=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --skip-build) SKIP_BUILD=true ;;
    --native)     NATIVE=true ;;
    --profile)    AWS_PROFILE_ARG="--profile $2"; shift ;;
    --context)    CDK_CONTEXT_ARGS="$CDK_CONTEXT_ARGS -c $2"; shift ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

if ! $SKIP_BUILD; then
  echo "==> Building Lambda zips"
  mvn -B -N install -DskipTests

  mvn -B -pl eks-dx-model install -DskipTests
  mvn -B -pl eks-dx-credential-service package -DskipTests
  mvn -B -pl eks-dx-mgmt-service package -DskipTests

  if $NATIVE; then
    mvn -B -pl eks-dx-tenant-service package -DskipTests -Pnative
  else
    mvn -B -pl eks-dx-tenant-service package -DskipTests
  fi
  echo "==> Build complete"
fi

echo "==> Deploying CDK stack"
cd infra
cdk deploy EksDxStack \
  --require-approval never \
  $AWS_PROFILE_ARG \
  $CDK_CONTEXT_ARGS

echo ""
echo "==> Deploy complete"
ENDPOINT=$(cd infra && cdk --no-color outputs EksDxStack 2>/dev/null | grep "^EksDxStack.Endpoint" | awk '{print $NF}' || true)
if [ -n "$ENDPOINT" ]; then
  echo ""
  echo "    API endpoint: $ENDPOINT"
  echo "    Configure CLI: eks-dx configure --endpoint $ENDPOINT"
fi
