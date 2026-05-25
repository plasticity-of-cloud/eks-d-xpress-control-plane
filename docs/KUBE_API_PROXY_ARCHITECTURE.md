# Architecture: Kube-API Proxy via Lambda

## Overview

Expose each tenant's kube-apiserver through a stable Lambda endpoint. Users interact via `kubectl` pointing at the Lambda URL — never directly at the instance IP. This enables wake-on-request, stable endpoints across spot reclaims, and centralized auth.

## Architecture

```
User (kubectl)                          Tenant Instance
     │                                       │
     │  kubeconfig.server =                  │  kube-apiserver :6443
     │  https://eks-dx.codriverlabs.ai       │  (local, not exposed)
     │       /tenants/{id}/kube              │
     ▼                                       │
┌─────────────────────────────────┐          │
│  API Gateway (HTTP API)         │          │
│  Route: /tenants/{id}/kube/**   │          │
└──────────────┬──────────────────┘          │
               │                             │
               ▼                             │
┌─────────────────────────────────┐          │
│  Lambda: eks-dx-kube-proxy      │          │
│                                 │          │
│  1. Authenticate (SigV4/token)  │          │
│  2. Check instance state        │          │
│     └─ if stopped → resume      │          │
│     └─ wait for ready           │          │
│  3. Forward request to instance ─┼──────────┘
│  4. Return response             │   (via private IP / VPC)
└─────────────────────────────────┘
```

## What Gets Proxied

| Traffic | Via Lambda? | Reason |
|---------|-------------|--------|
| `kubectl` (human, external) | ✅ Yes | Stable endpoint, wake-on-request, auth |
| kubelet → apiserver (in-cluster) | ❌ No | Local loopback, no proxy needed |
| controllers → apiserver (in-cluster) | ❌ No | Local, high-frequency |
| Pod Identity credential exchange | ❌ No | Already handled by eks-dx-lambda |

**Only external human `kubectl` traffic goes through the proxy.** In-cluster components talk directly to `localhost:6443`.

## Wake-on-Request Flow

```
kubectl get pods
    │
    ▼
Lambda checks DynamoDB: tenant state = "stopped"
    │
    ├─ ec2:StartInstances(hibernate=true resume)
    ├─ Poll ec2:DescribeInstances until "running" (30-60s)
    ├─ Health check: GET https://{privateIp}:6443/healthz (5-10s)
    ├─ Forward original request to kube-apiserver
    └─ Return response to user
    
Total latency on cold resume: ~45-90 seconds (first request only)
Subsequent requests: ~20-50ms (Lambda warm + VPC hop)
```

## Cost Analysis

### Assumptions
- 1 active developer per tenant
- ~500 kubectl commands/day (active hours)
- ~50 list operations (larger payloads)
- Average request duration: 100ms (simple), 300ms (list)
- Lambda memory: 256MB
- Region: us-east-1

### Per-Request Cost

| Component | Unit Price | Notes |
|-----------|-----------|-------|
| API Gateway (HTTP API) | $1.00 / 1M requests | |
| Lambda invocation | $0.20 / 1M requests | |
| Lambda compute (256MB × 100ms) | $0.0000004167 / request | |
| **Total per request** | **~$0.0000016** | |

### Monthly Cost per Tenant (active developer)

| Scenario | Requests/month | Monthly Cost |
|----------|---------------|--------------|
| Light use (100 cmds/day) | ~3,000 | **$0.005** |
| Normal use (500 cmds/day) | ~15,000 | **$0.024** |
| Heavy use (2,000 cmds/day) | ~60,000 | **$0.10** |

**Effectively free.** Even at 60K requests/month, it's $0.10/tenant.

### Comparison with Alternatives

| Approach | Monthly Cost | Availability |
|----------|-------------|--------------|
| Lambda proxy (this design) | ~$0.02/tenant | Always reachable |
| NLB + static IP | ~$16/mo + data | Always reachable |
| Direct IP (no proxy) | $0 | Breaks on spot reclaim/hibernate |
| EKS managed control plane | $73/mo | Always reachable |

## The Watch Problem

Kubernetes watches are long-lived streaming connections. Lambda has a 15-minute max timeout.

**Solution**: Don't proxy watches through Lambda.

| Approach | Trade-off |
|----------|-----------|
| **Reject watches, return `410 Gone`** | kubectl retries with list+watch; works but noisy |
| **Function URL with response streaming** | Up to 15 min per watch; reconnects automatically |
| **Only proxy non-watch requests** | `kubectl get/apply/delete` work; `kubectl logs -f` doesn't |
| **Inform user: use SSH for streaming** | Simple, honest limitation |

**Recommended for v1**: Proxy all standard requests. For `watch` requests (detected via `?watch=true` query param), return a streaming response via Function URL (15 min max). Kubernetes clients handle reconnection gracefully — kubelet and controllers reconnect watches every 5-10 minutes anyway.

## Kubeconfig Output

Provisioning returns a ready-to-use kubeconfig:

```yaml
apiVersion: v1
kind: Config
clusters:
- cluster:
    server: https://eks-dx.codriverlabs.ai/tenants/my-tenant/kube
    certificate-authority-data: <tenant CA cert, base64>
  name: eks-dx-my-tenant
contexts:
- context:
    cluster: eks-dx-my-tenant
    user: eks-dx-my-tenant-admin
  name: eks-dx-my-tenant
current-context: eks-dx-my-tenant
users:
- user:
    exec:
      apiVersion: client.authentication.k8s.io/v1
      command: eks-dx
      args: ["token", "--tenant", "my-tenant"]
```

The `eks-dx token` command generates a short-lived token (signed by the tenant's SA signing key stored in Secrets Manager). The Lambda proxy validates it before forwarding.

## IAM Permissions (Lambda)

```java
// VPC access (to reach tenant instance private IP)
// Lambda must be in the same VPC or have VPC peering

// EC2 (for wake-on-request)
"ec2:StartInstances", "ec2:DescribeInstances"

// DynamoDB (tenant state lookup)
"dynamodb:GetItem"
```

## Implementation Phases

1. **v1**: Proxy `kubectl` requests (non-watch). Wake-on-request. Return kubeconfig from provisioning.
2. **v2**: Stream watches via Function URL (15 min reconnect).
3. **v3**: Auto-hibernate on idle (no kubectl calls for 1 hour).

## Notes

- Lambda must be in VPC (or VPC-attached) to reach tenant instance private IP on port 6443.
- TLS: Lambda terminates external TLS (API Gateway). Re-encrypts to instance using tenant CA.
- The proxy adds ~20-50ms latency on warm requests — imperceptible for human kubectl use.
- This is architecturally identical to how EKS exposes its managed control plane (NLB → ENI → apiserver), but serverless.
