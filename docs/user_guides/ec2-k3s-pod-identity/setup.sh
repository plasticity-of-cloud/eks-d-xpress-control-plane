#!/bin/bash
#
# Automated setup: EC2 + k3s + EKS Pod Identity (full emulation)
#
# Prerequisites:
#   - AWS CLI v2 configured with admin-level credentials
#   - An existing EC2 key pair
#
# Usage:
#   ./setup.sh --key-pair my-key --region us-east-1 [--cluster-name k3s-pod-id] [--instance-type t3.medium]
#
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[+]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
err()  { echo -e "${RED}[✗]${NC} $*" >&2; exit 1; }

# ── defaults ──────────────────────────────────────────────────────────
REGION="${AWS_REGION:-us-east-1}"
CLUSTER_NAME="k3s-pod-id"
INSTANCE_TYPE="t3.medium"
KEY_PAIR=""
BROKER_ROLE="k3s-pod-id-broker"
BROKER_POLICY="k3s-pod-id-broker-policy"
INSTANCE_PROFILE="k3s-pod-id-profile"
SG_NAME="k3s-pod-id-sg"

usage() {
  cat <<EOF
Usage: $0 --key-pair NAME [OPTIONS]

Required:
  --key-pair NAME       EC2 key pair name

Options:
  --region REGION       AWS region            (default: $REGION)
  --cluster-name NAME   Cluster name          (default: $CLUSTER_NAME)
  --instance-type TYPE  EC2 instance type     (default: $INSTANCE_TYPE)
  --help                Show this help
EOF
  exit 0
}

while [[ $# -gt 0 ]]; do
  case $1 in
    --key-pair)       KEY_PAIR="$2";       shift 2 ;;
    --region)         REGION="$2";         shift 2 ;;
    --cluster-name)   CLUSTER_NAME="$2";   shift 2 ;;
    --instance-type)  INSTANCE_TYPE="$2";  shift 2 ;;
    --help)           usage ;;
    *) err "Unknown option: $1" ;;
  esac
done

[[ -z "$KEY_PAIR" ]] && err "--key-pair is required"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log "Account: $ACCOUNT_ID  Region: $REGION  Cluster: $CLUSTER_NAME"

# ── 1. IAM: broker role (minimal permissions) ────────────────────────
log "Creating IAM broker role ($BROKER_ROLE) ..."

aws iam create-role \
  --role-name "$BROKER_ROLE" \
  --assume-role-policy-document file://"$SCRIPT_DIR"/iam/ec2-trust-policy.json \
  --output text 2>/dev/null || warn "Role $BROKER_ROLE already exists"

# Inline policy — only sts:AssumeRole + sts:TagSession on k3s-pod-* roles
POLICY_DOC=$(sed "s/\\*/$ACCOUNT_ID/" "$SCRIPT_DIR"/iam/ec2-broker-policy.json)
aws iam put-role-policy \
  --role-name "$BROKER_ROLE" \
  --policy-name "$BROKER_POLICY" \
  --policy-document "$POLICY_DOC"

# Instance profile
aws iam create-instance-profile \
  --instance-profile-name "$INSTANCE_PROFILE" \
  --output text 2>/dev/null || warn "Instance profile already exists"

aws iam add-role-to-instance-profile \
  --instance-profile-name "$INSTANCE_PROFILE" \
  --role-name "$BROKER_ROLE" 2>/dev/null || true

# Wait for IAM propagation
log "Waiting for IAM propagation ..."
sleep 10

# ── 2. Security group ────────────────────────────────────────────────
log "Creating security group ($SG_NAME) ..."

VPC_ID=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true \
  --query 'Vpcs[0].VpcId' --output text --region "$REGION")

SG_ID=$(aws ec2 create-security-group \
  --group-name "$SG_NAME" \
  --description "k3s pod identity cluster" \
  --vpc-id "$VPC_ID" \
  --query 'GroupId' --output text --region "$REGION" 2>/dev/null || \
  aws ec2 describe-security-groups --group-names "$SG_NAME" \
  --query 'SecurityGroups[0].GroupId' --output text --region "$REGION")

for PORT in 22 6443; do
  aws ec2 authorize-security-group-ingress \
    --group-id "$SG_ID" --protocol tcp --port "$PORT" --cidr 0.0.0.0/0 \
    --region "$REGION" 2>/dev/null || true
done

# ── 3. User-data: install k3s + helm on first boot ───────────────────
read -r -d '' USERDATA <<'CLOUD_INIT' || true
#!/bin/bash
set -e
apt-get update -qq && apt-get install -y -qq curl jq git > /dev/null

# k3s (disable built-in LB and ingress — not needed)
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--disable traefik --disable servicelb" sh -

# kubeconfig for ubuntu user
mkdir -p /home/ubuntu/.kube
cp /etc/rancher/k3s/k3s.yaml /home/ubuntu/.kube/config
sed -i 's/127.0.0.1/0.0.0.0/' /home/ubuntu/.kube/config
chown -R ubuntu:ubuntu /home/ubuntu/.kube
chmod 600 /home/ubuntu/.kube/config
echo "export KUBECONFIG=/home/ubuntu/.kube/config" >> /home/ubuntu/.bashrc

# helm
curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
CLOUD_INIT

# ── 4. Launch EC2 ─────────────────────────────────────────────────────
log "Looking up latest Ubuntu 22.04 AMI ..."
AMI_ID=$(aws ec2 describe-images --owners 099720109477 \
  --filters "Name=name,Values=ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*" \
            "Name=state,Values=available" \
  --query 'Images | sort_by(@, &CreationDate) | [-1].ImageId' \
  --output text --region "$REGION")

log "Launching $INSTANCE_TYPE ($AMI_ID) ..."
INSTANCE_ID=$(aws ec2 run-instances \
  --image-id "$AMI_ID" \
  --instance-type "$INSTANCE_TYPE" \
  --key-name "$KEY_PAIR" \
  --security-group-ids "$SG_ID" \
  --iam-instance-profile Name="$INSTANCE_PROFILE" \
  --user-data "$USERDATA" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$CLUSTER_NAME}]" \
  --query 'Instances[0].InstanceId' --output text --region "$REGION")

log "Waiting for instance $INSTANCE_ID to be running ..."
aws ec2 wait instance-running --instance-ids "$INSTANCE_ID" --region "$REGION"

PUBLIC_IP=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text --region "$REGION")

log "Instance ready: $PUBLIC_IP"

# ── 5. Print summary ─────────────────────────────────────────────────
cat <<SUMMARY

${GREEN}═══════════════════════════════════════════════════════════════${NC}
  EC2 + k3s + EKS Pod Identity — infrastructure ready
${GREEN}═══════════════════════════════════════════════════════════════${NC}

  Instance:  $INSTANCE_ID ($PUBLIC_IP)
  Region:    $REGION
  Cluster:   $CLUSTER_NAME
  Broker:    $BROKER_ROLE  (sts:AssumeRole on k3s-pod-* roles)

  ${YELLOW}Next steps (on the instance):${NC}

  1. SSH in:
       ssh -i ${KEY_PAIR}.pem ubuntu@${PUBLIC_IP}

  2. Wait ~2 min for cloud-init, then verify k3s:
       kubectl get nodes

  3. Clone this repo and build the proxy:
       git clone <repo-url> && cd aws-eks-auth-service-proxy
       ./build.sh --target proxy

  4. Deploy CRD + proxy:
       kubectl apply -f eks-pod-identity-crd/src/main/resources/crd/pod-identity-association-crd.yaml
       kubectl apply -f deploy/eks-auth-proxy.yaml

  5. Install EKS Pod Identity Agent via Helm:
       git clone https://github.com/aws/eks-pod-identity-agent.git /tmp/eks-pod-identity-agent

       helm install eks-pod-identity-agent \\
         /tmp/eks-pod-identity-agent/charts/eks-pod-identity-agent \\
         --namespace kube-system \\
         --set clusterName="${CLUSTER_NAME}" \\
         --set env.AWS_REGION="${REGION}" \\
         --set "agent.additionalArgs.--endpoint=http://eks-auth-proxy.kube-system.svc.cluster.local:8080" \\
         --set "affinity="

  6. Deploy the webhook:
       kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
       kubectl wait --for=condition=Available deployment/cert-manager-webhook -n cert-manager --timeout=120s
       ./deploy.sh --target webhook

  7. Create a target role that the broker can assume:
       aws iam create-role --role-name k3s-pod-my-app \\
         --assume-role-policy-document '{
           "Version":"2012-10-17",
           "Statement":[{
             "Effect":"Allow",
             "Principal":{"AWS":"arn:aws:iam::${ACCOUNT_ID}:role/${BROKER_ROLE}"},
             "Action":["sts:AssumeRole","sts:TagSession"]
           }]
         }'

  8. Create a pod identity association:
       kubectl apply -f - <<EOF
       apiVersion: v1
       kind: ConfigMap
       metadata:
         name: pod-identity-associations
         namespace: kube-system
       data:
         "${CLUSTER_NAME}:default:my-app": "arn:aws:iam::${ACCOUNT_ID}:role/k3s-pod-my-app"
       EOF

  9. Test:
       kubectl create serviceaccount my-app
       kubectl run aws-test --image=amazon/aws-cli:latest --rm -it \\
         --overrides='{"spec":{"serviceAccountName":"my-app"}}' \\
         -- sts get-caller-identity

SUMMARY
