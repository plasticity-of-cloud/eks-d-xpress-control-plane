# Service Split Design

Refactoring `eks-dx-lambda` into three independently deployable Lambda functions.

## Motivation

The current `eks-dx-lambda` module handles three unrelated concerns with incompatible traffic profiles, IAM requirements, and scaling needs:

| Concern | Traffic | IAM surface | Latency sensitivity |
|---|---|---|---|
| Credential exchange (`/assets`) | High — every pod credential refresh | DynamoDB read + STS | Critical (pod startup blocked) |
| Cluster/association management | Low — developer CLI + webhook | DynamoDB CRUD + IAM GetRole | Tolerant |
| Tenant provisioning (`/tenants`) | Very low — CI/CD only | EC2 + IAM + Secrets Manager + DynamoDB | Tolerant (async 202) |

Keeping them in one Lambda means:
- The credential exchange hot path shares a SnapStart warm pool with infrequent management calls
- The tenant service's broad EC2/IAM permissions are attached to the function that handles pod credential requests
- A deployment of the management API restarts the credential exchange function

## Three-Lambda Architecture

```
API Gateway (single REST API, IAM auth default)
  │
  ├─ POST /clusters/{name}/assets  (Authorizer: NONE)
  │    └─ eks-dx-credential-service   ← hot path, read-only DynamoDB + STS
  │
  ├─ /clusters/**                  (IAM auth)
  ├─ /clusters/{name}/pod-identity-associations/**  (mixed auth)
  │    └─ eks-dx-mgmt-service          ← CRUD, low traffic
  │
  ├─ /tenants/**                   (IAM auth)
  │    └─ eks-dx-tenant-service        ← async provisioning, EC2/IAM/SM
  │
  └─ GET /tenants/{id}/stream      (Lambda Function URL — RESPONSE_STREAM)
       └─ eks-dx-tenant-service        ← SSE progress stream, 5s poll, 15min timeout
```

---

## eks-dx-credential-service

**Replaces**: credential exchange portion of `eks-dx-lambda`

**Endpoints**:
- `POST /clusters/{name}/assets` — no API Gateway auth; token validated by Lambda via JWKS

**Services retained**:
- `JwksTokenValidationService` — jose4j JWT validation, DynamoDB-cached JWKS (5-min TTL)
- `AwsCredentialService` — STS `AssumeRole` with session tags

**IAM policy (minimal)**:
```json
[
  { "Action": ["dynamodb:GetItem"],
    "Resource": ["arn:aws:dynamodb:*:*:table/eks-dx-clusters",
                 "arn:aws:dynamodb:*:*:table/eks-dx-associations"] },
  { "Action": ["sts:AssumeRole", "sts:TagSession"],
    "Resource": "arn:aws:iam::*:role/eks-dx-pod-*" }
]
```

**SAM config**:
```yaml
EksDxCredentialFunction:
  Type: AWS::Serverless::Function
  Properties:
    CodeUri: eks-dx-credential-service/target/function.zip
    Handler: io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest
    MemorySize: 512
    AutoPublishAlias: live
    SnapStart:
      ApplyOn: PublishedVersions   # dedicated warm pool for hot path
    Events:
      Assets:
        Type: Api
        Properties:
          RestApiId: !Ref EksDxApi
          Path: /clusters/{name}/assets
          Method: POST
          Auth:
            Authorizer: NONE
```

SnapStart is most valuable here — this function is on the critical path for pod startup. A cold start on the management function is acceptable; a cold start on credential exchange is not.

---

## eks-dx-mgmt-service

**Replaces**: management portion of `eks-dx-lambda`

**Endpoints**:
- `POST/GET /clusters` — IAM auth
- `GET/DELETE /clusters/{name}` — IAM auth
- `PUT /clusters/{name}/jwks` — IAM auth
- `POST/DELETE /clusters/{name}/pod-identity-associations` — IAM auth
- `GET /clusters/{name}/pod-identity-associations` — open (webhook uses SA token via `WebhookAuthFilter`)
- `GET /clusters/{name}/pod-identity-associations/{id}` — open

**Services retained**:
- `DynamoDbClusterService`
- `DynamoDbAssociationService`
- `WebhookAuthFilter`

**IAM policy**:
```json
[
  { "Action": ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem",
               "dynamodb:UpdateItem", "dynamodb:Query", "dynamodb:Scan"],
    "Resource": ["arn:aws:dynamodb:*:*:table/eks-dx-clusters",
                 "arn:aws:dynamodb:*:*:table/eks-dx-associations"] },
  { "Action": "iam:GetRole",
    "Resource": "arn:aws:iam::*:role/*" }
]
```

**SAM config**:
```yaml
EksDxMgmtFunction:
  Type: AWS::Serverless::Function
  Properties:
    CodeUri: eks-dx-mgmt-service/target/function.zip
    Handler: io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest
    MemorySize: 256          # lower — infrequent, no SnapStart needed
    Events:
      ClustersPost:   { Type: Api, Properties: { Path: /clusters, Method: POST, ... } }
      ClustersGet:    { Type: Api, Properties: { Path: /clusters, Method: GET, ... } }
      # ... remaining routes
```

---

## eks-dx-tenant-service

**New module** — see [tenant-provisioning.md](tenant-provisioning.md) for full design.

**Endpoints**:
- `POST /tenants` — IAM auth, returns 202
- `GET /tenants/{id}` — IAM auth, returns current state
- `DELETE /tenants/{id}` — IAM auth, deprovisions
- `GET /tenants/{id}/stream` — **Lambda Function URL** (`RESPONSE_STREAM`), SSE, 5s poll

**IAM policy**:
```json
[
  { "Action": ["dynamodb:PutItem", "dynamodb:GetItem", "dynamodb:UpdateItem",
               "dynamodb:DeleteItem"],
    "Resource": "arn:aws:dynamodb:*:*:table/eks-dx-tenants" },
  { "Action": ["ec2:RunInstances", "ec2:TerminateInstances",
               "ec2:CreateKeyPair", "ec2:DeleteKeyPair", "ec2:DescribeInstances"],
    "Resource": "*" },
  { "Action": ["iam:CreateRole", "iam:DeleteRole", "iam:PutRolePolicy",
               "iam:DeleteRolePolicy", "iam:PassRole"],
    "Resource": "arn:aws:iam::*:role/eks-dx-tenant-*" },
  { "Action": ["secretsmanager:CreateSecret", "secretsmanager:DeleteSecret"],
    "Resource": "arn:aws:secretsmanager:*:*:secret:eks-dx/tenant/*" }
]
```

**SAM config**:
```yaml
EksDxTenantFunction:
  Type: AWS::Serverless::Function
  Properties:
    CodeUri: eks-dx-tenant-service/target/function.zip
    Handler: io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest
    MemorySize: 128          # native image, low memory
    Timeout: 900             # 15 min for SSE stream
    FunctionUrlConfig:
      AuthType: AWS_IAM
      InvokeMode: RESPONSE_STREAM   # SSE /stream endpoint
    Events:
      TenantsPost:   { Type: Api, Properties: { Path: /tenants, Method: POST, ... } }
      TenantGet:     { Type: Api, Properties: { Path: /tenants/{id}, Method: GET, ... } }
      TenantDelete:  { Type: Api, Properties: { Path: /tenants/{id}, Method: DELETE, ... } }
      # /tenants/{id}/stream is served via Function URL, NOT API Gateway
```

---

## Module Structure After Split

```
eks-dx-control-plane/
├── eks-dx-credential-service/   # renamed + trimmed from eks-dx-lambda
│   └── service/
│       ├── JwksTokenValidationService
│       └── AwsCredentialService
│
├── eks-dx-mgmt-service/         # renamed + trimmed from eks-dx-lambda
│   └── service/
│       ├── DynamoDbClusterService
│       └── DynamoDbAssociationService
│   └── auth/
│       └── WebhookAuthFilter
│
├── eks-dx-tenant-service/       # new
│   └── service/
│       ├── TenantProvisioningService
│       └── TenantProgressService   # SSE stream
│
├── eks-dx-auth-proxy/           # unchanged
├── eks-dx-pod-identity-webhook/ # unchanged
├── eks-dx-cli/                  # add: create/get/delete tenant commands
└── infra/                       # unchanged (CDK, not primary path)
```

The `eks-dx-lambda` directory is deleted. Both successor modules keep the same Java package root (`ai.codriverlabs.eksdx`) with sub-packages `credential` and `mgmt` respectively.

---

## Shared Code

`TokenClaims` is currently duplicated between `eks-dx-lambda` and `eks-dx-auth-proxy`. After the split, `eks-dx-credential-service` and `eks-dx-auth-proxy` both need it. Options:

- **Keep duplicated** — modules remain independently deployable with no shared compile dependency. Acceptable given the record is small and stable.
- **Extract `eks-dx-model`** — a zero-dependency shared module with `TokenClaims` and other DTOs. Adds a module but eliminates drift risk.

Recommendation: extract `eks-dx-model` when the split is implemented, since you're already touching both modules.

---

## Migration Path

1. Create `eks-dx-credential-service/` — copy credential exchange classes from `eks-dx-lambda`, delete management classes
2. Create `eks-dx-mgmt-service/` — copy management classes from `eks-dx-lambda`, delete credential exchange classes
3. Create `eks-dx-tenant-service/` — new module per [tenant-provisioning.md](tenant-provisioning.md)
4. Update `sam.yaml` — 3 functions, same API Gateway, add Function URL for tenant stream
5. Update `pom.xml` — replace `eks-dx-lambda` module with 3 new modules
6. Update `ARCHITECTURE.md` — replace single Lambda node with 3 nodes
7. Delete `eks-dx-lambda/`
8. Update CI/CD workflows — build and deploy 3 functions independently

Steps 1–3 can be done in parallel. Steps 4–7 are a single atomic commit.

---

## Independent Deployment

After the split, each function can be deployed independently:

```bash
# Deploy only credential service (e.g., after JWKS cache tuning)
sam deploy --parameter-overrides DeployCredential=true

# Deploy only mgmt service (e.g., after association API change)
sam deploy --parameter-overrides DeployMgmt=true

# Deploy only tenant service (e.g., after provisioning logic change)
sam deploy --parameter-overrides DeployTenant=true
```

This requires conditional resource inclusion in `sam.yaml` or separate SAM templates per function with a shared API Gateway imported via `Fn::ImportValue`.
