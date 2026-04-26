# EKS Pod Identity on EC2 + k3s (Full Emulation)

Run EKS Pod Identity on a plain EC2 instance with k3s — no managed EKS cluster required. Pods get temporary AWS credentials exactly as they would on managed EKS.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  EC2 Instance  (IAM role: sts:AssumeRole + sts:TagSession)      │
│                                                                  │
│  ┌──────────┐    ┌──────────────────────┐    ┌───────────────┐  │
│  │  k3s     │    │ EKS Pod Identity     │    │ eks-auth-     │  │
│  │  cluster │    │ Agent (DaemonSet)     │───▶│ proxy         │──┼──▶ AWS STS
│  └──────────┘    │ 169.254.170.23:80    │    │ :8080         │  │
│       │          └──────────────────────┘    └───────────────┘  │
│       │          ┌──────────────────────┐                       │
│       └─────────▶│ eks-pod-identity-    │                       │
│                  │ webhook (admission)  │                       │
│                  └──────────────────────┘                       │
└─────────────────────────────────────────────────────────────────┘

Flow:
  1. Webhook mutates pod → injects env vars + projected SA token
  2. AWS SDK in pod → calls Agent at 169.254.170.23/v1/credentials
  3. Agent → calls eks-auth-proxy via --endpoint flag
  4. Proxy → TokenReview (k3s API) + association lookup (CRD/ConfigMap) + STS AssumeRole
  5. Temporary credentials returned to pod
```

## Prerequisites

- AWS CLI v2 configured with admin-level credentials (for initial setup only)
- An EC2 key pair in the target region
- Helm 3 installed (for the EKS Pod Identity Agent chart)

## Quick Start (Automated)

```bash
./setup.sh --key-pair my-key --region us-east-1
```

This provisions the EC2 instance, IAM roles, and security group. See [setup.sh](setup.sh) for details.

## Manual Setup

### 1. IAM Roles (Minimal Permissions)

The EC2 instance needs only **two IAM actions** — it acts as a broker that assumes roles on behalf of pods.

#### 1.1 Broker Role (attached to EC2)

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

# Create the broker role
aws iam create-role \
  --role-name k3s-pod-id-broker \
  --assume-role-policy-document file://iam/ec2-trust-policy.json

# Attach minimal policy: only AssumeRole + TagSession on target roles
aws iam put-role-policy \
  --role-name k3s-pod-id-broker \
  --policy-name broker-policy \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Action": ["sts:AssumeRole", "sts:TagSession"],
      "Resource": "arn:aws:iam::'$ACCOUNT_ID':role/k3s-pod-*"
    }]
  }'

# Create instance profile
aws iam create-instance-profile --instance-profile-name k3s-pod-id-profile
aws iam add-role-to-instance-profile \
  --instance-profile-name k3s-pod-id-profile \
  --role-name k3s-pod-id-broker
```

The `Resource` pattern `k3s-pod-*` scopes which roles pods can assume. Adjust to match your naming convention.

#### 1.2 Target Roles (assumed by pods)

Each application role must trust the broker:

```bash
aws iam create-role \
  --role-name k3s-pod-my-app \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"AWS": "arn:aws:iam::'$ACCOUNT_ID':role/k3s-pod-id-broker"},
      "Action": ["sts:AssumeRole", "sts:TagSession"]
    }]
  }'

# Attach whatever permissions the app needs
aws iam attach-role-policy \
  --role-name k3s-pod-my-app \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess
```

### 2. Launch EC2 + Install k3s

```bash
# Launch with the broker instance profile
aws ec2 run-instances \
  --image-id <ubuntu-22.04-ami> \
  --instance-type t3.medium \
  --key-name my-key \
  --iam-instance-profile Name=k3s-pod-id-profile \
  --user-data '#!/bin/bash
    apt-get update -qq && apt-get install -y -qq curl
    curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--disable traefik --disable servicelb" sh -
    mkdir -p /home/ubuntu/.kube
    cp /etc/rancher/k3s/k3s.yaml /home/ubuntu/.kube/config
    chown -R ubuntu:ubuntu /home/ubuntu/.kube'
```

SSH in and verify:

```bash
ssh -i my-key.pem ubuntu@<ip>
kubectl get nodes   # should show Ready
```

### 3. Build and Deploy Components

#### 3.1 Build and deploy eks-auth-proxy

```bash
cd aws-eks-auth-service-proxy
./build.sh --target proxy

# Deploy CRD + proxy
kubectl apply -f eks-pod-identity-crd/src/main/resources/crd/pod-identity-association-crd.yaml
kubectl apply -f deploy/eks-auth-proxy.yaml
```

#### 3.2 Install EKS Pod Identity Agent via Helm

The official chart lives in the [eks-pod-identity-agent](https://github.com/aws/eks-pod-identity-agent) repo under `charts/eks-pod-identity-agent/`.

```bash
CLUSTER_NAME=k3s-pod-id
REGION=us-east-1

helm install eks-pod-identity-agent \
  /path/to/eks-pod-identity-agent/charts/eks-pod-identity-agent \
  --namespace kube-system \
  --set clusterName="$CLUSTER_NAME" \
  --set env.AWS_REGION="$REGION" \
  --set "agent.additionalArgs.--endpoint=http://eks-auth-proxy.kube-system.svc.cluster.local:8080" \
  --set "affinity="
```

Key values explained:

| Value | Purpose |
|-------|---------|
| `agent.additionalArgs.--endpoint` | Points the agent at our proxy instead of the real EKS Auth Service |
| `affinity=` | Clears the default node affinity that filters for EKS compute types |

The init container (`init.create=true`, the default) **must stay enabled** — it creates a dummy network interface and attaches the link-local address `169.254.170.23` to the node. Without it, pods can't reach the agent. It uses `netlink` syscalls only (no EKS dependencies) and works on k3s.

If the chart is not available locally, you can install from the cloned repo:

```bash
git clone https://github.com/aws/eks-pod-identity-agent.git
helm install eks-pod-identity-agent \
  ./eks-pod-identity-agent/charts/eks-pod-identity-agent \
  --namespace kube-system \
  --set clusterName="$CLUSTER_NAME" \
  --set env.AWS_REGION="$REGION" \
  --set "agent.additionalArgs.--endpoint=http://eks-auth-proxy.kube-system.svc.cluster.local:8080" \
  --set "affinity="
```

Verify the agent is running:

```bash
kubectl get ds -n kube-system eks-pod-identity-agent
kubectl logs -n kube-system -l app.kubernetes.io/name=eks-pod-identity-agent
# Should show: "Overriding EKS Auth default endpoint with http://..."
```

#### 3.3 Deploy the webhook

```bash
# cert-manager is required for webhook TLS
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
kubectl wait --for=condition=Available deployment/cert-manager-webhook -n cert-manager --timeout=120s

./deploy.sh --target webhook
```

### 4. Create Pod Identity Associations

Using the ConfigMap fallback (simplest):

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

kubectl apply -f - <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: pod-identity-associations
  namespace: kube-system
data:
  "k3s-pod-id:default:my-app": "arn:aws:iam::${ACCOUNT_ID}:role/k3s-pod-my-app"
  "k3s-pod-id:ci-cd:*": "arn:aws:iam::${ACCOUNT_ID}:role/k3s-pod-ci-cd"
EOF
```

Or using CRDs:

```bash
kubectl apply -f - <<EOF
apiVersion: eks.amazonaws.com/v1
kind: PodIdentityAssociation
metadata:
  name: my-app
  namespace: default
spec:
  clusterName: k3s-pod-id
  namespace: default
  serviceAccount: my-app
  roleArn: arn:aws:iam::${ACCOUNT_ID}:role/k3s-pod-my-app
EOF
```

### 5. Test

```bash
# Create a service account
kubectl create serviceaccount my-app

# Run a test pod
kubectl run aws-test --image=amazon/aws-cli:latest --rm -it \
  --overrides='{"spec":{"serviceAccountName":"my-app"}}' \
  -- sts get-caller-identity
```

Expected output:

```json
{
    "UserId": "AROA...:default-my-app",
    "Account": "123456789012",
    "Arn": "arn:aws:sts::123456789012:assumed-role/k3s-pod-my-app/default-my-app"
}
```

## How the Agent Connects to the Proxy

The EKS Pod Identity Agent has a `--endpoint` flag ([source](https://github.com/aws/eks-pod-identity-agent/blob/main/cmd/server.go#L149)) that overrides the EKS Auth Service URL:

```go
serverCmd.Flags().StringVar(&overrideEksAuthEndpoint, "endpoint", "", "Override for EKS auth endpoint")
```

The Helm chart exposes this via `agent.additionalArgs`:

```yaml
agent:
  additionalArgs:
    "--endpoint": "http://eks-auth-proxy.kube-system.svc.cluster.local:8080"
```

The proxy exposes an AWS-API-compatible endpoint at `POST /clusters/{clusterName}/assets` (see `EksAuthAgentResource.java`) that matches the wire format the agent's SDK client expects.

## IAM Permission Summary

| Role | Permissions | Purpose |
|------|-------------|---------|
| EC2 broker (`k3s-pod-id-broker`) | `sts:AssumeRole`, `sts:TagSession` on `k3s-pod-*` | Assumes target roles on behalf of pods |
| Target roles (`k3s-pod-*`) | Whatever the app needs (S3, DynamoDB, etc.) | Actual application permissions |

No `eks:*`, `ec2:*`, or other broad permissions required on the broker role.

## Cleanup

```bash
# Remove Helm release
helm uninstall eks-pod-identity-agent -n kube-system

# Terminate EC2
aws ec2 terminate-instances --instance-ids <instance-id>

# Remove IAM
aws iam remove-role-from-instance-profile \
  --instance-profile-name k3s-pod-id-profile --role-name k3s-pod-id-broker
aws iam delete-instance-profile --instance-profile-name k3s-pod-id-profile
aws iam delete-role-policy --role-name k3s-pod-id-broker --policy-name broker-policy
aws iam delete-role --role-name k3s-pod-id-broker

# Remove target roles
aws iam detach-role-policy --role-name k3s-pod-my-app \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess
aws iam delete-role --role-name k3s-pod-my-app

# Remove security group
aws ec2 delete-security-group --group-name k3s-pod-id-sg
```

## Troubleshooting

**Agent can't reach proxy**:
```bash
kubectl logs -n kube-system -l app.kubernetes.io/name=eks-pod-identity-agent
# Look for: "Overriding EKS Auth default endpoint with http://..."
# Verify proxy is running:
kubectl get svc eks-auth-proxy -n kube-system
```

**Agent pod not scheduled (affinity mismatch)**:
```bash
# Default chart affinity excludes non-EKS nodes. Clear it:
helm upgrade eks-pod-identity-agent ... --set "affinity="
```

**TokenReview fails**:
```bash
kubectl logs -n kube-system -l app=eks-auth-proxy
# The proxy validates tokens via the k3s API server's TokenReview endpoint.
# Ensure the proxy's ServiceAccount has tokenreviews create permission.
```

**STS AssumeRole fails**:
```bash
# Verify the EC2 instance has the broker role:
curl -s http://169.254.169.254/latest/meta-data/iam/security-credentials/

# Verify the target role trusts the broker:
aws iam get-role --role-name k3s-pod-my-app --query 'Role.AssumeRolePolicyDocument'
```

**Pod not getting credentials injected**:
```bash
# Check webhook is running and mutating:
kubectl get mutatingwebhookconfigurations
kubectl logs -n kube-system -l app=eks-pod-identity-webhook

# Verify association exists:
kubectl get configmap pod-identity-associations -n kube-system -o yaml
# or
kubectl get podidentityassociations -A
```
