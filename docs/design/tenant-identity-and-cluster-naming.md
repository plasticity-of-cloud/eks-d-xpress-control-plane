# Tenant Identity and Cluster Naming

## Status

Implemented — supersedes naming section in `docs/roadmap/multi-tenancy-handling.md`

## Overview

Two distinct identifiers exist in the system:

| Identifier | Purpose | Source | Example |
|------------|---------|--------|---------|
| **Tenant ID** | AWS resource naming, SSH key path, internal key | System-derived hash | `a7f3b2c1` |
| **Cluster name** | Workload-visible identifier, pod-identity lookup key | User-provided, validated | `my-dev-cluster` |

The cluster name is **not globally unique** — the same name (e.g. `ci-pipeline`) can exist in multiple regions for different tenants. The tenant ID is globally unique and is the only stable key for AWS resources and local paths.

## Tenant ID

### Derivation

The tenant ID is a short hash derived from the IAM Identity Center (IdC) user ID of the requestor, similar to git commit short hashes:

```
tenantId = HEX(SHA256(idcUserId + ":" + createdAt))[:8]
```

- `idcUserId`: the Identity Center unique user ID (e.g., `d-1234567890/user@example.com` or the IdC `UserId` GUID)
- `createdAt`: ISO-8601 UTC timestamp at tenant creation time
- 8 hex characters = 32 bits of entropy, ~4 billion unique values

Properties:
- **Not user-chosen** — eliminates naming conflicts and resource name injection
- **Collision-resistant** — timestamp component guarantees uniqueness for same user across recreations
- **Short** — fits within IAM role name constraints with room to spare
- **Opaque** — does not leak identity information in resource names
- **Stable** — never changes after creation

### Collision Handling

On the rare collision (DynamoDB `attribute_not_exists` condition fails), retry with a counter suffix:

```java
String base = hex(sha256(idcUserId + ":" + createdAt));
String tenantId = base.substring(0, 8);  // first attempt
// on ConditionalCheckFailedException:
tenantId = base.substring(0, 8) + base.charAt(8);  // extend to 9 chars
```

## Cluster Name

### Validation Rules (EKS-compatible)

The cluster name is the user-facing identifier for the virtual cluster. It follows the same strict rules as EKS:

```
Pattern: ^[a-zA-Z][a-zA-Z0-9-]{0,99}$
```

- Starts with a letter
- Contains only alphanumeric characters and hyphens
- Maximum 100 characters
- Case-sensitive

### Scope

The cluster name is **scoped to a region**. The same name can be reused across regions (e.g. a CI/CD pipeline creating `ci-pipeline` in `us-east-1` and `eu-west-1`). Uniqueness is enforced within the `eks-d-xpress-clusters` DynamoDB table in each deployment region.

### Registration

The cluster name is what gets registered in `eks-d-xpress-clusters` (for pod identity associations) and is visible to workloads via the credential exchange flow. It serves the same purpose as an EKS cluster name — it's how pods and operators reference the cluster.

**For managed tenants:** The `tenant-service` Lambda pre-registers the cluster before EC2 boots. `TenantCryptoService` generates KMS-signed CA + SA signing keys, derives JWKS, and `preRegisterCluster()` writes the cluster record (with JWKS and issuer) to DynamoDB. No `register-cluster` CLI call is needed from the boot script.

**For self-managed clusters** (k3s, microk8s, EKS-D self-managed, etc.): Users call `create-cluster` with `--jwks` to register their cluster directly.

```bash
# Managed (provisions EC2 + EKS-D, pre-registers JWKS automatically):
eks-dx create-cluster my-dev-cluster --wait

# Self-managed (register with user-provided JWKS):
eks-dx create-cluster my-k3s \
  --issuer https://my-k3s-host \
  --jwks-file /path/to/jwks.json
```

### Relationship to Tenant

```
┌─────────────────────────────────────────────────┐
│ DynamoDB: eks-d-xpress-tenants                   │
├─────────────────────────────────────────────────┤
│ PK: tenantId = "a7f3b2c1"                       │
│ clusterName = "my-dev-cluster"                  │
│ managed = "true"                                │
│ idcUserId = "d-906714.../user@example.com"      │
│ ownerArn = "arn:aws:iam::...:role/..."          │
│ createdAt = "2026-06-26T10:00:00Z"              │
│ ...                                             │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│ DynamoDB: eks-d-xpress-clusters                  │
├─────────────────────────────────────────────────┤
│ PK: clusterName = "my-dev-cluster"              │
│ tenantId = "a7f3b2c1"                           │
│ managed = "true"                                │
│ jwks = "..."                                    │
│ issuer = "https://..."                          │
│ createdAt = "2026-06-26T10:00:00Z"              │
└─────────────────────────────────────────────────┘
```

A single tenant owns one virtual cluster. The cluster name is user-chosen and validated; the tenant ID is system-derived and opaque.

## DynamoDB Schema

**Table: `eks-d-xpress-tenants`** (PK: `tenantId`)

| Attribute | Type | Notes |
|---|---|---|
| `tenantId` | String (PK) | System-derived 8-char hex hash |
| `clusterName` | String | User-provided, validated |
| `managed` | String | `"true"` = EKS-D-Xpress provisioned EC2; `"false"` = standalone cluster |
| `idcUserId` | String | Identity Center user ID (audit trail, input to hash) |
| `ownerArn` | String | Caller IAM ARN |
| `createdAt` | String | ISO-8601, immutable |
| `updatedAt` | String | ISO-8601, updated on state change |
| `state` | String | `provisioning`, `ready`, `failed`, `stopped` (null for unmanaged) |
| `phase` | String | Human-readable progress step (managed only) |
| `progress` | Number | 0–100 (managed only) |
| `instanceId` | String | EC2 instance ID (managed only) |
| `publicIp` | String | EIP address (managed only) |
| `eipAllocationId` | String | EIP tracking (managed only) |
| `sshKeySecretArn` | String | Secrets Manager ARN (managed only) |
| `ec2PricingModel` | String | `spot` or `ondemand` (managed only) |
| `error` | String | Set on provisioning failure (managed only) |

**Table: `eks-d-xpress-clusters`** (PK: `clusterName`)

| Attribute | Type | Notes |
|---|---|---|
| `clusterName` | String (PK) | User-provided, unique within region |
| `tenantId` | String | Back-reference to owning tenant |
| `managed` | String | `"true"` or `"false"` — determines teardown behavior on delete |
| `ownerArn` | String | Caller IAM ARN (authorization on delete) |
| `jwks` | String | JSON Web Key Set for token validation |
| `issuer` | String | OIDC issuer URL |
| `createdAt` | String | ISO-8601 |

## Resource Naming Convention

All AWS resources use the **tenant ID** (not the cluster name) to stay within IAM's 64-char limit and avoid user-input in resource ARNs.

### Naming Constants (`TenantNaming.java`)

```java
public static final String RESOURCE_PREFIX = "eks-dx-tenant-";
public static final String SECRET_PREFIX = "eks-dx/tenant/";
```

### Resource Table

| Resource | Name | Example |
|----------|------|---------|
| Instance role | `eks-dx-tenant-{tenantId}-ir` | `eks-dx-tenant-a7f3b2c1-ir` |
| Instance profile | `eks-dx-tenant-{tenantId}-ir` | (same as role) |
| DLM role | `eks-dx-tenant-{tenantId}-dlm` | `eks-dx-tenant-a7f3b2c1-dlm` |
| Key pair | `eks-dx-tenant-{tenantId}-key` | `eks-dx-tenant-a7f3b2c1-key` |
| Security group | `eks-dx-tenant-{tenantId}-sg` | `eks-dx-tenant-a7f3b2c1-sg` |
| Interruption queue | `eks-dx-tenant-{clusterName}` | `eks-dx-tenant-my-dev-cluster` |
| Progress queue | `eks-dx-tenant-{tenantId}-progress.fifo` | `eks-dx-tenant-a7f3b2c1-progress.fifo` |
| EventBridge rules | `eks-dx-tenant-{clusterName}-{suffix}` | `eks-dx-tenant-my-dev-cluster-spot` |
| Secrets (SSH) | `eks-dx/tenant/{tenantId}/ssh-key` | `eks-dx/tenant/a7f3b2c1/ssh-key` |
| Secrets (CA key) | `eks-dx/tenant/{tenantId}/ca-key` | `eks-dx/tenant/a7f3b2c1/ca-key` |
| Secrets (CA cert) | `eks-dx/tenant/{tenantId}/ca-crt` | `eks-dx/tenant/a7f3b2c1/ca-crt` |
| Secrets (SA key) | `eks-dx/tenant/{tenantId}/sa-key` | `eks-dx/tenant/a7f3b2c1/sa-key` |
| EC2 tag | `eks-dx-tenant: {tenantId}` | `eks-dx-tenant: a7f3b2c1` |
| Platform tag | `Platform: eks-d-xpress` | (all resources) |

### Factory Methods

```java
TenantNaming.roleName(tenantId)           // eks-dx-tenant-{id}-ir
TenantNaming.instanceProfileName(tenantId) // eks-dx-tenant-{id}-ir
TenantNaming.dlmRoleName(tenantId)        // eks-dx-tenant-{id}-dlm
TenantNaming.securityGroupName(tenantId)  // eks-dx-tenant-{id}-sg
TenantNaming.keyPairName(tenantId)        // eks-dx-tenant-{id}-key
TenantNaming.secretPath(tenantId, name)   // eks-dx/tenant/{id}/{name}
TenantNaming.queueName(clusterName)       // eks-dx-tenant-{clusterName}
TenantNaming.progressQueueName(tenantId)  // eks-dx-tenant-{id}-progress.fifo
TenantNaming.eventRuleName(clusterName, suffix)  // eks-dx-tenant-{clusterName}-{suffix}
```

## Local CLI Paths

The CLI stores the SSH private key locally at:

```
~/.eks-d-xpress/tenants/{region}/{tenantId}.pem
```

Using `tenantId` (not `clusterName`) because the same cluster name can exist in multiple regions — keying by `clusterName` would cause path collisions for users running, e.g., a `ci-pipeline` cluster in both `us-east-1` and `eu-west-1`.

## First Boot Script

The boot script lives in the **`eks-d-xpress`** project (Golden AMI build). It receives both `TENANT_ID` and `CLUSTER_NAME` via `/opt/eks-d/cluster.env` (written by `TenantEc2Service` at instance launch).

| Variable | Used for |
|----------|----------|
| `TENANT_ID` | Progress reporting (SQS MessageGroupId), resource tag lookups |
| `CLUSTER_NAME` | `EKS_CLUSTER_NAME` Helm value, `EKS_DX_API_URL` path |
| `PROGRESS_QUEUE_URL` | SQS FIFO queue for boot progress events |

The boot script does **not** call `register-cluster` — the cluster is pre-registered by the provisioning Lambda (with JWKS derived from KMS-generated SA keys) before the EC2 instance launches.

Progress reporting uses a per-tenant SQS FIFO queue (see `docs/roadmap/security-hardening/sqs-progress-reporting.md`):

```bash
source /opt/eks-d/cluster.env
source /opt/eks-d-setup/progress.sh

update_progress "booting" "Starting cluster setup" 5
# ... setup steps ...
report_ready
```

## API

### Unified Cluster Lifecycle (`POST /clusters` on tenant-service)

The server infers managed vs. self-managed from the request body:

**Managed** (no `jwks` field — triggers full provisioning):
```json
POST /clusters
{
  "clusterName": "my-dev-cluster",
  "arch": "arm64",
  "ec2PricingModel": "spot",
  "k8sVersion": "1.35"
}
```

**Self-managed** (`jwks` present — register only):
```json
POST /clusters
{
  "clusterName": "my-k3s",
  "issuer": "https://my-k3s-host",
  "jwks": "{ ... }"
}
```

Response (both):
```json
{
  "tenantId": "a7f3b2c1",
  "clusterName": "my-dev-cluster",
  "managed": true
}
```

### Delete (`DELETE /clusters/{name}`)

Determines teardown scope from the `managed` field in `eks-d-xpress-clusters`:
- `managed=true` → full deprovision (EC2, EIP, IAM, network, secrets, SQS, DLM, DynamoDB)
- `managed=false` → remove DynamoDB records only

### CLI

```bash
# Managed (provisions EC2 + EKS-D, pre-registers JWKS):
eks-dx create-cluster my-dev-cluster --wait
# → Streams progress via SSE until ready

# Self-managed (register with user-provided JWKS):
eks-dx create-cluster my-k3s --issuer https://... --jwks-file jwks.json

# Delete (full teardown for managed, deregister for self-managed):
eks-dx delete-cluster my-dev-cluster

# Describe:
eks-dx describe-cluster my-dev-cluster

# List all:
eks-dx list-clusters
```

## Validation Implementation

```java
private static final Pattern CLUSTER_NAME_PATTERN =
    Pattern.compile("^[a-zA-Z][a-zA-Z0-9-]{0,99}$");

private void validateClusterName(String name) {
    if (name == null || !CLUSTER_NAME_PATTERN.matcher(name).matches())
        throw new IllegalArgumentException(
            "clusterName must start with a letter, contain only [a-zA-Z0-9-], max 100 chars");
}
```

## Decisions

1. **`tenantId` is system-derived** — never user-provided, eliminates naming collisions and injection risks
2. **Every cluster has a tenant record** — managed and unmanaged; `managed: false` for standalone clusters
3. **`clusterName` is region-scoped, not globally unique** — same name allowed in different regions
4. **AWS resources and SSH key paths use `tenantId`** — stable, unique, short, no user input
5. **Boot script uses `CLUSTER_NAME`** for in-cluster components — workloads reference the cluster by name
6. **CLI commands use cluster name** — `eks-dx create-cluster <name>`, `eks-dx delete-cluster <name>`
7. **`managed` field in clusters table** — determines delete behavior (full teardown vs deregister)
8. **8-char hex hash** — 32 bits of entropy, ~4 billion unique values
9. **Pre-registration** — JWKS written to DynamoDB before EC2 boots (KMS-backed PKI)
10. **Progress via SQS** — boot script writes to per-tenant FIFO queue, not DynamoDB directly
