# Tenant Identity and Cluster Naming

## Status

Proposed — supersedes naming section in `docs/roadmap/multi-tenancy-handling.md`

## Overview

Two distinct identifiers exist in the system:

| Identifier | Purpose | Source | Example |
|------------|---------|--------|---------|
| **Tenant ID** | AWS resource naming, internal key | Derived from IAM Identity Center user ID | `a7f3b2c1` |
| **Cluster name** | User-facing virtual cluster identifier | User-provided, validated | `my-dev-cluster` |

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

### Registration

The cluster name is what gets registered in `eks-dx-clusters` (for pod identity associations) and is visible to workloads via the credential exchange flow. It serves the same purpose as an EKS cluster name — it's how pods and operators reference the cluster.

### Relationship to Tenant

```
┌─────────────────────────────────────────────────┐
│ DynamoDB: eks-d-xpress-tenants                   │
├─────────────────────────────────────────────────┤
│ PK: tenantId = "a7f3b2c1"                       │
│ clusterName = "my-dev-cluster"                  │
│ idcUserId = "d-906714.../user@example.com"      │
│ ownerArn = "arn:aws:iam::...:role/..."          │
│ createdAt = "2026-06-26T10:00:00Z"              │
│ ...                                             │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│ DynamoDB: eks-dx-clusters                        │
├─────────────────────────────────────────────────┤
│ PK: clusterName = "my-dev-cluster"              │
│ jwksUrl = "..."                                 │
│ ...                                             │
└─────────────────────────────────────────────────┘
```

A single tenant owns one virtual cluster. The cluster name is user-chosen and validated; the tenant ID is system-derived and opaque.

## AWS Resource Naming

All AWS resources use the **tenant ID** (not the cluster name) to stay within IAM's 64-char limit and avoid user-input in resource ARNs.

### Naming Convention

```
eks-dx-t-{tenantId}-{suffix}
```

Prefix `eks-dx-t-` (8 chars) + tenant ID (8 chars) + `-` + suffix = 17 chars overhead.

### Resource Table

| Resource | Current | Proposed | Chars |
|----------|---------|----------|-------|
| Instance role | `eks-d-xpress-tenant-karolpiatek-us-east-1-instance-role` | `eks-dx-t-a7f3b2c1-ir` | 21 |
| DLM role | `eks-d-xpress-tenant-karolpiatek-us-east-1-dlm` | `eks-dx-t-a7f3b2c1-dlm` | 22 |
| Key pair | `eks-d-xpress-tenant-karolpiatek` | `eks-dx-t-a7f3b2c1-key` | 22 |
| SQS queue | `eks-d-xpress-karolpiatek` | `eks-dx-t-a7f3b2c1-q` | 20 |
| Security group | `karolpiatek-eks-d-xpress` | `eks-dx-t-a7f3b2c1-sg` | 21 |
| Secrets | `eks-d-xpress/tenant/karolpiatek/ssh-key` | `eks-dx/t/a7f3b2c1/ssh-key` | 26 |
| Tag value | `eks-d-xpress-tenant: karolpiatek` | `eks-dx-tenant: a7f3b2c1` | — |

### Benefits

- No region in role name — IAM roles are global, region was redundant
- Shortened suffixes (`-ir` instead of `-instance-role`)
- Consistent `eks-dx-t-` prefix for IAM policy scoping: `arn:aws:iam::*:role/eks-dx-t-*`
- Maximum resource name length: ~25 chars (well within all AWS limits)

## API Changes

### Create Tenant Request

```json
POST /tenants
{
  "clusterName": "my-dev-cluster"    // user-chosen, validated
}
```

Response:
```json
{
  "tenantId": "a7f3b2c1",             // system-derived
  "clusterName": "my-dev-cluster"
}
```

The `arch`, `ec2PricingModel`, `k8sVersion` etc. remain as before. The `tenantId` field in the request body is removed — it's no longer user-provided.

### CLI

```bash
eks-dx create-tenant --cluster-name my-dev-cluster
# → Created tenant a7f3b2c (cluster: my-dev-cluster)

eks-dx delete-tenant a7f3b2c
# or
eks-dx delete-tenant --cluster-name my-dev-cluster
```

### Cluster Registration

On successful provisioning, the system automatically registers the cluster name in `eks-dx-clusters` (for pod identity associations). The user no longer calls `eks-dx register-cluster` separately for provisioned tenants.

## Migration

No migration needed — no existing tenants. Implement directly with new naming convention.

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

1. **CLI supports `--cluster-name` lookup** — delete/get by cluster name via DynamoDB GSI (`clusterName-index`)
2. **8-char hex hash** — 32 bits of entropy, ~4 billion unique values
3. **No migration needed** — no existing tenants; implement directly with new naming
