# EKS-DX: Lambda-Based Pod Identity Service

## Overview

Move the eks-auth-proxy out of the cluster into AWS Lambda. Associations stored in DynamoDB. Clusters (k3s, microk8s, EKS-D) register with the service and receive scoped credentials to query their own associations only.

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│  AWS Account                                                         │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │ Lambda: eks-dx-auth-service                                  │    │
│  │   POST /clusters/{clusterName}/assets                        │    │
│  │   • Validates token (JWKS from DynamoDB — pushed by cluster) │    │
│  │   • Looks up association in DynamoDB                          │    │
│  │   • STS AssumeRole → returns temporary credentials           │    │
│  └──────────────┬───────────────────────┬───────────────────────┘    │
│                 │                       │                            │
│  ┌──────────────▼──────┐  ┌─────────────▼──────┐                    │
│  │ DynamoDB             │  │ STS                 │                    │
│  │                      │  │ AssumeRole           │                    │
│  │ eks-dx-clusters      │  │ + TagSession         │                    │
│  │  PK: <clusterName>   │  └────────────────────┘                    │
│  │  issuer, jwks, ...   │                                            │
│  │                      │                                            │
│  │ eks-dx-associations  │                                            │
│  │  PK: CLUSTER#<name>  │                                            │
│  │  SK: <ns>#<sa>       │                                            │
│  └──────────────────────┘                                            │
│                                                                      │
│  API Gateway (HTTPS)                                                 │
└──────────────────────────────────────────────────────────────────────┘
          ▲                              ▲
          │ POST /clusters/*/assets      │ DynamoDB GetItem
          │ (token exchange)             │ (association check + JWKS push)
          │                              │
┌─────────┼──────────────────────────────┼─────────────────────────────┐
│  EC2 instance                          │                             │
│  Instance profile: dynamodb:UpdateItem │ (JWKS push only,            │
│  on eks-dx-clusters for own PK         │  scoped to own cluster)     │
│                                        │                             │
│  ┌─────────────────────────────────────┼───────────────────────┐    │
│  │ k3s / microk8s / EKS-D cluster      │                       │    │
│  │                                     │                       │    │
│  │  ┌──────────────────────┐  ┌────────┴──────────────────┐   │    │
│  │  │ EKS Pod Identity     │  │ eks-pod-identity-webhook  │   │    │
│  │  │ Agent (DaemonSet)    │  │ DynamoDB GetItem for      │   │    │
│  │  │ --endpoint <gw-url>  │  │ association check         │   │    │
│  │  │ 169.254.170.23:80    │  │ (via Pod Identity creds)  │   │    │
│  │  └──────────────────────┘  └───────────────────────────┘   │    │
│  │                                                             │    │
│  │  ┌─────────────────────────────────────────────────────┐   │    │
│  │  │ JWKS sync (host-level, NOT a pod)                   │   │    │
│  │  │ systemd timer or cron, every 6h                     │   │    │
│  │  │ curl localhost:6443/openid/v1/jwks → DynamoDB       │   │    │
│  │  │ Uses EC2 instance profile directly                  │   │    │
│  │  └─────────────────────────────────────────────────────┘   │    │
│  └─────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

## Trust Model and Credential Isolation

### Problem

The EC2 instance profile is accessible via `169.254.169.254` to anything on the host. Without isolation, any pod could use it.

### Solution

Three layers of isolation:

| Component | Runs as | Gets credentials from | Permissions |
|-----------|---------|----------------------|-------------|
| **JWKS sync** | Host-level systemd/cron (not a pod) | EC2 instance profile | `dynamodb:UpdateItem` on own cluster row only |
| **Webhook** | Kubernetes pod | Pod Identity (via agent → Lambda) | `dynamodb:GetItem/Query` on own cluster associations |
| **Application pods** | Kubernetes pod | Pod Identity (via agent → Lambda) | Whatever role is mapped in their association |

**Why this is safe:**

1. The **EKS Pod Identity Agent** intercepts `169.254.169.254` for all pods via iptables. Pods cannot reach the EC2 metadata service — they get credentials from the agent instead.

2. The **JWKS sync** runs on the host (systemd), outside Kubernetes. It's the only process that uses the instance profile. It has a single permission: write JWKS to its own cluster's DynamoDB row.

3. The **webhook** gets its DynamoDB read credentials through Pod Identity itself — dogfooding the system. Its association maps to a role with scoped `dynamodb:GetItem/Query`.

4. The **instance profile has no STS permissions**. AssumeRole happens only inside the Lambda. Even if a pod somehow bypassed the agent and reached the metadata service, it could only push JWKS — not assume any role.

### EC2 Instance Profile (Minimal)

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "PushJWKSOnly",
    "Effect": "Allow",
    "Action": "dynamodb:UpdateItem",
    "Resource": "arn:aws:dynamodb:*:*:table/eks-dx-clusters",
    "Condition": {
      "ForAllValues:StringEquals": {
        "dynamodb:LeadingKeys": ["my-k3s"]
      }
    }
  }]
}
```

One permission. No STS. No association reads. Scoped to own cluster key.

## JWKS Sync Agent

Runs on the EC2 host, not as a Kubernetes pod. Pushes the cluster's SA signing public keys to DynamoDB so the Lambda can validate tokens without reaching back into the cluster.

### Implementation

Installed at cluster registration time as a systemd timer:

```bash
# /etc/systemd/system/eks-dx-jwks-sync.service
[Unit]
Description=EKS-DX JWKS sync

[Service]
Type=oneshot
ExecStart=/usr/local/bin/eks-dx-jwks-sync
```

```bash
# /etc/systemd/system/eks-dx-jwks-sync.timer
[Unit]
Description=Sync JWKS every 6 hours

[Timer]
OnBootSec=1min
OnUnitActiveSec=6h

[Install]
WantedBy=timers.target
```

```bash
#!/bin/bash
# /usr/local/bin/eks-dx-jwks-sync
CLUSTER_NAME="my-k3s"
REGION="us-east-1"
JWKS=$(curl -sk https://localhost:6443/openid/v1/jwks)

aws dynamodb update-item \
  --table-name eks-dx-clusters \
  --region "$REGION" \
  --key '{"clusterName":{"S":"'"$CLUSTER_NAME"'"}}' \
  --update-expression "SET jwks = :j, jwksSyncedAt = :t" \
  --expression-attribute-values '{
    ":j": {"S": "'"$(echo "$JWKS" | jq -c .)"'"},
    ":t": {"S": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'"}
  }'
```

### Why Host-Level, Not a Pod

- Pods can't use the instance profile (agent blocks metadata service)
- No chicken-and-egg: JWKS sync doesn't depend on Pod Identity being functional
- Runs before and independently of the Kubernetes control plane components
- If k3s restarts, the sync still runs on the next timer tick

## Cluster Registration

### Registration Flow

```bash
eks-dx register \
  --cluster-name my-k3s \
  --issuer https://kubernetes.default.svc \
  --region us-east-1
```

This:

1. Creates **DynamoDB entry** in `eks-dx-clusters`:
   ```json
   {
     "clusterName": "my-k3s",
     "issuer": "https://kubernetes.default.svc",
     "jwks": null,
     "registeredAt": "2026-04-26T22:00:00Z"
   }
   ```
   (`jwks` is null until the first sync runs)

2. Creates **IAM instance profile** `eks-dx-cluster-my-k3s` with the minimal DynamoDB UpdateItem policy (JWKS push only)

3. Creates **IAM role** `eks-dx-cluster-my-k3s-webhook` for the webhook pod:
   ```json
   {
     "Statement": [{
       "Effect": "Allow",
       "Action": ["dynamodb:GetItem", "dynamodb:Query"],
       "Resource": "arn:aws:dynamodb:*:*:table/eks-dx-associations",
       "Condition": {
         "ForAllValues:StringLike": {
           "dynamodb:LeadingKeys": ["CLUSTER#my-k3s"]
         }
       }
     }]
   }
   ```

4. Creates **pod identity association** for the webhook itself:
   ```
   my-k3s:kube-system:eks-pod-identity-webhook → eks-dx-cluster-my-k3s-webhook
   ```

5. Installs the **JWKS sync** systemd timer on the host

6. Outputs:
   - Lambda endpoint URL (for `--endpoint` flag)
   - Instance profile name (for EC2)
   - First JWKS sync triggered immediately

### Bootstrap Order

```
1. eks-dx register → creates DynamoDB entries + IAM roles + systemd timer
2. JWKS sync runs → pushes cluster's public keys to DynamoDB
3. EKS Pod Identity Agent starts → points to Lambda endpoint
4. Webhook starts → gets DynamoDB read creds via Pod Identity (dogfooding)
5. Application pods start → webhook injects env vars → agent → Lambda → STS
```

Step 4 is the dogfooding moment: the webhook's own credentials come through the Pod Identity system it's part of.

## DynamoDB Schema

### Table: `eks-dx-clusters`

| PK (clusterName) | issuer | jwks | jwksSyncedAt | registeredAt |
|-------------------|--------|------|--------------|--------------|
| `my-k3s` | `https://kubernetes.default.svc` | `{"keys":[...]}` | `2026-04-26T22:00:00Z` | `2026-04-26T22:00:00Z` |

### Table: `eks-dx-associations`

| PK | SK | roleArn | createdAt |
|----|-----|---------|-----------|
| `CLUSTER#my-k3s` | `default#my-app` | `arn:aws:iam::123456789012:role/my-app` | `2026-04-26T22:00:00Z` |
| `CLUSTER#my-k3s` | `ci-cd#builder` | `arn:aws:iam::123456789012:role/ci-builder` | `2026-04-26T22:00:00Z` |

### IAM Scoping per Cluster

Each cluster's roles use **DynamoDB leading key conditions**:

```json
{
  "Condition": {
    "ForAllValues:StringLike": {
      "dynamodb:LeadingKeys": ["CLUSTER#my-k3s"]
    }
  }
}
```

- Cluster A cannot read Cluster B's associations
- Instance profile can only write JWKS for its own cluster
- No cross-cluster data leakage

## Lambda Auth Service

### Endpoint

```
POST /clusters/{clusterName}/assets
Body: {"token": "<jwt>"}
```

### Flow

1. Look up cluster in `eks-dx-clusters` → get issuer + JWKS (from DynamoDB, pushed by cluster)
2. Validate token:
   - Parse JWKS from DynamoDB (cached in Lambda memory with TTL)
   - Verify JWT signature (RS256), audience (`pods.eks.amazonaws.com`), expiry, issuer
   - Extract namespace + service account from claims
3. Look up association in `eks-dx-associations` → get roleArn
4. STS AssumeRole with session tags → return temporary credentials

### Lambda IAM Role

```json
{
  "Statement": [
    {
      "Sid": "ReadClusterAndAssociationData",
      "Effect": "Allow",
      "Action": ["dynamodb:GetItem", "dynamodb:Query"],
      "Resource": [
        "arn:aws:dynamodb:*:*:table/eks-dx-associations",
        "arn:aws:dynamodb:*:*:table/eks-dx-clusters"
      ]
    },
    {
      "Sid": "AssumeTargetRoles",
      "Effect": "Allow",
      "Action": ["sts:AssumeRole", "sts:TagSession"],
      "Resource": "arn:aws:iam::*:role/eks-dx-pod-*"
    }
  ]
}
```

### Token Validation

The Lambda validates JWTs directly (no Kubernetes TokenReview):

- JWKS is read from DynamoDB (pushed by the cluster's host-level sync agent)
- No network path from Lambda to the cluster required
- JWKS cached in Lambda memory, refreshed from DynamoDB every 5 minutes
- If JWKS is stale (cluster rotated keys), the sync agent pushes new keys within 6 hours

## Webhook (In-Cluster)

Stays in the cluster. Switches from CRD lookup to DynamoDB:

```java
public boolean hasAssociation(String clusterName, String namespace, String serviceAccount) {
    GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
        .tableName("eks-dx-associations")
        .key(Map.of(
            "PK", AttributeValue.fromS("CLUSTER#" + clusterName),
            "SK", AttributeValue.fromS(namespace + "#" + serviceAccount)))
        .build());
    return response.hasItem();
}
```

Gets its AWS credentials via Pod Identity (dogfooding). Its association maps to `eks-dx-cluster-<name>-webhook` role which has scoped DynamoDB read-only access.

## CLI

```bash
# Register a cluster (run from the EC2 host)
eks-dx register --cluster-name my-k3s --issuer https://kubernetes.default.svc --region us-east-1

# Manage associations (run from anywhere with AWS credentials)
eks-dx create  --cluster-name my-k3s --service-account default:my-app --role-arn arn:aws:iam::...:role/my-app
eks-dx list    --cluster-name my-k3s
eks-dx delete  --cluster-name my-k3s --service-account default:my-app

# Deregister
eks-dx deregister --cluster-name my-k3s
```

## IAM Role Summary

| Role | Attached to | Permissions | Scope |
|------|-------------|-------------|-------|
| `eks-dx-cluster-<name>` | EC2 instance profile | `dynamodb:UpdateItem` on `eks-dx-clusters` | Own cluster key only |
| `eks-dx-cluster-<name>-webhook` | Webhook pod (via Pod Identity) | `dynamodb:GetItem/Query` on `eks-dx-associations` | Own cluster associations only |
| `eks-dx-auth-service` | Lambda execution role | `dynamodb:GetItem/Query` on both tables + `sts:AssumeRole` on `eks-dx-pod-*` | All clusters (it's the central service) |
| `eks-dx-pod-*` | Target roles assumed by pods | Application-specific (S3, DynamoDB, etc.) | Whatever the app needs |

## Cost Estimate (per cluster)

| Resource | Cost |
|----------|------|
| Lambda (1000 requests/day) | ~$0.20/month |
| API Gateway | ~$3.50/month |
| DynamoDB on-demand (1000 reads/day) | ~$0.04/month |
| JWKS sync (4 DynamoDB writes/day) | ~$0.00/month |
| **Total** | **~$4/month** |

vs. EKS control plane: $73/month

## Migration Path

| Component | Current (in-cluster) | Target (Lambda + DynamoDB) |
|-----------|---------------------|---------------------------|
| Auth proxy | Kubernetes Deployment | Lambda + API Gateway |
| Associations | CRD resources | DynamoDB table |
| Token validation | Kubernetes TokenReview | Direct JWT/JWKS from DynamoDB |
| JWKS distribution | Not needed (TokenReview) | Host-level sync → DynamoDB |
| Webhook | CRD lookup | DynamoDB GetItem |
| CLI | CRD CRUD | DynamoDB CRUD |
| Agent config | `--endpoint http://...:8080` | `--endpoint https://...` |

## Open Questions

1. **Multi-region**: Deploy Lambda + DynamoDB per-region, or use DynamoDB Global Tables for multi-region clusters?
2. **Agent authentication to Lambda**: The token itself authenticates the request (Lambda validates it). No additional auth needed — same as real EKS.
3. **CRD backward compatibility**: Keep CRD support as an offline/disconnected fallback mode?
4. **JWKS rotation urgency**: 6-hour sync interval means up to 6h delay if cluster rotates SA keys. Acceptable? Or add a manual `eks-dx sync-jwks` command?
5. **Webhook bootstrap**: On first boot, the webhook needs Pod Identity to get DynamoDB creds, but Pod Identity needs the webhook to inject env vars. Solution: the webhook's own pod should have env vars injected statically (not via webhook) or use the instance profile as a bootstrap fallback.
