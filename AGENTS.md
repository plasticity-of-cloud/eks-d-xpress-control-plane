# AGENTS.md

## Directory Overview

Multi-module Quarkus + CDK project. Brings EKS Pod Identity (`AssumeRoleForPodIdentity`) to non-EKS Kubernetes clusters via a serverless Lambda backend.

```
eks-dx-lambda/           # Lambda: credential exchange + cluster/association management API
eks-dx-auth-proxy/       # In-cluster proxy: Kubernetes TokenReview fast-fail + Lambda forwarding
eks-dx-pod-identity-webhook/  # Admission webhook: injects env vars + projected token volume into pods
eks-dx-cli/              # Native CLI (GraalVM): cluster + association management
infra/                   # CDK infrastructure (Java, alternative to sam.yaml)
sam.yaml                 # SAM template (Lambda + DynamoDB + API Gateway)
docs/user_guides/        # Setup/teardown shell scripts for k3s on EC2
.agents/summary/         # Generated documentation (index.md is the knowledge base entry point)
```

## Key Entry Points

| What | File |
|------|------|
| Credential exchange endpoint | `eks-dx-lambda/.../resource/EksAuthResource.java` |
| JWT/JWKS validation (5-min cache) | `eks-dx-lambda/.../service/JwksTokenValidationService.java` |
| DynamoDB cluster CRUD | `eks-dx-lambda/.../service/DynamoDbClusterService.java` |
| DynamoDB association CRUD + IAM role validation | `eks-dx-lambda/.../service/DynamoDbAssociationService.java` |
| STS AssumeRole with session tags | `eks-dx-lambda/.../service/AwsCredentialService.java` |
| Webhook auth filter (Bearer token on GET associations) | `eks-dx-lambda/.../auth/WebhookAuthFilter.java` |
| In-cluster proxy resource | `eks-dx-auth-proxy/.../resource/EksAuthAgentResource.java` |
| Kubernetes TokenReview | `eks-dx-auth-proxy/.../service/TokenValidationService.java` |
| Pod mutation logic | `eks-dx-pod-identity-webhook/.../PodIdentityMutator.java` |
| Association lookup (webhook → Lambda) | `eks-dx-pod-identity-webhook/.../LambdaAssociationLookup.java` |
| CLI entry point | `eks-dx-cli/.../EksDxCommand.java` |
| CLI HTTP client + SigV4 signing | `eks-dx-cli/.../util/EksDxApiClient.java`, `AwsSigV4Signer.java` |
| CLI config (~/.eks-dx/config) | `eks-dx-cli/.../config/EksDxConfig.java` |
| CDK stack | `infra/.../EksDxStack.java` |

## Authentication Model

- `POST /clusters/{name}/assets` — **no API Gateway auth**; token is in the request body, validated by Lambda via JWKS
- Management endpoints (`/clusters`, JWKS PUT, association POST/DELETE) — **IAM SigV4**
- Association GET endpoints — open at API Gateway; `WebhookAuthFilter` optionally validates a Bearer SA token when present
- Webhook → Lambda — Bearer SA token with audience `eks-dx.codriverlabs.ai`
- Pod SA tokens — audience `pods.eks.amazonaws.com`

## Repo-Specific Patterns

**JWKS caching**: `JwksTokenValidationService` caches `JWTAuthContextInfo` per `clusterName|audience` in a `ConcurrentHashMap` with a 5-minute TTL. Cache is per Lambda instance.

**DynamoDB key design**: Associations use composite key `PK=CLUSTER#<name>` / `SK=<namespace>#<serviceAccount>`. Role ARN lookup is O(1) `GetItem`. `describeAssociation` by ID requires a `Scan` with filter (no GSI on `associationId`).

**Role naming constraint**: STS `AssumeRole` is scoped to `arn:aws:iam::*:role/eks-dx-pod-*`. Pod identity IAM roles must match this prefix.

**Dual `TokenClaims`**: `eks-dx-lambda` uses a Java `record`; `eks-dx-auth-proxy` has its own class. They are not shared — modules are independently deployable.

**CLI config resolution order**: CLI flag → `EKS_DX_ENDPOINT` / `AWS_REGION` env vars → `~/.eks-dx/config` → defaults (`https://eks-dx.codriverlabs.ai`, `us-east-1`).

**`create cluster` auto-discovery**: `CreateClusterCommand` fetches issuer from `/.well-known/openid-configuration` and JWKS from `/openid/v1/jwks` on the kube-apiserver if not provided explicitly.

**Webhook idempotency**: `PodIdentityMutator` checks for existing env vars and volumes before injecting to avoid duplicates on re-admission.

**CDK vs SAM**: CDK (`EksDxStack`) adds PITR and `RemovalPolicy.RETAIN` on DynamoDB tables; SAM does not. SAM explicitly grants `iam:GetRole`; CDK does not — CDK deployments will fail at association creation without it.

## Integration Tests

`DynamoDbIntegrationTest` requires DynamoDB Local on port 18000. Run with `-Dintegration.dynamodb=true`. The test creates tables if they don't exist.

## Custom Instructions
<!-- This section is for human and agent-maintained operational knowledge.
     Add repo-specific conventions, gotchas, and workflow rules here.
     This section is preserved exactly as-is when re-running codebase-summary. -->
