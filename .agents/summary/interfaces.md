# Interfaces

## Lambda API (API Gateway REST v1)

Base URL: `https://{api-id}.execute-api.{region}.amazonaws.com/prod` (or custom domain `https://eks-dx.codriverlabs.ai`)

### Credential Exchange — No Auth (token validated by Lambda)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/clusters/{name}/assets` | Exchange SA token for AWS credentials |

**Request:**
```json
{ "token": "eyJ..." }
```

**Response 200:**
```json
{
  "credentials": {
    "accessKeyId": "ASIA...",
    "secretAccessKey": "...",
    "sessionToken": "...",
    "expiration": 1234567890
  },
  "assumedRoleUser": { "arn": "arn:aws:iam::...", "assumeRoleId": "ns-sa" },
  "podIdentityAssociation": { "associationArn": "...", "associationId": "assoc-..." },
  "subject": { "namespace": "default", "serviceAccount": "my-sa" },
  "audience": "pods.eks.amazonaws.com"
}
```

### Cluster Management — IAM Auth (SigV4)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/clusters` | Register a cluster |
| GET | `/clusters` | List all clusters |
| GET | `/clusters/{name}` | Describe a cluster |
| DELETE | `/clusters/{name}` | Deregister a cluster |
| PUT | `/clusters/{name}/jwks` | Refresh JWKS for a cluster |

**Register cluster request:**
```json
{ "name": "my-cluster", "issuer": "https://...", "jwks": "{\"keys\":[...]}" }
```

### Association Management — Mixed Auth

`POST` and `DELETE` require IAM auth. `GET` endpoints accept either IAM or a Bearer SA token (validated by `WebhookAuthFilter` for the webhook SA).

| Method | Path | Description |
|--------|------|-------------|
| POST | `/clusters/{name}/pod-identity-associations` | Create association |
| GET | `/clusters/{name}/pod-identity-associations` | List associations (query: `namespace`, `serviceAccount`) |
| GET | `/clusters/{name}/pod-identity-associations/{id}` | Describe association |
| DELETE | `/clusters/{name}/pod-identity-associations/{id}` | Delete association |

**Create association request:**
```json
{ "namespace": "default", "serviceAccount": "my-sa", "roleArn": "arn:aws:iam::123:role/eks-dx-pod-my-role" }
```

### Error Response Format

All errors follow:
```json
{ "__type": "ErrorCode", "message": "Human-readable message" }
```

Common codes: `InvalidParameterException` (400), `AccessDeniedException` (403), `NotFoundException` (404), `ConflictException` (409), `InternalServerException` (500).

---

## In-Cluster Proxy API (eks-dx-auth-proxy)

Exposes the same credential exchange endpoint as the Lambda, plus a compatibility alias:

| Method | Path | Notes |
|--------|------|-------|
| POST | `/clusters/{name}/assets` | Primary endpoint (used by Pod Identity Agent) |
| POST | `/clusters/{name}/assume-role-for-pod-identity` | Alias |
| GET | `/health/live` | Liveness probe |
| GET | `/health/ready` | Readiness probe |
| GET | `/metrics` | Prometheus metrics (Micrometer) |

---

## Webhook Endpoint (eks-dx-pod-identity-webhook)

| Method | Path | Notes |
|--------|------|-------|
| POST | `/mutate` | Kubernetes MutatingAdmissionWebhook handler |

Receives `AdmissionReview` (Kubernetes API), returns JSON patch.

---

## CLI Interface (eks-dx)

```
eks-dx configure [--endpoint URL] [--region REGION]

eks-dx cluster create --name NAME [--issuer URL] [--jwks JSON]
eks-dx cluster describe NAME
eks-dx cluster list
eks-dx cluster update NAME [--refresh-jwks]
eks-dx cluster delete NAME

eks-dx association create --cluster NAME --namespace NS --service-account SA --role-arn ARN
eks-dx association describe --cluster NAME --id ID
eks-dx association list --cluster NAME [--namespace NS] [--service-account SA]
eks-dx association delete --cluster NAME --id ID
```

`create cluster` auto-discovers issuer and JWKS from the kube-apiserver OIDC endpoints if not provided explicitly.

---

## Configuration Interfaces

### eks-dx-lambda (application.properties / env vars)

| Property | Env Var | Default |
|----------|---------|---------|
| `eks-dx.clusters-table` | `EKS_DX_CLUSTERS_TABLE` | `eks-dx-clusters` |
| `eks-dx.associations-table` | `EKS_DX_ASSOCIATIONS_TABLE` | `eks-dx-associations` |
| `aws.sts.session-duration` | — | `PT1H` |

### eks-dx-auth-proxy (application.properties / env vars)

| Property | Env Var | Default |
|----------|---------|---------|
| `eks-dx.endpoint` | `EKS_DX_ENDPOINT` | `https://eks-dx.codriverlabs.ai` |

### eks-dx-pod-identity-webhook (application.properties)

| Property | Description |
|----------|-------------|
| `eks-dx.endpoint` | Lambda API Gateway URL |
| `eks.cluster-name` | Name of the cluster this webhook serves |

### CLI (~/.eks-dx/config)

| Key | Env Var | Default |
|-----|---------|---------|
| `endpoint` | `EKS_DX_ENDPOINT` | `https://eks-dx.codriverlabs.ai` |
| `region` | `AWS_REGION` | `us-east-1` |
