# Architecture

## System Overview

EKS-DX Control Plane is a serverless service that brings EKS Pod Identity to non-EKS Kubernetes clusters (EKS-D via kubeadm). It consists of three Lambda functions, two in-cluster containers, a native CLI, and a CDK infrastructure stack.

## Deployment Topology

```mermaid
graph TB
    subgraph "AWS Cloud"
        subgraph "API Gateway (IAM + Open)"
            APIGW[REST API]
        end
        subgraph "Lambda Functions"
            Cred[credential-service<br/>JVM + SnapStart]
            Mgmt[mgmt-service<br/>JVM]
            Tenant[tenant-service<br/>GraalVM native arm64]
        end
        subgraph "Storage"
            Clusters[(eks-dx-clusters)]
            Assoc[(eks-dx-associations)]
            Tenants[(eks-dx-tenants)]
        end
        subgraph "Tenant Infrastructure"
            EC2[EC2 Instance<br/>EKS-D + kubeadm]
            SQS[SQS Queue<br/>Karpenter interruption]
            EB[EventBridge Rules]
        end
        STS[AWS STS]
        SM[Secrets Manager]
    end
    
    subgraph "Tenant Cluster"
        Proxy[eks-dx-auth-proxy]
        Webhook[pod-identity-webhook]
        Pods[Application Pods]
    end
    
    APIGW --> Cred & Mgmt & Tenant
    Cred --> Clusters & Assoc & STS
    Mgmt --> Clusters & Assoc
    Tenant --> Tenants & EC2 & SQS & SM
    EB --> SQS
    Proxy --> APIGW
    Webhook --> APIGW
    Pods --> Proxy
```

## Request Flows

### Credential Exchange (Hot Path)
```mermaid
sequenceDiagram
    participant Pod
    participant Agent as Pod Identity Agent
    participant Proxy as eks-dx-auth-proxy
    participant K8s as Kube API
    participant Lambda as credential-service
    participant DDB as DynamoDB
    participant STS as AWS STS
    
    Pod->>Agent: Need AWS credentials
    Agent->>Proxy: POST /clusters/{name}/assets {token}
    Proxy->>K8s: TokenReview (fast-fail)
    K8s-->>Proxy: Authenticated
    Proxy->>Lambda: Forward request
    Lambda->>DDB: Get JWKS for cluster
    Lambda->>Lambda: Validate JWT signature
    Lambda->>DDB: Lookup association (namespace#sa → roleArn)
    Lambda->>STS: AssumeRole (with session tags)
    STS-->>Lambda: Temporary credentials
    Lambda-->>Proxy: Credentials response
    Proxy-->>Agent: Credentials
    Agent-->>Pod: AWS credentials injected
```

### Tenant Provisioning
```mermaid
sequenceDiagram
    participant CLI as eks-dx CLI
    participant APIGW as API Gateway
    participant Lambda as tenant-service
    participant Net as TenantNetworkService
    participant IAM as TenantIamService
    participant EC2 as TenantEc2Service
    participant DLM as TenantDlmService
    
    CLI->>APIGW: POST /tenants {tenantId, arch, ec2PricingModel}
    APIGW->>Lambda: Forward (SigV4 authenticated)
    Lambda->>Net: Create subnets + SG + route tables
    Lambda->>Lambda: Create secrets (signing key, SSH key)
    Lambda->>IAM: Create role + instance profile
    Lambda->>Lambda: Create SQS + EventBridge rules
    Lambda->>DLM: Create etcd backup policy
    Lambda->>EC2: Launch instance (LT + user data)
    Lambda->>Lambda: Write DynamoDB state
    Lambda-->>CLI: 202 Accepted {tenantId}
```

## Design Patterns

- **Composable services**: Tenant provisioning split into Network, IAM, EC2, DLM services
- **SSM as interface**: Terraform writes params, Lambda reads at runtime
- **Per-tenant isolation**: Dedicated subnets, SG, IAM role, SQS queue per tenant
- **Dual validation**: Proxy does TokenReview (fast-fail), Lambda does JWKS (authoritative)
- **Event-driven interruption**: EventBridge → SQS → Karpenter handles spot reclaims
