# AGENTS.md

## Directory Overview and Component Map

This is a **multi-module Quarkus + CDK project** providing EKS Pod Identity authentication for k3s, microk8s, and EKS-D clusters via a serverless Lambda backend.

```
├── eks-dx-lambda/           # Lambda service (credential exchange + management API)
│   └── src/main/java/cloud/plasticity/eksdx/lambda/
│       ├── auth/            # WebhookAuthFilter (SA token audience check)
│       ├── model/           # TokenClaims record
│       ├── resource/        # REST endpoints (EksAuthResource, ClusterResource, AssociationResource)
│       └── service/         # DynamoDbClusterService, DynamoDbAssociationService,
│                            # JwksTokenValidationService, AwsCredentialService
├── eks-dx-cli/              # Native CLI for cluster + association management
│   └── src/main/java/cloud/plasticity/eksdx/cli/
│       ├── cluster/         # CreateCluster, Describe, List, Update, Delete commands
│       ├── association/     # CreateAssociation, Describe, List, Delete commands
│       ├── config/          # ConfigureCommand, EksDxConfig (~/.eks-dx/config)
│       └── util/            # EksDxApiClient (JDK HttpClient), AwsSigV4Signer
├── eks-auth-proxy/          # Simplified in-cluster proxy (TokenReview + Lambda forwarding)
│   └── src/main/java/cloud/plasticity/eksauth/
│       ├── resource/        # EksAuthAgentResource (POST /clusters/{name}/assets)
│       └── service/         # TokenValidationService (K8s TokenReview),
│                            # LambdaForwardingService (JDK HttpClient → Lambda)
├── eks-pod-identity-webhook/  # Kubernetes admission webhook
│   └── src/main/java/cloud/plasticity/webhook/
│       ├── WebhookEndpoint.java        # POST /mutate
│       ├── PodIdentityMutator.java     # Injects env vars + projected token volume
│       └── LambdaAssociationLookup.java # Queries Lambda API for associations
├── infra/                   # CDK infrastructure (alternative to SAM)
│   └── src/main/java/cloud/plasticity/eksdx/infra/
│       ├── InfraApp.java    # CDK app entry point
│       └── EksDxStack.java  # Lambda, DynamoDB, API Gateway, CloudWatch alarms
├── eks-pod-identity-crd/    # DEPRECATED — CRD model (replaced by DynamoDB)
├── eks-d-auth-cli/          # DEPRECATED — old CLI (replaced by eks-dx-cli)
├── sam.yaml                 # SAM template (Lambda + DynamoDB + API Gateway)
└── .github/workflows/       # CI + Release pipelines
```

### Key Entry Points

| Component | File | Purpose |
|-----------|------|---------|
| **Credential Exchange** | `eks-dx-lambda/.../EksAuthResource.java` | POST /clusters/{name}/assets — JWKS validation + STS AssumeRole |
| **Cluster Management** | `eks-dx-lambda/.../ClusterResource.java` | CRUD for cluster registration + JWKS storage |
| **Association Management** | `eks-dx-lambda/.../AssociationResource.java` | CRUD for pod identity associations |
| **Token Validation** | `eks-dx-lambda/.../JwksTokenValidationService.java` | jose4j JWKS-based JWT validation |
| **In-Cluster Proxy** | `eks-auth-proxy/.../EksAuthAgentResource.java` | TokenReview fast-fail + Lambda forwarding |
| **Webhook** | `eks-pod-identity-webhook/.../WebhookEndpoint.java` | Admission controller for pod mutation |
| **CLI** | `eks-dx-cli/.../EksDxCommand.java` | Top-level picocli command |

## Authentication Flow

```
Pod → EKS Pod Identity Agent → eks-auth-proxy (in-cluster)
  │
  ├─ 1. TokenReview (fast-fail — K8s API validates JWT signature)
  │
  └─ 2. Forward to eks-dx-lambda (Lambda via API Gateway)
       │
       ├─ 3. JWKS validation (jose4j, DynamoDB-cached JWKS)
       ├─ 4. Association lookup (DynamoDB: CLUSTER#name / namespace#sa)
       ├─ 5. STS AssumeRole (with session tags from token claims)
       └─ 6. Return temporary AWS credentials
```

## Build System

### Lambda (SAM)
```bash
mvn -pl eks-dx-lambda package -DskipTests    # Build function.zip
sam validate                                   # Validate template
sam deploy --guided                            # Deploy to AWS
```

### Lambda (CDK)
```bash
mvn -pl eks-dx-lambda package -DskipTests
cd infra && cdk deploy
```

### CLI
```bash
mvn -pl eks-dx-cli package -DskipTests        # JVM uber-jar
mvn -pl eks-dx-cli package -Pnative -DskipTests  # GraalVM native binary
java -jar eks-dx-cli/target/*-runner.jar --help
```

### Proxy + Webhook (container images)
```bash
mvn -pl eks-auth-proxy package -DskipTests \
  -Dquarkus.container-image.build=true
mvn -pl eks-pod-identity-webhook package -DskipTests \
  -Dquarkus.container-image.build=true
```

## Testing

```bash
# Unit tests (all modules, no external deps)
mvn test

# Integration tests (requires DynamoDB Local on port 18000)
docker run -d -p 18000:8000 public.ecr.aws/aws-dynamodb-local/aws-dynamodb-local:latest
mvn -pl eks-dx-lambda test -Dtest=DynamoDbIntegrationTest -Dintegration.dynamodb=true
```

### Test Coverage

| Module | Unit Tests | Integration Tests |
|--------|-----------|-------------------|
| eks-dx-lambda | 123 | 16 (DynamoDB Local) |
| eks-dx-cli | 41 | — |
| eks-auth-proxy | 18 | — |
| eks-pod-identity-webhook | 10 | — |

## Configuration

### Lambda (eks-dx-lambda)
| Property | Default | Description |
|----------|---------|-------------|
| `eks-dx.clusters-table` | `eks-dx-clusters` | DynamoDB clusters table |
| `eks-dx.associations-table` | `eks-dx-associations` | DynamoDB associations table |
| `aws.sts.session-duration` | `PT1H` | STS session duration |

### CLI (eks-dx-cli)
Resolution order: CLI flag → env var → `~/.eks-dx/config` → default.
```bash
eks-dx configure --endpoint https://xxx.execute-api.us-east-1.amazonaws.com --region us-east-1
```

### Proxy (eks-auth-proxy)
| Variable | Description |
|----------|-------------|
| `EKS_DX_ENDPOINT` | Lambda API Gateway endpoint (required) |

### Webhook (eks-pod-identity-webhook)
| Variable | Description |
|----------|-------------|
| `EKS_CLUSTER_NAME` | Cluster name for association lookups |
| `EKS_DX_ENDPOINT` | Lambda API Gateway endpoint |

## Domain and Naming Convention

| Context | Convention | Example |
|---------|-----------|---------|
| Java packages | `cloud.plasticity.*` | `cloud.plasticity.eksdx.lambda.service` |
| Maven groupId | `cloud.plasticity` | `<groupId>cloud.plasticity</groupId>` |
| CLI config | `~/.eks-dx/config` | endpoint, region |
| Container images | `plasticity.cloud/` prefix or ECR | `plasticity.cloud/eks-auth-proxy` |
| API Gateway | `eks-dx.plasticity.cloud` | Custom domain (optional) |

## Infrastructure

### SAM (`sam.yaml`)
- Lambda (Java 21, SnapStart, 512MB)
- DynamoDB tables (PAY_PER_REQUEST)
- API Gateway (IAM auth on management endpoints, open on /assets)
- CloudWatch alarms (Lambda errors, throttles, p99 duration, DynamoDB throttles)
- Optional custom domain

### CDK (`infra/`)
- Same resources as SAM, plus PITR on DynamoDB
- REST API v1 with IAM auth
- `cdk deploy` from `infra/` directory
