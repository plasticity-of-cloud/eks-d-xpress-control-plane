# Deploying on k3s (EC2 or bare-metal)

Run EKS Pod Identity on a plain k3s cluster — no managed EKS control plane required.
Pods get temporary AWS credentials exactly as they would on managed EKS.

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  k3s node  (IAM broker role: sts:AssumeRole + sts:TagSession)    │
│                                                                   │
│  ┌──────────┐   ┌─────────────────────┐   ┌──────────────────┐  │
│  │  k3s     │   │ EKS Pod Identity    │   │ eks-auth-proxy   │  │
│  │  cluster │   │ Agent (DaemonSet)   │──▶│ :8080            │──┼──▶ AWS STS
│  └──────────┘   │ 169.254.170.23:80   │   └──────────────────┘  │
│       │         └─────────────────────┘                          │
│       │         ┌─────────────────────┐                          │
│       └────────▶│ eks-pod-identity-   │                          │
│                 │ webhook (admission) │                          │
│                 └─────────────────────┘                          │
└──────────────────────────────────────────────────────────────────┘

Flow:
  1. Webhook mutates pod → injects env vars + projected SA token
  2. AWS SDK in pod → calls Agent at 169.254.170.23/v1/credentials
  3. Agent → calls eks-auth-proxy via --endpoint flag
  4. Proxy → TokenReview (k3s API) + association lookup (CRD/ConfigMap) + STS AssumeRole
  5. Temporary credentials returned to pod
```

## Prerequisites

- k3s cluster running (single-node is fine)
- Helm 3 installed
- EC2 instance profile with a **broker role** (only `sts:AssumeRole` + `sts:TagSession`)
- `eks-d-auth-cli` binary built (`./build.sh --target cli`)

> **Full EC2 provisioning guide**: see [docs/user_guides/ec2-k3s-pod-identity/](../docs/user_guides/ec2-k3s-pod-identity/) for automated instance + IAM setup.

### IAM Broker Role (minimal permissions)

The EC2 instance needs only two IAM actions — it assumes roles on behalf of pods:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["sts:AssumeRole", "sts:TagSession"],
    "Resource": "arn:aws:iam::ACCOUNT_ID:role/k3s-pod-*"
  }]
}
```

Each target role (assumed by pods) must trust the broker:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"AWS": "arn:aws:iam::ACCOUNT_ID:role/k3s-pod-id-broker"},
    "Action": ["sts:AssumeRole", "sts:TagSession"]
  }]
}
```

## Step 1: Deploy CRD

```bash
kubectl apply -f eks-pod-identity-crd/src/main/resources/crd/pod-identity-association-crd.yaml
```

## Step 2: Deploy eks-auth-proxy

```bash
# Create AWS credentials secret
kubectl create secret generic eks-auth-proxy-aws-creds \
  -n kube-system \
  --from-literal=account-id=<AWS_ACCOUNT_ID> \
  --from-literal=access-key-id=<AWS_ACCESS_KEY_ID> \
  --from-literal=secret-access-key=<AWS_SECRET_ACCESS_KEY>

# Deploy cert-manager resources (for TLS)
kubectl apply -f eks-auth-proxy/k8s/cert-manager.yaml

# Deploy the proxy
kubectl apply -f deploy/eks-auth-proxy.yaml

# Verify
kubectl rollout status deployment/eks-auth-proxy -n kube-system
```

> **Note**: On EC2 with an instance profile, you can skip the AWS credentials secret
> and modify `eks-auth-proxy.yaml` to remove the `eks-auth-proxy-aws-creds` secret
> references — the SDK will use the instance metadata service automatically.

## Step 3: Install EKS Pod Identity Agent (Helm)

```bash
CLUSTER_NAME=k3s-pod-id
REGION=us-east-1

git clone https://github.com/aws/eks-pod-identity-agent.git /tmp/eks-pod-identity-agent

helm install eks-pod-identity-agent \
  /tmp/eks-pod-identity-agent/charts/eks-pod-identity-agent \
  --namespace kube-system \
  --set clusterName="$CLUSTER_NAME" \
  --set env.AWS_REGION="$REGION" \
  --set "agent.additionalArgs.--endpoint=http://eks-auth-proxy.kube-system.svc.cluster.local:8080" \
  --set "affinity="
```

Key Helm values:

| Value | Purpose |
|-------|---------|
| `agent.additionalArgs.--endpoint` | Points agent at our proxy instead of real EKS Auth |
| `affinity=` | Clears default node affinity that filters for EKS compute types |

The init container creates a dummy network interface with `169.254.170.23` — this is how pods reach the agent. It uses `netlink` syscalls only (no EKS dependencies) and works on k3s.

Verify:

```bash
kubectl get ds -n kube-system eks-pod-identity-agent
kubectl logs -n kube-system -l app.kubernetes.io/name=eks-pod-identity-agent
# Should show: "Overriding EKS Auth default endpoint with http://..."
```

## Step 4: Deploy the Webhook

```bash
# cert-manager is required for webhook TLS
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
kubectl wait --for=condition=Available deployment/cert-manager-webhook -n cert-manager --timeout=120s

# Deploy webhook
./deploy.sh --target webhook
```

## Step 5: Create Pod Identity Associations (CLI)

**Always use the `eks-d-auth-cli` to manage associations.** It creates CRD resources that the proxy watches.

```bash
CLI=./eks-d-auth-cli/target/eks-d-auth-cli-*-runner

# Create
$CLI create \
  --cluster-name k3s-pod-id \
  --service-account default:my-app \
  --role-arn arn:aws:iam::123456789012:role/k3s-pod-my-app

# List
$CLI list --cluster-name k3s-pod-id

# Describe
$CLI describe \
  --cluster-name k3s-pod-id \
  --service-account default:my-app

# Delete
$CLI delete \
  --cluster-name k3s-pod-id \
  --service-account default:my-app
```

The `--service-account` format is `namespace:serviceaccount`.

## Step 6: Test

```bash
# Create a service account
kubectl create serviceaccount my-app

# Run a test pod — the webhook injects env vars + token automatically
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

## Troubleshooting

| Symptom | Check |
|---------|-------|
| Agent can't reach proxy | `kubectl logs -n kube-system -l app.kubernetes.io/name=eks-pod-identity-agent` — look for "Overriding EKS Auth default endpoint" |
| Agent pod not scheduled | Default chart affinity excludes non-EKS nodes. Ensure `--set "affinity="` was passed to Helm |
| TokenReview fails | `kubectl logs -n kube-system -l app=eks-auth-proxy` — proxy SA needs `tokenreviews` create permission |
| STS AssumeRole fails | Verify EC2 has broker role: `curl -s http://169.254.169.254/latest/meta-data/iam/security-credentials/` and target role trusts the broker |
| Pod not getting credentials | Check webhook: `kubectl get mutatingwebhookconfigurations` and `kubectl logs -n kube-system -l app=eks-pod-identity-webhook` |
| No association found | `$CLI list --cluster-name k3s-pod-id` — verify association exists for the service account |

## Cleanup

```bash
# Remove Helm release
helm uninstall eks-pod-identity-agent -n kube-system

# Remove deployments
kubectl delete -f deploy/eks-auth-proxy.yaml
kubectl delete -f eks-auth-proxy/k8s/cert-manager.yaml
kubectl delete -f eks-pod-identity-crd/src/main/resources/crd/pod-identity-association-crd.yaml

# Remove cert-manager (optional)
kubectl delete -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
```
