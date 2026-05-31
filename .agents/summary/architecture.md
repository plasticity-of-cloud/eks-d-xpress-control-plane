# Architecture

## System Overview

```mermaid
graph TB
    Pod[Pod] --> Agent[eks-dx-auth-proxy]
    Agent -->|TokenReview| K8sAPI[Kubernetes API]
    Agent -->|Forward token| APIGW[API Gateway]
    APIGW --> CredFn[credential-service Lambda]
    CredFn -->|JWKS validate| DDB1[DynamoDB clusters]
    CredFn -->|Association lookup| DDB2[DynamoDB associations]
    CredFn -->|AssumeRole| STS[AWS STS]

    CLI[eks-dx CLI] -->|SigV4| APIGW
    APIGW --> MgmtFn[mgmt-service Lambda]
    MgmtFn --> DDB1
    MgmtFn --> DDB2

    APIGW --> TenantFn[tenant-service Lambda]
    TenantFn --> DDB3[DynamoDB tenants]
    TenantFn --> EC2[EC2]
    TenantFn --> IAM[IAM]
    TenantFn --> SM[Secrets Manager]
    TenantFn --> DLM[DLM]

    Webhook[pod-identity-webhook] -->|Association check| APIGW
```

## Three-Lambda Split

| Lambda | Profile | Why Separate |
|--------|---------|--------------|
| credential-service | SnapStart, 512MB, 30s | Hot path, low latency, high concurrency |
| mgmt-service | JVM, 256MB, 30s | CRUD, infrequent, simple |
| tenant-service | Native arm64, 128MB, 900s | Long-running provisioning, cold start matters less |

## Tenant Provisioning — Composable with Rollback

```mermaid
sequenceDiagram
    participant O as Orchestrator
    participant N as NetworkService
    participant S as SecretsManager
    participant I as IamService
    participant D as DlmService
    participant E as Ec2Service

    O->>N: createTenantNetwork (subnets + SG)
    O->>S: createSecret (signing key + SSH key)
    O->>I: createTenantRole (role + instance profile)
    O->>D: createEtcdBackupPolicy
    O->>E: launchInstance

    Note over O: On failure at any step:
    O->>E: terminateInstance (if created)
    O->>D: deleteEtcdBackupPolicy
    O->>I: deleteTenantRole
    O->>S: deleteSecrets
    O->>N: deleteTenantNetwork
```

## SSM as Interface

Infrastructure stack writes parameters, Lambda reads at runtime:
- `/eks-dx/launch-template/{arch}/{spot|ondemand}` — Launch template IDs
- `/eks-dx/ami/{arch}/{k8s-version}` — AMI IDs
- `/eks-dx/network/vpc-id`, `private-subnet-ids`, `security-group-id`

## IAM Model

- Tenant-service role: EC2 (scoped to VPC for networking, broad for instances), IAM (scoped to `eks-dx-tenant-*`), Secrets Manager, DLM, STS, SSM, DynamoDB
- Credential-service role: DynamoDB read, STS AssumeRole (scoped to `eks-dx-pod-*`)
- Mgmt-service role: DynamoDB read/write, IAM GetRole (validation)
