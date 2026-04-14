# CRD-Based Pod Identity Association Design

## Problem Statement

The current implementation requires an AWS EKS managed cluster registered in the EKS Control Plane to validate pod identity associations via the `eks:DescribePodIdentityAssociation` API. This creates several limitations:

- **Cost**: $73/month per EKS control plane
- **Scope**: Only works with AWS-managed EKS, not EKS-D
- **Dependency**: Requires AWS API availability and credentials with `eks:*` permissions
- **Local Development**: Difficult to test without a real AWS cluster

## Proposed Solution

Replace the AWS EKS API dependency with a Kubernetes-native CRD-based storage layer. This approach:

1. Eliminates the need for an AWS-managed EKS cluster
2. Works seamlessly with EKS-D (self-managed Kubernetes)
3. Provides full control over pod identity association management
4. Maintains AWS CLI syntax compatibility for user experience
5. Reduces operational overhead and costs

## Architecture

### Current Flow
```
Pod Token → eks-auth-proxy → AWS EKS API (requires registered cluster) → STS AssumeRole
```

### Proposed Flow
```
Pod Token → eks-auth-proxy → Kubernetes CRD (local cluster) → STS AssumeRole
```

## Implementation Phases

### Phase 1: CRD-Based Storage (MVP)

#### 1.1 Custom Resource Definition

Create `PodIdentityAssociation` CRD to store associations in the cluster:

```yaml
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata:
  name: podidentityassociations.eks.amazonaws.com
spec:
  group: eks.amazonaws.com
  names:
    kind: PodIdentityAssociation
    plural: podidentityassociations
  scope: Namespaced
  versions:
  - name: v1
    served: true
    storage: true
    schema:
      openAPIV3Schema:
        type: object
        properties:
          spec:
            type: object
            properties:
              clusterName:
                type: string
                description: "EKS cluster name"
              namespace:
                type: string
                description: "Kubernetes namespace"
              serviceAccount:
                type: string
                description: "Service account name"
              roleArn:
                type: string
                description: "IAM role ARN to assume"
            required: [clusterName, namespace, serviceAccount, roleArn]
```

#### 1.2 Service Layer Update

Modify `PodIdentityAssociationService.java` to query CRDs instead of AWS EKS API:

```java
@ApplicationScoped
public class PodIdentityAssociationService {
    @Inject
    KubernetesClient k8sClient;

    public String getRoleArn(String clusterName, String namespace, String serviceAccount) {
        // Query CRD: clusterName + namespace + serviceAccount
        var crd = k8sClient.customResource(PodIdentityAssociation.class)
            .inNamespace(namespace)
            .withName(clusterName + "-" + serviceAccount)
            .get();
        
        if (crd != null) {
            return crd.getSpec().getRoleArn();
        }
        
        // Fallback to ConfigMap (backward compatibility)
        return getFromConfigMap(clusterName, namespace, serviceAccount);
    }
}
```

**Lookup Priority**:
1. Kubernetes CRD (primary)
2. ConfigMap `kube-system/pod-identity-associations` (fallback)
3. Generated default: `arn:aws:iam::<AWS_ACCOUNT_ID>:role/eks-pod-identity-<ns>-<sa>` (last resort)

#### 1.3 Benefits

- ✅ No AWS EKS API dependency
- ✅ Works with EKS-D and any Kubernetes cluster
- ✅ Native Kubernetes storage (etcd)
- ✅ Backward compatible with ConfigMap fallback
- ✅ Minimal code changes to existing service

---

### Phase 2: CLI Tool with AWS CLI Syntax

#### 2.1 CLI Commands

Implement a Picocli-based CLI that mimics AWS CLI syntax:

```bash
# Create association
eks-pod-identity-association create \
  --cluster-name my-cluster \
  --service-account default:my-sa \
  --role-arn arn:aws:iam::123456789012:role/my-role

# Delete association
eks-pod-identity-association delete \
  --cluster-name my-cluster \
  --service-account default:my-sa

# Describe association
eks-pod-identity-association describe \
  --cluster-name my-cluster \
  --service-account default:my-sa

# List associations
eks-pod-identity-association list \
  --cluster-name my-cluster
```

#### 2.2 Implementation Structure

```
eks-auth-proxy/src/main/java/com/plcloud/eksauth/cli/
├── PodIdentityCommand.java          # Main command group
├── CreateCommand.java               # create subcommand
├── DeleteCommand.java               # delete subcommand
├── DescribeCommand.java             # describe subcommand
├── ListCommand.java                 # list subcommand
└── CliMain.java                     # Quarkus CLI entry point
```

#### 2.3 Key Features

- **AWS CLI Compatibility**: Exact syntax as `aws eks create-pod-identity-association`
- **Service Account Format**: Accepts `namespace:serviceaccount` format
- **Error Handling**: Clear error messages matching AWS CLI style
- **Validation**: Pre-flight checks before CRD creation

#### 2.4 Build Configuration

Add Picocli dependency and configure Quarkus CLI packaging:

```xml
<dependency>
    <groupId>info.picocli</groupId>
    <artifactId>picocli</artifactId>
    <version>4.7.5</version>
</dependency>
```

---

### Phase 3: CloudFormation Template Support (Future)

#### 3.1 Scope

Support CloudFormation templates with `AWS::EKS::PodIdentityAssociation` resources:

```yaml
Resources:
  MyPodIdentityAssociation:
    Type: AWS::EKS::PodIdentityAssociation
    Properties:
      ClusterName: my-cluster
      Namespace: default
      ServiceAccountName: my-sa
      RoleArn: arn:aws:iam::123456789012:role/my-role
```

#### 3.2 Implementation

- Parse CloudFormation templates (YAML/JSON)
- Extract `AWS::EKS::PodIdentityAssociation` resources
- Delegate to CLI commands for CRD creation
- Support `create-stack`, `update-stack`, `delete-stack` operations

---

## Integration with Existing Components

### EKS Pod Identity Webhook

The webhook continues to operate unchanged:
- Intercepts pod creation
- Injects `AWS_CONTAINER_CREDENTIALS_FULL_URI` and `AWS_CONTAINER_AUTHORIZATION_TOKEN`
- Points to local `eks-auth-proxy` service

### EKS Pod Identity Agent

The agent (running in the cluster) continues to:
- Validate pod identity via webhook
- Call `eks-auth-proxy` for credential exchange
- Receive temporary AWS credentials

### eks-auth-proxy Service

Enhanced to:
- Query CRDs instead of AWS EKS API
- Maintain all existing validation logic
- Support both HTTP API and CLI modes

---

## Deployment Architecture

```
┌─────────────────────────────────────────────────────────┐
│ Kubernetes Cluster (EKS-D or self-managed)              │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │ kube-system namespace                            │  │
│  ├──────────────────────────────────────────────────┤  │
│  │ • eks-pod-identity-webhook (mutating webhook)    │  │
│  │ • eks-auth-proxy (service)                       │  │
│  │ • PodIdentityAssociation CRDs (etcd)             │  │
│  └──────────────────────────────────────────────────┘  │
│                                                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │ User Namespace                                   │  │
│  ├──────────────────────────────────────────────────┤  │
│  │ • Pod (with service account)                     │  │
│  │   ↓ (webhook injects env vars)                   │  │
│  │   ↓ (pod calls eks-auth-proxy)                   │  │
│  │   ↓ (receives AWS credentials)                   │  │
│  └──────────────────────────────────────────────────┘  │
│                                                         │
└─────────────────────────────────────────────────────────┘
         ↓
    AWS STS (AssumeRole)
```

---

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `AWS_ACCOUNT_ID` | AWS account ID (for generated role ARNs) | Required |
| `AWS_ACCESS_KEY_ID` | AWS credentials for STS | Required |
| `AWS_SECRET_ACCESS_KEY` | AWS credentials for STS | Required |
| `AWS_REGION` | AWS region | `us-east-1` |

### Application Properties

| Property | Default | Description |
|----------|---------|-------------|
| `eks.pod-identity.configmap.name` | `pod-identity-associations` | Fallback ConfigMap name |
| `eks.pod-identity.configmap.namespace` | `kube-system` | Fallback ConfigMap namespace |
| `aws.sts.session-duration` | `PT1H` | STS session duration |

---

## Migration Path

### From AWS EKS API to CRDs

1. **Existing ConfigMap associations**: Continue to work (fallback)
2. **New associations**: Created as CRDs via CLI
3. **Gradual migration**: No breaking changes, backward compatible
4. **No downtime**: Service continues to validate tokens during transition

### Example Migration

```bash
# Old way (ConfigMap)
kubectl create configmap pod-identity-associations \
  -n kube-system \
  --from-literal="my-cluster:default:my-sa=arn:aws:iam::123456789012:role/my-role"

# New way (CRD)
eks-pod-identity-association create \
  --cluster-name my-cluster \
  --service-account default:my-sa \
  --role-arn arn:aws:iam::123456789012:role/my-role
```

---

## Security Considerations

### RBAC

Restrict CRD access to authorized users/service accounts:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: pod-identity-admin
  namespace: kube-system
rules:
- apiGroups: ["eks.amazonaws.com"]
  resources: ["podidentityassociations"]
  verbs: ["create", "delete", "get", "list", "update"]
```

### AWS Credentials

- Stored in environment variables or AWS credential chain
- Used only for STS AssumeRole calls
- No credentials stored in CRDs
- Webhook validates pod identity before credential issuance

---

## Testing Strategy

### Unit Tests

- CRD parsing and validation
- CLI command parsing
- Service layer logic

### Integration Tests

- CRD creation and retrieval
- CLI end-to-end workflows
- Token validation with CRD-backed associations
- STS AssumeRole with CRD-resolved roles

### E2E Tests

- Full pod identity flow with EKS-D cluster
- Webhook injection + credential retrieval
- Multiple associations and namespaces

---

## Cost Analysis

### Before (AWS EKS)
- EKS Control Plane: $73/month
- Data transfer: ~$0.02/GB
- **Total**: ~$73/month minimum

### After (CRD-based)
- Kubernetes cluster: Self-managed or EKS-D (no additional cost)
- Storage: etcd (included in cluster)
- **Total**: $0 additional cost

**Savings**: $73/month per cluster

---

## Timeline

| Phase | Effort | Timeline |
|-------|--------|----------|
| Phase 1: CRD Storage | 2-3 days | Week 1 |
| Phase 2: CLI Tool | 3-4 days | Week 2 |
| Phase 3: CloudFormation | 2-3 days | Week 3 |
| Testing & Documentation | 2-3 days | Week 4 |

---

## References

- [Kubernetes CRD Documentation](https://kubernetes.io/docs/tasks/extend-kubernetes/custom-resources/custom-resource-definitions/)
- [Picocli Documentation](https://picocli.info/)
- [AWS EKS Pod Identity](https://docs.aws.amazon.com/eks/latest/userguide/pod-identities.html)
- [Quarkus CLI Guide](https://quarkus.io/guides/picocli)
