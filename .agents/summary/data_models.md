# Data Models

## DynamoDB: eks-dx-clusters

**Key schema:** `clusterName` (String, HASH)

| Attribute | Type | Description |
|-----------|------|-------------|
| `clusterName` | S | Cluster identifier (PK) |
| `issuer` | S | OIDC issuer URL |
| `jwks` | S | JWKS JSON (public keys for JWT validation) |
| `createdAt` | S | ISO-8601 timestamp |
| `updatedAt` | S | ISO-8601 timestamp |

---

## DynamoDB: eks-dx-associations

**Key schema:** `PK` (String, HASH) + `SK` (String, RANGE)

| Attribute | Type | Description |
|-----------|------|-------------|
| `PK` | S | `CLUSTER#<clusterName>` |
| `SK` | S | `<namespace>#<serviceAccount>` |
| `associationId` | S | `assoc-<12-char UUID prefix>` |
| `clusterName` | S | Cluster name (denormalized) |
| `namespace` | S | Kubernetes namespace |
| `serviceAccount` | S | Kubernetes service account name |
| `roleArn` | S | IAM role ARN to assume |
| `createdAt` | S | ISO-8601 timestamp |

**Access patterns:**
- Get role ARN for a pod: `GetItem(PK=CLUSTER#name, SK=ns#sa)` → O(1)
- List all associations for a cluster: `Query(PK=CLUSTER#name)` → O(n)
- List by namespace: `Query(PK=CLUSTER#name, SK begins_with ns#)` → O(n)
- Describe by associationId: `Scan(PK=CLUSTER#name, filter associationId=id)` → O(n) full scan

---

## Java Records / Classes

### `TokenClaims` (lambda module — record)
```
namespace, serviceAccount, serviceAccountUid, podName, podUid, subject
sessionTags() → Map<String,String>
```

### `TokenValidationService.TokenClaims` (auth-proxy module — class)
Same fields plus `expiration: Instant`. Separate class, not shared across modules.

### API DTOs (EksAuthResource inner classes)
```
AgentRequest       { token }
AgentResponse      { credentials, assumedRoleUser, podIdentityAssociation, subject, audience }
CredentialsDto     { accessKeyId, secretAccessKey, sessionToken, expiration (epoch seconds) }
AssumedRoleUserDto { arn, assumeRoleId }
AssociationDto     { associationArn, associationId }
SubjectDto         { namespace, serviceAccount }
```

### `JwksTokenValidationService.CachedContext` (private record)
```
contextInfo: JWTAuthContextInfo
loadedAt: Instant
isExpired() → true if now > loadedAt + 300s
```

---

## Session Tags (STS)

Tags added to every `AssumeRole` call:

| Tag Key | Source |
|---------|--------|
| `eks-cluster-name` | Path parameter `clusterName` |
| `kubernetes-namespace` | JWT claim / TokenReview |
| `kubernetes-service-account` | JWT claim / TokenReview |
| `kubernetes-pod-name` | JWT claim `kubernetes.io/pod/name` (nullable) |
| `kubernetes-pod-uid` | JWT claim `kubernetes.io/pod/uid` (nullable) |

Empty/null tag values are skipped.
