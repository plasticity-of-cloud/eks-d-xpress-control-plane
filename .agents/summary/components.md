# Components

## Module Map

| Module | Purpose | Key Classes |
|--------|---------|-------------|
| `eks-dx-credential-service` | Hot-path credential exchange (SnapStart) | `EksAuthResource`, `JwksTokenValidationService`, `AwsCredentialService` |
| `eks-dx-mgmt-service` | Cluster/association CRUD, JWKS refresh | `ClusterResource`, `AssociationResource`, `DynamoDbClusterService`, `TrustPolicyService` |
| `eks-dx-tenant-service` | Cluster provisioning, lifecycle, PKI generation | `ClusterResource`, `TenantResource`, `TenantProvisioningService`, `TenantCryptoService` |
| `eks-dx-auth-proxy` | In-cluster TokenReview + Lambda forwarding | `EksAuthAgentResource`, `TokenValidationService`, `EksDxCredentialServiceClient` |
| `eks-dx-pod-identity-webhook` | Admission webhook: injects env + token volume | `PodIdentityMutator`, `LambdaAssociationLookup` |
| `eks-dx-karpenter-support` | EC2NodeClass webhook + reconciler | `Ec2NodeClassWebhookResource`, `Ec2NodeClassReconciler`, `UserDataMergeService` |
| `eks-dx-cli` | Native CLI binary | `UnifiedCreateClusterCommand`, `UnifiedDeleteClusterCommand`, `EksDxApiClient` |
| `eks-dx-model` | Shared `TokenClaims` record | `TokenClaims`, `CallerIdentity` |
| `infra` | CDK stack (Java) | `EksDXpressControlPlaneStack` |

## TenantNaming Constants

All tenant-scoped AWS resource names are derived from `TenantNaming`:

```java
TenantNaming.RESOURCE_PREFIX  = "eks-dx-tenant-"
TenantNaming.SECRET_PREFIX    = "eks-dx/tenant/"

TenantNaming.roleName(id)             // eks-dx-tenant-<id>-ir
TenantNaming.instanceProfileName(id)  // eks-dx-tenant-<id>-ir
TenantNaming.dlmRoleName(id)          // eks-dx-tenant-<id>-dlm
TenantNaming.securityGroupName(id)    // eks-dx-tenant-<id>-sg
TenantNaming.keyPairName(id)          // eks-dx-tenant-<id>-key
TenantNaming.queueName(cluster)       // eks-dx-tenant-<cluster>
TenantNaming.eventRuleName(c, suffix) // eks-dx-tenant-<cluster>-<suffix>
TenantNaming.secretPath(id, name)     // eks-dx/tenant/<id>/<name>
```

## Tenant Service Decomposition

| Service | Responsibility | Create | Delete |
|---------|---------------|--------|--------|
| `TenantNetworkService` | Subnets, SG, route table associations | Public + private /24 subnets, SG | Internal cleanup on partial failure |
| `TenantCryptoService` | CA + SA key generation, KMS signing, JWKS derivation | Secrets Manager storage | Deletes all PKI secrets |
| `TenantIamService` | IAM role, instance profile, inline policy | Role + profile + policies | Detach + delete |
| `TenantEc2Service` | Instance launch, EIP, user-data | RunInstances + EIP | (handled by provisioning service) |
| `TenantDlmService` | Daily etcd backup lifecycle policy | DLM policy + execution role | Snapshots → policy → role |

## Authorization Roles

| Role | Tag | Quota | Can Provision for Others |
|------|-----|-------|--------------------------|
| `EksDXpressUser` | `user` | 1 | No |
| `EksDXpressOperator` | `operator` | 10 (CDK param) | No |
| `EksDXpressAdministrator` | `administrator` | 10 (CDK param) | Yes |
