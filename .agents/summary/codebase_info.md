# Codebase Info

## Project
**eks-d-xpress-control-plane** — Serverless backend bringing EKS Pod Identity to non-EKS Kubernetes clusters (EKS-D via kubeadm).

## Tech Stack
- **Language**: Java 25
- **Framework**: Quarkus 3.35.4 LTS
- **Build**: Maven 3.9+, GraalVM native image (Mandrel)
- **Infrastructure**: AWS CDK (Java)
- **Runtime**: AWS Lambda (provided.al2023 for native, Java 25 for JVM)
- **Storage**: DynamoDB
- **AWS Services**: STS, EC2, IAM, Secrets Manager, DLM, SQS, EventBridge, API Gateway, SSM

## Modules (8)
| Module | Runtime | Purpose |
|--------|---------|---------|
| eks-dx-credential-service | Lambda JVM, SnapStart, 512MB | Credential exchange (hot path) |
| eks-dx-mgmt-service | Lambda JVM, 256MB | Cluster/association CRUD |
| eks-dx-tenant-service | Lambda native arm64, 128MB, 15min | Tenant provisioning + lifecycle |
| eks-dx-auth-proxy | Container (in-cluster) | TokenReview + Lambda forwarding |
| eks-dx-pod-identity-webhook | Container (in-cluster) | Pod mutation (env + volume injection) |
| eks-dx-cli | Native binary (GraalVM) | CLI for cluster/association/tenant management |
| eks-dx-model | Library JAR | Shared TokenClaims record |
| infra | CDK app | CloudFormation stack definition |

## Prerequisites
- Java 25 (GraalVM JDK for native)
- Maven 3.9+
- Docker (native builds)
- AWS CDK CLI
