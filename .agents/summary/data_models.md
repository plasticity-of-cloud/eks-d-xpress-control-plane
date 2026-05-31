# Data Models

## DynamoDB Tables

### eks-d-xpress-clusters
| Key | Type | Description |
|-----|------|-------------|
| `clusterName` (PK) | S | Cluster identifier |
| `issuer` | S | OIDC issuer URL |
| `jwks` | S | JSON Web Key Set (cached) |
| `jwksUpdatedAt` | S | ISO timestamp |
| `createdAt` | S | ISO timestamp |

### eks-d-xpress-associations
| Key | Type | Description |
|-----|------|-------------|
| `PK` | S | `CLUSTER#<clusterName>` |
| `SK` | S | `<namespace>#<serviceAccount>` |
| `roleArn` | S | IAM role to assume |
| `associationId` | S | Unique ID |
| `createdAt` | S | ISO timestamp |

O(1) GetItem for credential exchange hot path.

### eks-d-xpress-tenants
| Key | Type | Description |
|-----|------|-------------|
| `tenantId` (PK) | S | Tenant identifier |
| `instanceId` | S | EC2 instance ID |
| `state` | S | `provisioning` / `running` / `terminated` |
| `phase` | S | Current provisioning step |
| `progress` | N | 0-100 |
| `ownerArn` | S | IAM principal that owns this tenant |
| `ownerRole` | S | Resolved eks-dx-role at creation |
| `provisionedBy` | S | Operator ARN (if provisioned for another) |
| `sshKeySecretArn` | S | Secrets Manager ARN for SSH key |
| `updatedAt` | S | ISO timestamp |

**GSIs** (planned):
- `ownerArn-index` (PK: ownerArn, SK: tenantId) — quota checks
- `provisionedBy-index` (PK: provisionedBy, SK: tenantId) — operator visibility
