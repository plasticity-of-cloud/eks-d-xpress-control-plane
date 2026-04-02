# EKS Pod Identity Integration Guide

## Overview

This guide covers deploying the EKS Auth Proxy and wiring it to the `eks-pod-identity-agent` daemonset running on your EKS Distro cluster.

## Architecture

```
Pod → eks-pod-identity-agent (169.254.170.23) → eks-auth-proxy (kube-system) → AWS STS
```

## Prerequisites

- EKS Distro single-node cluster running
- `eks-pod-identity-agent` daemonset deployed
- AWS credentials with `sts:AssumeRole` permission

## Step 1: Configure kube-apiserver as OIDC Provider

Add these flags to `/etc/kubernetes/manifests/kube-apiserver.yaml`:

```yaml
spec:
  containers:
  - command:
    - kube-apiserver
    # ... existing flags ...
    - --service-account-issuer=https://kubernetes.default.svc
    - --service-account-key-file=/etc/kubernetes/pki/sa.pub
    - --service-account-signing-key-file=/etc/kubernetes/pki/sa.key
    - --api-audiences=pods.eks.amazonaws.com,https://kubernetes.default.svc
```

Verify OIDC endpoints are available:
```bash
kubectl get --raw /openid/v1/jwks
kubectl get --raw /.well-known/openid-configuration
```

## Step 2: Create AWS Credentials Secret

```bash
kubectl create secret generic eks-auth-proxy-aws-creds \
  -n kube-system \
  --from-literal=account-id=<AWS_ACCOUNT_ID> \
  --from-literal=access-key-id=<AWS_ACCESS_KEY_ID> \
  --from-literal=secret-access-key=<AWS_SECRET_ACCESS_KEY>
```

## Step 3: Deploy the Proxy

```bash
# Create role associations ConfigMap
kubectl apply -f deploy/pod-identity-associations.yaml

# Edit the ConfigMap to add your actual role mappings
kubectl edit configmap pod-identity-associations -n kube-system

# Deploy the proxy
kubectl apply -f deploy/eks-auth-proxy.yaml

# Verify it's running
kubectl rollout status deployment/eks-auth-proxy -n kube-system
```

## Step 4: Point eks-pod-identity-agent to the Proxy

Patch the daemonset to use the `--endpoint` flag:

```bash
kubectl patch daemonset eks-pod-identity-agent -n kube-system --type=json -p='[
  {"op": "add", "path": "/spec/template/spec/containers/0/args/-", "value": "--endpoint"},
  {"op": "add", "path": "/spec/template/spec/containers/0/args/-", "value": "http://eks-auth-proxy.kube-system.svc.cluster.local:8080"}
]'

# Verify the patch
kubectl get daemonset eks-pod-identity-agent -n kube-system -o jsonpath='{.spec.template.spec.containers[0].args}'
```

## Step 5: Configure Role Associations

Edit `deploy/pod-identity-associations.yaml` with your actual mappings:

```yaml
data:
  # "cluster-name:namespace:serviceaccount": "role-arn"
  "my-cluster:default:my-app": "arn:aws:iam::123456789012:role/my-app-role"
  "my-cluster:ci-cd:*": "arn:aws:iam::123456789012:role/ci-cd-role"
```

Apply:
```bash
kubectl apply -f deploy/pod-identity-associations.yaml
```

## Step 6: Configure Pods to Use Pod Identity

Project a service account token with the correct audience in your pod spec:

```yaml
spec:
  serviceAccountName: my-app
  volumes:
  - name: eks-token
    projected:
      sources:
      - serviceAccountToken:
          audience: pods.eks.amazonaws.com
          expirationSeconds: 86400
          path: token
  containers:
  - name: my-app
    env:
    - name: AWS_CONTAINER_CREDENTIALS_FULL_URI
      value: "http://169.254.170.23/v1/credentials"
    - name: AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE
      value: "/var/run/secrets/eks/token"
    volumeMounts:
    - name: eks-token
      mountPath: /var/run/secrets/eks
```

## Verification

```bash
# Check proxy logs
kubectl logs -n kube-system deployment/eks-auth-proxy -f

# Test from a pod
kubectl run test --rm -it --image=amazon/aws-cli -- aws sts get-caller-identity
```

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Invalid service account token` | Wrong audience in projected token | Set `audience: pods.eks.amazonaws.com` |
| `Missing namespace or service account claims` | Token not a service account token | Use projected service account token |
| `No role association found` | Missing ConfigMap entry | Add entry to `pod-identity-associations` ConfigMap |
| `Failed to assume role` | Missing IAM permissions | Ensure AWS credentials have `sts:AssumeRole` on the target role |
| JWKS fetch fails | kube-apiserver OIDC not configured | Verify `--service-account-issuer` flag is set |
