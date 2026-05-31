# Components

## eks-dx-credential-service
Hot-path Lambda for credential exchange. Validates pod SA tokens via JWKS, looks up association in DynamoDB, calls STS AssumeRole.

| Class | Role |
|-------|------|
| `EksAuthResource` | REST endpoint, request/response mapping |
| `JwksTokenValidationService` | JWKS fetch + cache (5min TTL), JWT validation |
| `AwsCredentialService` | STS AssumeRole with session tags |

## eks-dx-mgmt-service
CRUD Lambda for clusters and pod identity associations.

| Class | Role |
|-------|------|
| `ClusterResource` | Register/list/describe/delete clusters, refresh JWKS |
| `AssociationResource` | Create/list/describe/delete associations |
| `DynamoDbClusterService` | Cluster table operations |
| `DynamoDbAssociationService` | Association table operations, role validation |
| `WebhookAuthFilter` | Optional Bearer token validation for webhook calls |

## eks-dx-tenant-service
Long-running native Lambda for tenant provisioning with compensating rollback.

| Class | Role |
|-------|------|
| `TenantResource` | REST endpoint, SSH CIDR resolution, error handling |
| `TenantStreamResource` | SSE progress stream via Function URL |
| `TenantProvisioningService` | Orchestrator: provision(), rollback(), deprovision() |
| `TenantNetworkService` | Subnets, SG, route table association; create + delete |
| `TenantIamService` | Role, inline policy, instance profile; create + delete |
| `TenantEc2Service` | Instance launch, user data, EIP |
| `TenantDlmService` | Etcd backup lifecycle policy; create + delete |
| `ProvisionedResources` | Tracks created resources for rollback |

## eks-dx-auth-proxy
In-cluster sidecar/proxy. Fast-fails via Kubernetes TokenReview, then forwards to Lambda.

| Class | Role |
|-------|------|
| `EksAuthAgentResource` | REST endpoint mimicking EKS Pod Identity Agent |
| `TokenValidationService` | Kubernetes TokenReview API call |
| `EksDxCredentialServiceClient` | HTTP forwarding to Lambda via API Gateway |

## eks-dx-pod-identity-webhook
Mutating admission webhook. Injects env vars and projected SA token volume into pods with associations.

| Class | Role |
|-------|------|
| `PodIdentityMutator` | Core mutation logic (env + volume injection) |
| `WebhookEndpoint` | AdmissionReview handling |
| `LambdaAssociationLookup` | Queries Lambda API for association existence |

## eks-dx-cli
Native GraalVM binary. Picocli-based CLI with SigV4 signing.

| Class | Role |
|-------|------|
| `EksDxCommand` | Root command, subcommand registration |
| `CreateTenantCommand` | Tenant provisioning + SSE progress streaming |
| `EksDxApiClient` | HTTP client with SigV4 signing |
| `AwsSigV4Signer` | AWS Signature V4 implementation |
| `KubeApiClient` | Kubernetes API access for OIDC/JWKS discovery |
| `EksDxConfig` | Config resolution: flag → env → file → SSM → defaults |

## infra
CDK stack defining all AWS resources.

| Class | Role |
|-------|------|
| `EksDXpressControlPlaneStack` | API Gateway, 3 Lambdas, DynamoDB tables, IAM, Function URL |
| `InfraApp` | CDK app entry point |
