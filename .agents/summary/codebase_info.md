# Codebase Info

## Project Identity
- **Name**: EKS-DX Control Plane (`aws-eks-auth-parent`)
- **Version**: 1.0.0-SNAPSHOT
- **Group**: `ai.codriverlabs`
- **Purpose**: Serverless service that brings EKS Pod Identity (`AssumeRoleForPodIdentity`) to non-EKS Kubernetes distributions (k3s, microk8s, EKS-D)

## Technology Stack
| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Quarkus 3.20.3 |
| Build | Maven (multi-module) |
| Serverless | AWS Lambda (SnapStart, Java 21) |
| Storage | DynamoDB (PAY_PER_REQUEST) |
| API | API Gateway REST v1 |
| JWT | SmallRye JWT / jose4j |
| K8s Client | Fabric8 6.13.4 |
| Webhook | JOSDK Webhooks 3.0.1 |
| CLI | Quarkus PicoCLI + GraalVM native |
| IaC | AWS SAM + AWS CDK 2.180.0 (Java) |
| Metrics | Micrometer + Prometheus |

## Module Structure
```
aws-eks-auth-parent (root pom)
├── eks-dx-lambda          # Lambda: credential exchange + management API
├── eks-dx-auth-proxy      # In-cluster proxy: TokenReview + Lambda forwarding
├── eks-dx-pod-identity-webhook  # K8s admission webhook: pod mutation
├── eks-dx-cli             # Native CLI: cluster + association management
└── infra                  # CDK infrastructure (alternative to SAM)
```

## Deployment Artifacts
- `eks-dx-lambda/target/function.zip` — Lambda deployment package
- Container images for `eks-dx-auth-proxy` and `eks-dx-pod-identity-webhook` (built via Quarkus Jib)
- Native binary for `eks-dx-cli` (GraalVM, `-Pnative`)
- `sam.yaml` — SAM template
- `infra/` — CDK app (`InfraApp.java`)
