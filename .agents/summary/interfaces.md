# Interfaces

## REST APIs

### Credential Exchange (credential-service)
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/clusters/{name}/assets` | None (token in body) | Exchange pod token for AWS credentials |

### Management (mgmt-service)
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/clusters` | IAM SigV4 | Register cluster (name, issuer, jwks) |
| GET | `/clusters` | IAM SigV4 | List clusters |
| GET | `/clusters/{name}` | IAM SigV4 | Describe cluster |
| DELETE | `/clusters/{name}` | IAM SigV4 | Deregister cluster |
| PUT | `/clusters/{name}/jwks` | IAM SigV4 | Refresh JWKS |
| POST | `/clusters/{name}/pod-identity-associations` | IAM SigV4 | Create association |
| GET | `/clusters/{name}/pod-identity-associations` | Bearer (optional) | List associations |
| GET | `/clusters/{name}/pod-identity-associations/{id}` | Bearer (optional) | Describe association |
| DELETE | `/clusters/{name}/pod-identity-associations/{id}` | IAM SigV4 | Delete association |

### Tenant (tenant-service)
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/tenants` | IAM SigV4 | Create tenant |
| GET | `/tenants/{id}` | IAM SigV4 | Get tenant state |
| DELETE | `/tenants/{id}` | IAM SigV4 | Deprovision tenant |
| GET | `/tenants/{id}/stream` | IAM (Function URL) | SSE progress stream |

### Tenant Create Request
```json
{
  "tenantId": "string (required)",
  "arch": "arm64 | x86_64 (default: arm64)",
  "ec2PricingModel": "spot | ondemand (default: spot)",
  "k8sVersion": "string (default: 1.35)",
  "assignElasticIp": "boolean (default: false)"
}
```

## SSM Parameter Contract

Interface between infrastructure (Terraform/CDK) and Lambda runtime.

```
/eks-dx/
├── ami/{arch}/{k8s-version}              → AMI ID (region-specific)
├── launch-template/{arch}/{pricing}      → Launch Template ID
└── network/
    ├── vpc-id
    ├── public-subnet-ids                 → StringList
    ├── private-subnet-ids                → StringList
    └── security-group-id
```

Discovery: `aws ssm get-parameters-by-path --path /eks-dx/launch-template/arm64`

## DynamoDB Tables

### eks-dx-clusters
| Key | Type | Description |
|-----|------|-------------|
| `clusterName` (PK) | String | Cluster identifier |

Attributes: `issuer`, `jwks`, `createdAt`

### eks-dx-associations
| Key | Type | Description |
|-----|------|-------------|
| `PK` | String | `CLUSTER#<clusterName>` |
| `SK` | String | `<namespace>#<serviceAccount>` |

Attributes: `roleArn`, `associationId`, `createdAt`

### eks-dx-tenants
| Key | Type | Description |
|-----|------|-------------|
| `tenantId` (PK) | String | Tenant identifier |

Attributes: `instanceId`, `state`, `phase`, `progress`, `publicIp`, `sshKeySecretArn`, `updatedAt`, `error`

## Environment Variables

### credential-service
| Variable | Description |
|----------|-------------|
| `EKS_DX_CLUSTERS_TABLE` | DynamoDB clusters table name |
| `EKS_DX_ASSOCIATIONS_TABLE` | DynamoDB associations table name |

### tenant-service
| Variable | Description |
|----------|-------------|
| `EKS_DX_TENANTS_TABLE` | DynamoDB tenants table name |
| `EKS_DX_CLUSTERS_TABLE` | DynamoDB clusters table name |
| `EKS_DX_LT_ARM64_ONDEMAND` | Launch template ID |
| `EKS_DX_LT_ARM64_SPOT` | Launch template ID |
| `EKS_DX_LT_X86_ONDEMAND` | Launch template ID |
| `EKS_DX_LT_X86_SPOT` | Launch template ID |
| `EKS_DX_VPC_ID` | VPC for tenant resources |
| `EKS_DX_ENDPOINT` | API Gateway URL |

### auth-proxy
| Variable | Description |
|----------|-------------|
| `EKS_DX_ENDPOINT` | Lambda API Gateway URL |
