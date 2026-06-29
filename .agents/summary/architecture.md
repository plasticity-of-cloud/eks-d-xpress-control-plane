# Architecture

## System Overview

EKS-DX brings EKS Pod Identity to non-EKS Kubernetes clusters through a serverless Lambda backend. Three Lambdas handle credential exchange, cluster management, and tenant provisioning.

```mermaid
graph TB
    subgraph "Kubernetes Cluster (EKS-D / k3s / microk8s)"
        Pod --> Agent[eks-pod-identity-agent]
        Agent --> Proxy[eks-dx-auth-proxy]
        Webhook[eks-dx-pod-identity-webhook] -.->|mutates| Pod
    end

    subgraph "AWS (Serverless)"
        Proxy -->|TokenReview + forward| CredSvc[credential-service Lambda]
        CLI[eks-dx CLI] -->|SigV4| TenantFnUrl[Tenant Function URL]
        TenantFnUrl --> TenantSvc[tenant-service Lambda]
        CLI -->|SigV4| APIGW[API Gateway]
        APIGW --> MgmtSvc[mgmt-service Lambda]
        CredSvc --> DDB[(DynamoDB)]
        MgmtSvc --> DDB
        TenantSvc --> DDB
        TenantSvc --> EC2[EC2 Instance]
        TenantSvc --> SM[Secrets Manager]
        TenantSvc --> KMS[KMS CA Key]
        CredSvc --> STS[STS AssumeRole]
    end
```

## Credential Exchange Flow

```mermaid
sequenceDiagram
    participant Pod
    participant Proxy as eks-dx-auth-proxy
    participant K8s as kube-apiserver
    participant Lambda as credential-service
    participant DDB as DynamoDB
    participant STS as AWS STS

    Pod->>Proxy: POST / (token in body)
    Proxy->>K8s: TokenReview (fast-fail)
    K8s-->>Proxy: authenticated (namespace, SA, claims)
    Proxy->>Lambda: Forward via API Gateway
    Lambda->>DDB: GetItem (CLUSTER#name / ns#sa → roleArn)
    Lambda->>Lambda: JWKS validation (jose4j, cached 5min)
    Lambda->>STS: AssumeRole (session tags from claims)
    STS-->>Lambda: Temporary credentials
    Lambda-->>Proxy: Credentials response
    Proxy-->>Pod: AWS credentials
```

## Deployment Topology

| Component | Runtime | Memory | Timeout | Auth |
|-----------|---------|--------|---------|------|
| credential-service | JVM (SnapStart) | 512MB | 30s | None (token-validated) |
| mgmt-service | JVM | 256MB | 30s | IAM SigV4 (API GW) |
| tenant-service | JVM or native arm64 | 256-512MB | 900s | IAM SigV4 (Function URL) |
| eks-dx-auth-proxy | Container (in-cluster) | — | — | K8s ServiceAccount |
| eks-dx-pod-identity-webhook | Container (in-cluster) | — | — | cert-manager TLS |

## PKI Trust Hierarchy (Managed Mode)

```mermaid
graph TD
    KMS[KMS Asymmetric Key<br/>RSA-2048, non-exportable] -->|kms:Sign| CA[Per-tenant CA cert]
    CA --> SAKey[SA signing key pair]
    SAKey --> JWKS[JWKS in DynamoDB]
    CA --> SM1[Secrets Manager: ca-key]
    CA --> SM2[Secrets Manager: ca-crt]
    SAKey --> SM3[Secrets Manager: sa-key]
```
