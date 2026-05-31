# Interfaces

## REST API

### Credential Exchange (no API Gateway auth)
```
POST /clusters/{name}/assets    # Token in body → AWS credentials
```

### Cluster Management (IAM SigV4)
```
POST   /clusters
GET    /clusters
GET    /clusters/{name}
DELETE /clusters/{name}
```

### Association Management (IAM SigV4 for write, open for read)
```
POST   /clusters/{name}/pod-identity-associations
GET    /clusters/{name}/pod-identity-associations
GET    /clusters/{name}/pod-identity-associations/{id}
DELETE /clusters/{name}/pod-identity-associations/{id}
```

### Tenant Provisioning (IAM SigV4)
```
POST   /tenants              # Create tenant
GET    /tenants/{id}         # Get state
DELETE /tenants/{id}         # Deprovision
GET    /tenants/{id}/stream  # SSE progress (Function URL)
```

## Authentication Model

| Endpoint | Auth Method | Validated By |
|----------|-------------|--------------|
| `POST /clusters/{name}/assets` | Pod SA token in body | Lambda JWKS validation |
| Management endpoints | IAM SigV4 | API Gateway |
| Association GET | Open (optional Bearer) | `WebhookAuthFilter` |
| Webhook → Lambda | Bearer SA token | `WebhookAuthFilter` (audience: `eks-dx.codriverlabs.ai`) |
| Pod SA tokens | Projected volume | Audience: `pods.eks.amazonaws.com` |

## Internal Interfaces

### Auth Proxy → Lambda
HTTP POST to API Gateway with pod token in JSON body.

### Webhook → Lambda
HTTP GET to `/clusters/{name}/pod-identity-associations?namespace=X&serviceAccount=Y` with Bearer SA token.

### CLI → API Gateway
All requests signed with AWS SigV4 using caller's credentials.

### Tenant SSE Stream
Function URL with `RESPONSE_STREAM` invoke mode. Returns newline-delimited JSON events with `phase`, `progress`, `publicIp` fields.
