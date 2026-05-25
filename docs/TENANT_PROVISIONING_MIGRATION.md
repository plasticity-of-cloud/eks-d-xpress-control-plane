# Migration: Tenant Provisioning from Terraform to Lambda

## Context

Tenant EC2 instance lifecycle is moving from Terraform (in `eks-dx-infra`) to the tenant-service Lambda (in `eks-dx-control-plane`). Terraform retains ownership of static, reusable infrastructure only.

## Before (current)

```
provision-tenant.sh → Terraform → EC2 instance
                                   └─ user data: cluster.env only
```

Terraform manages: VPC, subnets, SG, IAM role, launch templates, **and** the EC2 instance itself.

## After (target)

```
eks-dx CLI / API
    │
    └─► tenant-service Lambda (RunInstances API)
         ├─ Input: tenantId, arch, ec2PricingModel, region
         ├─ Looks up: LT from SSM, subnet from SSM
         ├─ Creates: IAM role, key pair, EC2 instance
         └─ User data: cluster.env + EKS_DX_ENDPOINT + EKS_DX_API_URL
```

Terraform manages: VPC, subnets, SG, launch templates, AMIs, SSM parameters.
Terraform does **NOT** manage: EC2 instances, per-tenant IAM roles, key pairs.

## Responsibility Split

| Resource | Owner | Lifecycle |
|----------|-------|-----------|
| VPC, subnets, route tables | Terraform | Static, shared across tenants |
| Security groups | Terraform | Static, shared |
| Launch templates (4: arch × pricing) | Terraform | Static, references AMI |
| AMIs (per arch, per k8s version) | Terraform + Packer | Versioned, region-copied |
| SSM parameters | Terraform | Written once, read by Lambda |
| EC2 instance | **Lambda** | Per-tenant, dynamic |
| IAM role (per-tenant) | **Lambda** | Per-tenant, dynamic |
| EC2 key pair | **Lambda** | Per-tenant, dynamic |
| Secrets (signing key, SSH key) | **Lambda** | Per-tenant, Secrets Manager |
| DynamoDB tenant state | **Lambda** | Per-tenant, dynamic |

## Lambda Provisioning Flow (already implemented)

```java
public String provision(String tenantId, String arch, String ec2PricingModel, String k8sVersion) {
    // 1. Generate RSA-2048 SA signing key → Secrets Manager
    // 2. Create EC2 key pair → Secrets Manager
    // 3. Create IAM role (eks-dx-tenant-{id}-instance-role)
    // 4. Resolve launch template from arch + ec2PricingModel
    // 5. RunInstances with:
    //    - Launch template (from SSM)
    //    - Subnet (from SSM)
    //    - Key pair
    //    - IAM instance profile
    //    - User data (cluster.env + EKS_DX_ENDPOINT + EKS_DX_API_URL)
    // 6. Store state in DynamoDB
}
```

## User Data Injection

Lambda constructs user data at launch time with tenant-specific values:

```bash
#!/bin/bash
# --- Static (from launch template) ---
# AMI already has EKS-D installed via kubeadm

# --- Dynamic (injected by Lambda) ---
cat > /etc/eks-dx/cluster.env <<EOF
TENANT_ID=${tenantId}
CLUSTER_NAME=eks-dx-${tenantId}
EKS_DX_ENDPOINT=https://eks-dx.codriverlabs.ai
EKS_DX_API_URL=https://eks-dx.codriverlabs.ai/clusters/${tenantId}/assets
REGION=${region}
K8S_VERSION=${k8sVersion}
EOF

# Bootstrap EKS-D cluster
/opt/eks-dx/bootstrap.sh
```

## What Terraform Removes

After migration, remove from `eks-dx-infra`:

- `aws_instance` resources (per-tenant EC2)
- `aws_iam_role` for per-tenant instance roles
- `aws_key_pair` per-tenant
- `provision-tenant.sh` EC2 launch logic (keep for local testing only)

## What Terraform Keeps

- `aws_vpc`, `aws_subnet`, `aws_route_table`
- `aws_security_group` (shared tenant SG)
- `aws_launch_template` (4: arm64/x86 × spot/ondemand)
- `aws_ami_copy` (per-region AMI distribution)
- `aws_ssm_parameter` (all `/eks-dx/*` params)
- `provision-tenant.sh` (local testing wrapper, calls Lambda or runs directly)

## SSM Parameters Read by Lambda

| SSM Path | Used For |
|----------|----------|
| `/eks-dx/launch-template/arm64/ondemand` | LT selection |
| `/eks-dx/launch-template/arm64/spot` | LT selection |
| `/eks-dx/launch-template/x86_64/ondemand` | LT selection |
| `/eks-dx/launch-template/x86_64/spot` | LT selection |
| `/eks-dx/network/private-subnet-ids` | Instance placement |

## Migration Steps

1. ✅ Tenant-service Lambda implements `provision()` with RunInstances (done)
2. ✅ Lambda accepts `arch`, `ec2PricingModel`, `k8sVersion` (done)
3. ✅ Lambda resolves LT from env vars populated via SSM (done)
4. ⬜ Add user data construction with `EKS_DX_ENDPOINT` + `EKS_DX_API_URL`
5. ⬜ Terraform: create SSM params, LTs, AMIs (in `eks-dx-infra`)
6. ⬜ Terraform: remove per-tenant EC2/IAM resources
7. ⬜ End-to-end test: CLI → Lambda → EC2 → cluster bootstraps → credential exchange works
8. ⬜ Keep `provision-tenant.sh` as local-only testing path

## Local Testing (provision-tenant.sh)

`provision-tenant.sh` remains in `eks-dx-infra` for local iteration without deploying Lambda. It replicates the same logic (resolve LT, RunInstances, inject user data) but runs from the developer's machine. Not used in production.
