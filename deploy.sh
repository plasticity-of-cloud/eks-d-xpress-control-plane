#!/bin/bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Defaults
TARGET="all"        # all | proxy | webhook | crd | associations
NAMESPACE="kube-system"
CLUSTER=""
DRY_RUN=false

usage() {
    cat <<EOF
Usage: $0 [OPTIONS]

Options:
  --target TARGET    What to deploy: all (default), proxy, webhook, crd, associations
  --namespace NS     Kubernetes namespace (default: kube-system)
  --cluster NAME     kubectl context / cluster name to use
  --dry-run          Print manifests without applying
  --help             Show this help
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --target)    TARGET="$2";    shift 2 ;;
        --namespace) NAMESPACE="$2"; shift 2 ;;
        --cluster)   CLUSTER="$2";   shift 2 ;;
        --dry-run)   DRY_RUN=true;   shift ;;
        --help)      usage ;;
        *) echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

KUBECTL="kubectl"
if [[ -n "$CLUSTER" ]]; then
    KUBECTL="kubectl --context=$CLUSTER"
fi

APPLY="$KUBECTL apply -f"
if [[ "$DRY_RUN" == true ]]; then
    APPLY="$KUBECTL apply --dry-run=client -f"
fi

# Wait for cert-manager CRDs before applying Certificate resources
wait_for_cert_manager() {
    echo -e "${YELLOW}Waiting for cert-manager CRDs...${NC}"
    for crd in certificates.cert-manager.io issuers.cert-manager.io; do
        until $KUBECTL get crd "$crd" &>/dev/null; do
            echo "  waiting for $crd..."
            sleep 3
        done
    done
    echo -e "${GREEN}cert-manager CRDs ready.${NC}"
}

deploy_crd() {
    echo -e "${YELLOW}Deploying PodIdentityAssociation CRD...${NC}"
    $APPLY eks-pod-identity-crd/src/main/resources/crd/pod-identity-association-crd.yaml
    echo -e "${GREEN}CRD deployed.${NC}"
}

deploy_proxy() {
    echo -e "${YELLOW}Deploying eks-auth-proxy...${NC}"
    # Quarkus-generated manifest (built artifact)
    local manifest="eks-auth-proxy/target/kubernetes/kubernetes.yml"
    if [[ -f "$manifest" ]]; then
        $APPLY "$manifest"
    else
        echo -e "${YELLOW}  No built manifest found, using deploy/eks-auth-proxy.yaml${NC}"
        $APPLY deploy/eks-auth-proxy.yaml
    fi
    echo -e "${GREEN}eks-auth-proxy deployed.${NC}"
}

deploy_webhook() {
    echo -e "${YELLOW}Deploying eks-pod-identity-webhook...${NC}"
    wait_for_cert_manager
    $APPLY eks-pod-identity-webhook/k8s/cert-manager.yaml
    # Quarkus-generated manifest (built artifact)
    local manifest="eks-pod-identity-webhook/target/kubernetes/kubernetes.yml"
    if [[ -f "$manifest" ]]; then
        $APPLY "$manifest"
    fi
    $APPLY eks-pod-identity-webhook/k8s/mutating-webhook-configuration.yaml
    echo -e "${GREEN}webhook deployed.${NC}"
}

deploy_associations() {
    echo -e "${YELLOW}Deploying pod-identity-associations ConfigMap...${NC}"
    $APPLY deploy/pod-identity-associations.yaml
    echo -e "${GREEN}ConfigMap deployed.${NC}"
}

echo -e "${GREEN}=== EKS Auth Deploy ===${NC}"
echo "target=$TARGET  namespace=$NAMESPACE  dry-run=$DRY_RUN"
[[ -n "$CLUSTER" ]] && echo "cluster=$CLUSTER"
echo

case "$TARGET" in
    crd)          deploy_crd ;;
    proxy)        deploy_proxy ;;
    webhook)      deploy_webhook ;;
    associations) deploy_associations ;;
    all)
        deploy_crd
        deploy_proxy
        deploy_webhook
        deploy_associations
        ;;
    *) echo -e "${RED}Unknown target: $TARGET${NC}"; exit 1 ;;
esac

echo -e "${GREEN}Done.${NC}"
