# Architecture

## System Overview

EKS-DX extends EKS Pod Identity to non-EKS Kubernetes clusters. It replicates the `AssumeRoleForPodIdentity` API contract so that the standard EKS Pod Identity Agent can work against k3s, microk8s, and EKS-D clusters without modification.

## High-Level Architecture

```mermaid
graph TB
    subgraph "Kubernetes Cluster (k3s / microk8s / EKS-D)"
        Pod["Pod\n(AWS SDK)"]
        Agent["EKS Pod Identity Agent\n(DaemonSet)"]
        Proxy["eks-dx-auth-proxy\n(Deployment)"]
        Webhook["eks-dx-pod-identity-webhook\n(Admission Webhook)"]
    end

    subgraph "AWS"
        APIGW["API Gateway\n(REST v1)"]
        Lambda["eks-dx-lambda\n(Java 21, SnapStart)"]
        DDB_C["DynamoDB\neks-dx-clusters"]
        DDB_A["DynamoDB\neks-dx-associations"]
        STS["AWS STS"]
        IAM["AWS IAM"]
    end

    subgraph "Operator"
        CLI["eks-dx CLI\n(native binary)"]
    end

    Pod -->|"AWS_CONTAINER_CREDENTIALS_FULL_URI\n(injected by webhook)"| Agent
    Agent -->|"POST /clusters/{name}/assets"| Proxy
    Proxy -->|"1. TokenReview (fast-fail)"| K8sAPI["K8s API Server"]
    Proxy -->|"2. Forward POST /clusters/{name}/assets"| APIGW
    APIGW --> Lambda
    Lambda -->|"JWKS lookup + cache"| DDB_C
    Lambda -->|"Association lookup"| DDB_A
    Lambda -->|"AssumeRole + TagSession"| STS
    Lambda -->|"GetRole (trust policy validation)"| IAM
    CLI -->|"SigV4 signed"| APIGW
    Webhook -->|"GET /clusters/{name}/pod-identity-associations"| APIGW
```

## Authentication Flow (Credential Exchange)

```mermaid
sequenceDiagram
    participant Pod
    participant Agent as Pod Identity Agent
    participant Proxy as eks-dx-auth-proxy
    participant K8s as K8s API Server
    participant GW as API Gateway
    participant Lambda as eks-dx-lambda
    participant DDB as DynamoDB
    participant STS

    Pod->>Agent: GET /v1/credentials (AWS SDK)
    Agent->>Proxy: POST /clusters/{name}/assets {token}
    Proxy->>K8s: TokenReview (audience: pods.eks.amazonaws.com)
    K8s-->>Proxy: authenticated=true, namespace/sa/pod info
    Proxy->>GW: POST /clusters/{name}/assets {token}
    GW->>Lambda: invoke
    Lambda->>DDB: getJwks(clusterName)
    Lambda->>Lambda: SmallRye JWT validate (JWKS, issuer, audience)
    Lambda->>DDB: getRoleArn(cluster, namespace, sa)
    Lambda->>STS: AssumeRole(roleArn, sessionTags)
    STS-->>Lambda: credentials
    Lambda-->>GW: {credentials, subject, association}
    GW-->>Proxy: 200 credentials
    Proxy-->>Agent: 200 credentials
    Agent-->>Pod: credentials
```

## Webhook Mutation Flow

```mermaid
sequenceDiagram
    participant K8s as K8s API Server
    participant Webhook as eks-dx-pod-identity-webhook
    participant GW as API Gateway

    K8s->>Webhook: AdmissionReview (Pod CREATE/UPDATE)
    Webhook->>GW: GET /clusters/{name}/pod-identity-associations?namespace=X&sa=Y\n(Bearer SA token, audience: eks-dx.codriverlabs.ai)
    GW-->>Webhook: [{associationId, roleArn, ...}] or []
    alt association exists
        Webhook-->>K8s: JSONPatch: inject env vars + projected token volume
    else no association
        Webhook-->>K8s: no mutation
    end
```

## Design Principles

- **Wire compatibility**: The proxy and Lambda expose the same `/clusters/{name}/assets` endpoint as the real EKS Pod Identity Agent API, so the standard agent DaemonSet works unmodified.
- **Defense in depth**: Two-stage token validation — Kubernetes TokenReview (fast-fail in-cluster) + independent JWKS validation in Lambda.
- **Stateless Lambda**: All state in DynamoDB; Lambda is stateless and benefits from SnapStart for cold-start reduction.
- **Least privilege**: STS `AssumeRole` is scoped to `arn:aws:iam::*:role/eks-dx-pod-*`; management endpoints require IAM SigV4.
- **JWKS caching**: 5-minute in-memory TTL per cluster per audience to avoid DynamoDB reads on every token validation.
