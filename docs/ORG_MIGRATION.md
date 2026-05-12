# Org Migration: plasticity-of-cloud → codriverlabs.ai

Pre-migration snapshot tagged: **v1.0.1**

## Scope

| Category | Old | New |
|----------|-----|-----|
| GitHub org | `plasticity-of-cloud` | `codriverlabs` |
| Domain | `plasticity.cloud` | `codriverlabs.ai` |
| Java package root | `cloud.plasticity` | `ai.codriverlabs` |
| Maven groupId | `cloud.plasticity` | `ai.codriverlabs` |
| Container image group | `plcloud` | `codriverlabs` |
| GHCR registry path | `ghcr.io/plasticity-of-cloud/` | `ghcr.io/codriverlabs/` |
| Default API endpoint | `https://eks-dx.plasticity.cloud` | `https://eks-dx.codriverlabs.ai` |
| Webhook audience | `eks-dx.plasticity.cloud` | `eks-dx.codriverlabs.ai` |
| CRD group | `eks.plasticity.cloud` | `eks.codriverlabs.ai` |
| Webhook name | `pod-identity.plasticity.cloud` | `pod-identity.codriverlabs.ai` |

## Files to Change

### Java source — package rename (`cloud.plasticity` → `ai.codriverlabs`)

Affects all `.java` files under:
- `eks-dx-lambda/src/`
- `eks-dx-auth-proxy/src/`
- `eks-dx-cli/src/`
- `eks-dx-pod-identity-webhook/src/`
- `infra/src/`

Also update directory structure:
```
src/main/java/cloud/plasticity/  →  src/main/java/ai/codriverlabs/
src/test/java/cloud/plasticity/  →  src/test/java/ai/codriverlabs/
```

### Maven POMs

All `pom.xml` files (root + 5 modules):
```xml
<!-- before -->
<groupId>cloud.plasticity</groupId>
<!-- after -->
<groupId>ai.codriverlabs</groupId>
```

`infra/pom.xml` — also update `<mainClass>`:
```xml
<!-- before -->
<mainClass>cloud.plasticity.eksdx.infra.InfraApp</mainClass>
<!-- after -->
<mainClass>ai.codriverlabs.eksdx.infra.InfraApp</mainClass>
```

### application.properties (all modules)

| Property | Old value | New value |
|----------|-----------|-----------|
| `eks-dx.endpoint` default | `https://eks-dx.plasticity.cloud` | `https://eks-dx.codriverlabs.ai` |
| `quarkus.log.category` | `"cloud.plasticity"` | `"ai.codriverlabs"` |
| `quarkus.container-image.group` | `plcloud` | `codriverlabs` |
| `quarkus.container-image.registry` | `864899852480.dkr.ecr...` | unchanged (ECR stays) |
| `quarkus.kubernetes.env.vars.EKS_DX_ENDPOINT` | `https://eks-dx.plasticity.cloud` | `https://eks-dx.codriverlabs.ai` |

### Runtime constants (code changes)

| File | Constant | Old | New |
|------|----------|-----|-----|
| `JwksTokenValidationService.java` | `EKS_DX_AUDIENCE` | `eks-dx.plasticity.cloud` | `eks-dx.codriverlabs.ai` |
| `JwksTokenValidationServiceTest.java` | `DX_AUDIENCE` | `eks-dx.plasticity.cloud` | `eks-dx.codriverlabs.ai` |
| `EksDxConfig.java` | default endpoint | `https://eks-dx.plasticity.cloud` | `https://eks-dx.codriverlabs.ai` |
| `LambdaAssociationLookup.java` | comment | `eks-dx.plasticity.cloud` | `eks-dx.codriverlabs.ai` |

### Kubernetes / Helm manifests

| File | Field | Old | New |
|------|-------|-----|-----|
| `deploy/eks-dx-auth-proxy.yaml` | `apiGroups` | `eks.plasticity.cloud` | `eks.codriverlabs.ai` |
| `deploy/eks-dx-auth-proxy.yaml` | `image` | `plcloud/eks-dx-auth-proxy` | `codriverlabs/eks-dx-auth-proxy` |
| `eks-dx-pod-identity-webhook/k8s/deployment.yaml` | `apiGroups` | `eks.plasticity.cloud` | `eks.codriverlabs.ai` |
| `eks-dx-pod-identity-webhook/k8s/deployment.yaml` | `image` | `plcloud/eks-dx-pod-identity-webhook` | `codriverlabs/eks-dx-pod-identity-webhook` |
| `eks-dx-pod-identity-webhook/k8s/mutating-webhook-configuration.yaml` | `name` | `pod-identity.plasticity.cloud` | `pod-identity.codriverlabs.ai` |
| `eks-dx-auth-proxy/src/main/helm/crds/*.yaml` | `group` | `eks.plasticity.cloud` | `eks.codriverlabs.ai` |
| `eks-dx-auth-proxy/src/main/helm/values.yaml` | `repository` | `plcloud/eks-dx-auth-proxy` | `codriverlabs/eks-dx-auth-proxy` |
| `eks-dx-pod-identity-webhook/src/main/helm/values.yaml` | `repository` | `plcloud/eks-dx-pod-identity-webhook` | `codriverlabs/eks-dx-pod-identity-webhook` |
| `eks-dx-auth-proxy/src/main/kubernetes/kubernetes.yml` | `apiGroups` | `eks.plasticity.cloud` | `eks.codriverlabs.ai` |
| `eks-dx-pod-identity-webhook/src/main/kubernetes/kubernetes.yml` | `apiGroups` + webhook name | `eks.plasticity.cloud` / `pod-identity.plasticity.cloud` | `eks.codriverlabs.ai` / `pod-identity.codriverlabs.ai` |

### CI/CD

| File | Change |
|------|--------|
| `.github/workflows/release.yml` | `GITHUB_ORG` / registry paths: `plasticity-of-cloud` → `codriverlabs` |
| `docs/user_guides/ec2-k3s-pod-identity/setup.sh` | `GITHUB_ORG="plasticity-of-cloud"` → `GITHUB_ORG="codriverlabs"` |

### Documentation

Global find-and-replace across `docs/`, `README.md`, `AGENTS.md`:
- `plasticity-of-cloud` → `codriverlabs`
- `plasticity.cloud` → `codriverlabs.ai`
- `plcloud` → `codriverlabs`

## Execution Order

1. Rename Java source directories (`cloud/plasticity` → `ai/codriverlabs`)
2. Update all `package` and `import` statements
3. Update all `pom.xml` groupIds and mainClass
4. Update `application.properties` in all modules
5. Update runtime constants in Java source
6. Update Kubernetes/Helm manifests
7. Update CI/CD workflows and setup scripts
8. Update documentation
9. Run `mvn verify` — all 192 unit tests + 16 integration tests must pass
10. Commit as single atomic commit: `refactor: migrate org from plasticity-of-cloud to codriverlabs.ai`

## Notes

- The **webhook audience** (`eks-dx.codriverlabs.ai`) is a runtime value embedded in deployed tokens. Any existing clusters must re-register and re-deploy the webhook after migration.
- The **CRD group** change (`eks.codriverlabs.ai`) requires deleting and re-applying CRDs in existing clusters.
- ECR registry (`864899852480.dkr.ecr.us-east-1.amazonaws.com`) is unchanged — only the image group/repository path changes.
