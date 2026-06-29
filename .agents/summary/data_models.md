# Data Models

## DynamoDB Tables

### eks-d-xpress-clusters

| Key | Type | Description |
|-----|------|-------------|
| `clusterName` (PK) | S | Unique cluster identifier |
| `jwks` | S | JSON Web Key Set (public keys) |
| `issuer` | S | SA token issuer URL |
| `tenantId` | S | Associated tenant ID |
| `ownerArn` | S | IAM ARN of the owner |
| `managed` | S | "true" or "false" |
| `createdAt` | S | ISO-8601 timestamp |

### eks-d-xpress-associations

| Key | Type | Description |
|-----|------|-------------|
| `PK` | S | `CLUSTER#<clusterName>` |
| `SK` | S | `<namespace>#<serviceAccount>` |
| `roleArn` | S | IAM role to assume |
| `associationId` | S | Unique association ID |
| `createdAt` | S | ISO-8601 timestamp |

O(1) GetItem for credential exchange hot path.

### eks-d-xpress-tenants

| Key | Type | Description |
|-----|------|-------------|
| `tenantId` (PK) | S | System-derived 8-char hex hash |
| `clusterName` | S | User-visible cluster name |
| `managed` | S | "true" or "false" |
| `idcUserId` | S | IAM Identity Center user identity |
| `ownerArn` | S | Normalized IAM ARN |
| `state` | S | provisioning / ready / failed / stopped |
| `phase` | S | Human-readable progress description |
| `progress` | N | 0-100 percentage |
| `instanceId` | S | EC2 instance ID (managed only) |
| `publicIp` | S | Instance public IP |
| `eipAllocationId` | S | Elastic IP allocation |
| `sshKeySecretArn` | S | Secrets Manager ARN for SSH key |
| `ec2PricingModel` | S | spot or ondemand |
| `createdAt` | S | ISO-8601 timestamp |
| `updatedAt` | S | ISO-8601 timestamp |

## Java Records

### TenantItem
```java
public record TenantItem(
    String tenantId, String clusterName, boolean managed,
    String idcUserId, String ownerArn, String createdAt, String updatedAt,
    String state, String phase, int progress,
    String instanceId, String publicIp, String eipAllocationId,
    String sshKeySecretArn, String ec2PricingModel, String error
) {}
```

### TenantProgress
```java
public record TenantProgress(
    String state, String phase, int progress,
    String publicIp, long elapsedSeconds, String error, String sshPrivateKey
) {}
```

### TenantCryptoService.CryptoResult
```java
public record CryptoResult(String jwks, String issuer, String caKeySecret, String caCrtSecret, String saKeySecret) {}
```

## Tenant ID Generation

```
tenantId = HEX(SHA256(idcUserId + ":" + createdAt))[:8]
```

- `idcUserId`: IAM Identity Center email (from session name) or IAM user ARN
- Collision retry extends to 9 chars
