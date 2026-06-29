# Workflows

## Managed Cluster Provisioning

```mermaid
sequenceDiagram
    participant CLI as eks-dx CLI
    participant Lambda as tenant-service
    participant KMS
    participant SM as Secrets Manager
    participant DDB as DynamoDB
    participant EC2

    CLI->>Lambda: POST /clusters {clusterName, arch, pricing}
    Lambda->>Lambda: 1. Create network (subnets + SG)
    Lambda->>KMS: 2. kms:Sign (CA cert)
    Lambda->>SM: 3. Store ca-key, ca-crt, sa-key
    Lambda->>DDB: 4. Pre-register cluster (JWKS + issuer)
    Lambda->>Lambda: 5. Create IAM role + instance profile
    Lambda->>Lambda: 6. Create SQS queue + EventBridge rules
    Lambda->>Lambda: 7. Create DLM policy
    Lambda->>EC2: 8. RunInstances (Launch Template)
    Lambda->>DDB: 9. Write tenant record (state=provisioning)
    Lambda-->>CLI: 202 {tenantId, clusterName}

    Note over EC2: First-boot script runs
    EC2->>SM: Fetch ca-key, ca-crt, sa-key
    EC2->>EC2: Write /etc/kubernetes/pki/*
    EC2->>EC2: kubeadm init (uses pre-placed keys)
    EC2->>DDB: Update progress (state=ready)
```

## Managed Cluster Deletion

```mermaid
sequenceDiagram
    participant CLI as eks-dx CLI
    participant Lambda as tenant-service
    participant EC2
    participant IAM
    participant Network
    participant SM as Secrets Manager
    participant DDB as DynamoDB

    CLI->>Lambda: DELETE /clusters/{name}
    Lambda->>DDB: Lookup cluster record (managed=true?)
    Lambda->>EC2: TerminateInstances + wait
    Lambda->>Lambda: Release EIP
    Lambda->>Lambda: Delete DLM (snapshots → policy → role)
    Lambda->>Lambda: Delete EventBridge rules + SQS queue
    Lambda->>IAM: Delete role + instance profile
    Lambda->>EC2: Delete key pair
    Lambda->>SM: Delete SSH key secret
    Lambda->>Lambda: Delete PKI secrets (crypto service)
    Lambda->>DDB: Delete cluster record
    Lambda->>Network: Delete subnets + SG
    Lambda->>DDB: Delete tenant record
    Lambda-->>CLI: 204
```

## Rollback on Provisioning Failure

If any step fails during provisioning, `ProvisionedResources` tracks what was created. Rollback happens in reverse order:

1. Terminate EC2 instance (wait for termination)
2. Release EIP
3. Delete DLM policy
4. Delete EventBridge rules + SQS queue
5. Delete IAM role + instance profile
6. Delete key pair
7. Delete SSH key secret
8. Delete PKI secrets (TenantCryptoService.deleteSecrets)
9. Delete pre-registered cluster from DynamoDB
10. Delete network (TenantNetworkService — also does internal cleanup on partial failure)

## Self-Managed Cluster Registration

```mermaid
sequenceDiagram
    participant CLI as eks-dx CLI
    participant Lambda as tenant-service
    participant DDB as DynamoDB

    CLI->>Lambda: POST /clusters {clusterName, jwks, issuer}
    Lambda->>DDB: PutItem (clusters table: JWKS + issuer)
    Lambda->>DDB: PutItem (tenants table: managed=false)
    Lambda-->>CLI: 201 {tenantId, clusterName}
```

## Credential Exchange (Hot Path)

1. Pod calls eks-pod-identity-agent (DaemonSet, intercepts 169.254.170.23)
2. Agent calls eks-dx-auth-proxy (in-cluster, kube-system)
3. Proxy does Kubernetes TokenReview (fast-fail: validates JWT signature + audience)
4. Proxy forwards to credential-service Lambda (API Gateway)
5. Lambda validates JWT via JWKS (DynamoDB-cached, 5-min TTL per cluster|audience)
6. Lambda looks up association: `PK=CLUSTER#<name>`, `SK=<namespace>#<serviceAccount>`
7. Lambda calls STS AssumeRole with session tags from token claims
8. Returns temporary AWS credentials to pod

## Network Allocation

- VPC: `10.0.0.0/16` (shared)
- Shared infra subnet: `10.0.0.0/24`
- Per-tenant public subnet: `10.0.<index>.0/24` (index auto-incremented)
- Per-tenant private subnet: `10.0.<100+index>.0/24`
- Control plane IP: `10.0.<index>.5` (static within public subnet)
