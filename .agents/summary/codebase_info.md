# Codebase Information

## Project
- **Name**: EKS-DX Control Plane
- **Group**: ai.codriverlabs
- **Version**: 1.0.0-SNAPSHOT
- **License**: MIT

## Technology Stack
- **Language**: Java 25
- **Framework**: Quarkus 3.33.1 LTS
- **Build**: Maven (multi-module)
- **Native**: GraalVM/Mandrel 25 (tenant-service + CLI)
- **Infrastructure**: AWS CDK 2.256.1 (Java)
- **Runtime**: AWS Lambda (SnapStart for JVM, native for tenant-service)
- **Storage**: DynamoDB (PAY_PER_REQUEST)
- **Auth**: STS AssumeRole, JWKS/JWT validation (jose4j)

## Modules (8)
| Module | Purpose | Deployment |
|--------|---------|-----------|
| eks-dx-model | Shared TokenClaims record | Library (jar) |
| eks-dx-credential-service | Credential exchange (hot path) | Lambda, SnapStart |
| eks-dx-mgmt-service | Cluster/association CRUD | Lambda, JVM |
| eks-dx-tenant-service | Tenant provisioning + lifecycle | Lambda, GraalVM native arm64 |
| eks-dx-auth-proxy | In-cluster proxy (TokenReview + forwarding) | Container (Kubernetes) |
| eks-dx-pod-identity-webhook | Admission webhook (env/volume injection) | Container (Kubernetes) |
| eks-dx-cli | Native CLI for management | GraalVM native binary |
| infra | CDK infrastructure stack | CDK deploy |

## Key Metrics
- **Source files**: 72 prioritized Java files
- **Total LOC**: ~5,921 (application code)
- **Test coverage**: Unit tests for all modules, integration tests for DynamoDB
- **CI**: GitHub Actions (build, test, deploy)
