# EKS-DX Integration: k3s on EC2

End-to-end procedure to enable EKS Pod Identity on a k3s cluster.

## Prerequisites

- k3s node running on EC2 (single-node or multi-node)
- `eks-dx` CLI installed locally
- AWS account with `sam deploy` completed (see [DEPLOYMENT.md](../DEPLOYMENT.md))
- `ENDPOINT` env var set to your API Gateway URL

---

## 1. Configure k3s to expose OIDC

k3s must be started with a public issuer URL so the JWKS can be registered.

On the k3s node:

```bash
# Get the node's public IP
PUBLIC_IP=$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4)

# Install k3s with OIDC issuer set to the public IP
curl -sfL https://get.k3s.io | sh -s - \
  --kube-apiserver-arg="service-account-issuer=https://${PUBLIC_IP}" \
  --kube-apiserver-arg="service-account-jwks-uri=https://${PUBLIC_IP}/openid/v1/jwks"
```

If k3s is already running, add to `/etc/rancher/k3s/config.yaml`:

```yaml
kube-apiserver-arg:
  - "service-account-issuer=https://<PUBLIC_IP>"
  - "service-account-jwks-uri=https://<PUBLIC_IP>/openid/v1/jwks"
```

Then restart: `systemctl restart k3s`

---

## 2. Register the cluster with eks-dx

From your local machine (with `~/.kube/config` pointing at the k3s cluster):

```bash
# Configure CLI endpoint once
eks-dx configure --endpoint $ENDPOINT --region us-east-1

# Register — auto-discovers issuer + JWKS from the kube-apiserver
eks-dx create cluster --name my-k3s
```

Or manually if auto-discovery doesn't work (e.g. kube-apiserver not reachable from your machine):

```bash
# On the k3s node
kubectl get --raw /openid/v1/jwks > /tmp/jwks.json

# From your machine
eks-dx create cluster --name my-k3s \
  --issuer https://<PUBLIC_IP> \
  --jwks-file /tmp/jwks.json
```

Verify:

```bash
eks-dx get cluster my-k3s
```

---

## 3. Install eks-dx-auth-proxy

The proxy runs in `kube-system` and handles TokenReview + credential forwarding.

```bash
VERSION=0.2.0-design

helm install eks-dx-auth-proxy \
  oci://ghcr.io/plasticity-of-cloud/helm/eks-dx-auth-proxy \
  --version ${VERSION} \
  --namespace kube-system \
  --set app.imageConfig.registry=ghcr.io \
  --set app.imageConfig.repository=plasticity-of-cloud/eks-dx-auth-proxy \
  --set app.imageConfig.tag=${VERSION} \
  --set app.envs.EKS_DX_ENDPOINT=${ENDPOINT} \
  --set app.envs.AWS_REGION=us-east-1
```

The proxy needs AWS credentials to sign requests to API Gateway (IAM SigV4 is not used on `/assets` — but the proxy's projected SA token handles auth). If your EC2 node has an instance profile, the proxy picks up credentials automatically via the metadata service. Otherwise pass them explicitly:

```bash
  --set app.envs.AWS_ACCESS_KEY_ID=... \
  --set app.envs.AWS_SECRET_ACCESS_KEY=...
```

Wait for the proxy to be ready:

```bash
kubectl rollout status deployment/eks-dx-auth-proxy -n kube-system
```

---

## 4. Install eks-dx-pod-identity-webhook

The webhook injects `AWS_CONTAINER_CREDENTIALS_FULL_URI` and the projected SA token into pods that have a pod identity association.

```bash
helm install eks-dx-pod-identity-webhook \
  oci://ghcr.io/plasticity-of-cloud/helm/eks-dx-pod-identity-webhook \
  --version ${VERSION} \
  --namespace kube-system \
  --set app.imageConfig.registry=ghcr.io \
  --set app.imageConfig.repository=plasticity-of-cloud/eks-dx-pod-identity-webhook \
  --set app.imageConfig.tag=${VERSION} \
  --set app.envs.EKS_DX_ENDPOINT=${ENDPOINT} \
  --set app.envs.EKS_CLUSTER_NAME=my-k3s
```

---

## 5. Create a pod identity association

```bash
# The IAM role must trust sts:AssumeRole and be named eks-dx-pod-*
eks-dx create association \
  --cluster my-k3s \
  --namespace my-app \
  --service-account my-sa \
  --role-arn arn:aws:iam::123456789012:role/eks-dx-pod-my-role
```

---

## 6. Test

Deploy a pod using the associated service account:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-sa
  namespace: my-app
---
apiVersion: v1
kind: Pod
metadata:
  name: aws-test
  namespace: my-app
spec:
  serviceAccountName: my-sa
  containers:
  - name: aws-cli
    image: amazon/aws-cli:latest
    command: ["aws", "sts", "get-caller-identity"]
```

```bash
kubectl apply -f test-pod.yaml
kubectl logs aws-test -n my-app
# Expected: JSON with the assumed role ARN
```

---

## Troubleshooting

**TokenReview fails:** the proxy can't reach the kube-apiserver. Check `KUBERNETES_SERVICE_HOST` is reachable from `kube-system`.

**JWT validation fails:** the issuer in DynamoDB doesn't match the `iss` claim in the SA token. Re-register with the correct `--issuer`.

**No association found:** the pod's `namespace/serviceAccount` doesn't match any registered association. Run `eks-dx list associations --cluster my-k3s`.

**Proxy token rejected:** the proxy's projected SA token audience doesn't match `eks-dx.codriverlabs.ai`. Check the volume spec in the proxy deployment.
