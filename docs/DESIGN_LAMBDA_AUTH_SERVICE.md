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
│  │   • Validates token (JWKS from cluster's registered issuer)  │    │
│  │   • Looks up association in DynamoDB                          │    │
│  │   • STS AssumeRole → returns temporary credentials           │    │
│  └──────────────┬───────────────────────┬───────────────────────┘    │
│                 │                       │                            │
│  ┌──────────────▼──────┐  ┌─────────────▼──────┐                    │
│  │ DynamoDB             │  │ STS                 │                    │
│  │ eks-dx-associations  │  │ AssumeRole          │                    │
│  │                      │  │ + TagSession         │                    │
│  │ PK: CLUSTER#<name>   │  └────────────────────┘                    │
│  │ SK: <ns>#<sa>        │                                            │
│  │                      │                                            │
│  │ eks-dx-clusters      │                                            │
│  │ PK: <clusterName>    │                                            │
│  │ issuer, jwksUri, ... │                                            │
│  └──────────────────────┘                                            │
│                                                                      │
│  API Gateway (HTTPS endpoint for Lambda)                             │
│  https://eks-dx.{region}.amazonaws.com (or custom domain)            │
└──────────────────────────────────────────────────────────────────────┘
          ▲                              ▲
          │                              │
          │ POST /clusters/*/assets      │ DynamoDB GetItem
          │ (token exchange)             │ (association check)
          │                              │
┌─────────┼──────────────────────────────┼─────────────────────────────┐
│  k3s / microk8s / EKS-D cluster        │                             │
│         │                              │                             │
│  ┌──────┴──────────────────┐  ┌────────┴────────────────────┐       │
│  │ EKS Pod Identity Agent  │  │ eks-pod-identity-webhook    │       │
│  │ --endpoint <lambda-url> │  │ queries DynamoDB for        │       │
│  │ 169.254.170.23:80       │  │ association existence       │       │
│  └─────────────────────────┘  └─────────────────────────────┘       │
│                                                                      │
│  IAM role: eks-dx-cluster-<clusterName>                              │
│    • dynamodb:GetItem on eks-dx-associations (condition: PK match)   │
│    • dynamodb:Query  on eks-dx-associations (condition: PK match)    │
└──────────────────────────────────────────────────────────────────────┘
```

## Cluster Registration

### Registration Flow

```bash
eks-dx register \
  --cluster-name my-k3s \
  --issuer https://kubernetes.default.svc \
  --jwks-uri https://<node-ip>:6443/openid/v1/jwks \
  --region us-east-1
```

This creates:

1. **DynamoDB entry** in `eks-dx-clusters` table:
   ```json
   {
     "clusterName": "my-k3s",
     "issuer": "https://kubernetes.default.svc",
     "jwksUri": "https://<node-ip>:6443/openid/v1/jwks",
     "registeredAt": "2026-04-26T22:00:00Z"
   }
   ```

2. **IAM role** `eks-dx-cluster-my-k3s` with scoped policy:
   ```json
   {
     "Version": "2012-10-17",
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

3. **Outputs** the Lambda endpoint URL and IAM role ARN for the cluster to use.

### What the Cluster Gets

- Lambda endpoint URL → passed to EKS Pod Identity Agent via `--endpoint`
- IAM role ARN → attached to EC2 instance profile (or used via IRSA)
- The role can ONLY read associations for its own cluster name (DynamoDB leading key condition)

## DynamoDB Schema

### Single-Table Design

**Table: `eks-dx-associations`**

| PK | SK | roleArn | createdAt |
|----|-----|---------|-----------|
| `CLUSTER#my-k3s` | `default#my-app` | `arn:aws:iam::123456789012:role/my-app` | `2026-04-26T22:00:00Z` |
| `CLUSTER#my-k3s` | `ci-cd#builder` | `arn:aws:iam::123456789012:role/ci-builder` | `2026-04-26T22:00:00Z` |
| `CLUSTER#prod-eks-d` | `default#api` | `arn:aws:iam::123456789012:role/prod-api` | `2026-04-26T22:00:00Z` |

- **PK**: `CLUSTER#<clusterName>` — enables IAM condition scoping per cluster
- **SK**: `<namespace>#<serviceAccount>` — unique within a cluster

**Table: `eks-dx-clusters`**

| PK (clusterName) | issuer | jwksUri | iamRoleArn | registeredAt |
|-------------------|--------|---------|------------|--------------|
| `my-k3s` | `https://kubernetes.default.svc` | `https://1.2.3.4:6443/openid/v1/jwks` | `arn:aws:iam::...:role/eks-dx-cluster-my-k3s` | `2026-04-26T22:00:00Z` |

### IAM Scoping (Best Practice)

Each cluster's IAM role uses a **DynamoDB leading key condition** to restrict access:

```json
{
  "Condition": {
    "ForAllValues:StringLike": {
      "dynamodb:LeadingKeys": ["CLUSTER#my-k3s"]
    }
  }
}
```

This means:
- `my-k3s` cluster can only read associations with PK `CLUSTER#my-k3s`
- It cannot read `CLUSTER#prod-eks-d` associations
- No cross-cluster data leakage even if the cluster is compromised

## Lambda Auth Service

### Endpoint

```
POST /clusters/{clusterName}/assets
Body: {"token": "<jwt>"}
```

Compatible with the EKS Pod Identity Agent's `--endpoint` flag.

### Flow

1. Look up cluster registration in `eks-dx-clusters` → get issuer + JWKS URI
2. Validate token:
   - Fetch JWKS from registered URI (cached)
   - Verify JWT signature, audience (`pods.eks.amazonaws.com`), expiry
   - Extract namespace + service account from claims
3. Look up association in `eks-dx-associations` → get roleArn
4. STS AssumeRole with session tags → return temporary credentials

### Lambda IAM Role

```json
{
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["dynamodb:GetItem", "dynamodb:Query"],
      "Resource": [
        "arn:aws:dynamodb:*:*:table/eks-dx-associations",
        "arn:aws:dynamodb:*:*:table/eks-dx-clusters"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["sts:AssumeRole", "sts:TagSession"],
      "Resource": "arn:aws:iam::*:role/eks-dx-pod-*"
    }
  ]
}
```

### Token Validation Change

Currently the proxy uses Kubernetes TokenReview (requires in-cluster access). The Lambda version must validate JWTs directly:

- Fetch JWKS from the cluster's registered `jwksUri`
- Verify RS256 signature against the cluster's public keys
- Validate `aud: pods.eks.amazonaws.com`, `exp`, `iss`
- This is what the real EKS Auth Service does

**Implication**: the cluster's JWKS endpoint must be reachable from Lambda. Options:
- Public JWKS endpoint (k3s can expose this via ingress)
- JWKS cached in DynamoDB at registration time (simpler, but needs rotation)
- VPC Lambda with VPN/peering to the cluster network

## Webhook (In-Cluster)

The webhook stays in the cluster but switches from CRD lookup to DynamoDB:

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

The webhook's IAM role (from cluster registration) can only query its own cluster's associations.

## CLI Changes

```bash
# Register a cluster
eks-dx register --cluster-name my-k3s --issuer https://kubernetes.default.svc --jwks-uri https://...

# Manage associations (DynamoDB instead of CRDs)
eks-dx create  --cluster-name my-k3s --service-account default:my-app --role-arn arn:aws:iam::...:role/my-app
eks-dx list    --cluster-name my-k3s
eks-dx delete  --cluster-name my-k3s --service-account default:my-app

# Deregister a cluster
eks-dx deregister --cluster-name my-k3s
```

## Migration Path

| Component | Current (in-cluster) | Target (Lambda + DynamoDB) |
|-----------|---------------------|---------------------------|
| Auth proxy | Kubernetes Deployment | Lambda + API Gateway |
| Associations | CRD resources | DynamoDB table |
| Token validation | Kubernetes TokenReview | Direct JWT/JWKS validation |
| Webhook | CRD lookup | DynamoDB GetItem |
| CLI | CRD CRUD | DynamoDB CRUD |
| Agent config | `--endpoint http://eks-auth-proxy.kube-system:8080` | `--endpoint https://eks-dx.region.amazonaws.com` |

## Cost Estimate (per cluster)

| Resource | Cost |
|----------|------|
| Lambda (1000 requests/day) | ~$0.20/month |
| API Gateway | ~$3.50/month |
| DynamoDB on-demand (1000 reads/day) | ~$0.04/month |
| **Total** | **~$4/month** |

vs. current EKS control plane: $73/month

## Open Questions

1. **JWKS reachability**: How does Lambda reach the cluster's JWKS endpoint? Public ingress, cached at registration, or VPC peering?
2. **Multi-region**: Should the Lambda + DynamoDB be deployed per-region or global (DynamoDB Global Tables)?
3. **Cluster authentication**: How does the agent authenticate to the Lambda? API key, IAM SigV4, or the token itself is sufficient?
4. **CRD backward compatibility**: Keep CRD support as an offline/disconnected mode?
