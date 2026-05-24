# CDK Migration Plan

Migrate infrastructure definition from SAM (`sam.yaml`) to CDK (`infra/`) to enable
single-tool deployment across `eks-dx-control-plane` and `ecp-eks-dx-infra`, composable
in a future `eks-d-xpress-release` repo.

## Target tag: `v1.0.0-cdk-migration`

## Rationale

- SAM is serverless-only — cannot manage VPC, EC2, AMI pipelines needed by tenant infra
- Terraform (used in `ecp-eks-dx-infra`) is not AWS-native and has no construct-sharing model
- CDK covers the full stack in Java (consistent with existing codebase), compiles to CloudFormation,
  and supports the release-repo composition pattern:

```
eks-dx-control-plane  →  ControlPlaneStack (Lambda + DynamoDB + API GW)
ecp-eks-dx-infra      →  SharedInfraStack  (VPC + AMI builder + ECR)
eks-d-xpress-release  →  CDK app importing both as Maven dependencies
```

SAM and CDK can coexist during migration (`sam local --hook-name cdk` for local Lambda testing).

## Gap analysis: SAM vs current CDK

| Area | SAM | Current CDK | Gap |
|---|---|---|---|
| Lambda functions | 3 separate (credential, mgmt, tenant) | 1 monolithic | Split into 3 |
| Tenant function | GraalVM native, arm64, 900s, Function URL RESPONSE_STREAM | Missing | Add entirely |
| TenantsTable | ✅ | ❌ | Add |
| SSM Parameters | 2 (launch-template-id, subnet-id) | ❌ | Add |
| IAM: credential | `dynamodb:GetItem` only + `sts:AssumeRole/TagSession` | ReadWrite (too broad) | Tighten |
| IAM: mgmt | CrudPolicy + `iam:GetRole` | CrudPolicy, no `iam:GetRole` | Add missing action |
| IAM: tenant | EC2 + IAM + SecretsManager + STS | ❌ | Add entirely |
| API routes | Specific methods + per-route auth | `ANY` + wrong auth | Fix |
| CloudWatch alarms | Per-function, named to match SAM | Generic single-function | Fix |
| Custom domain | Conditional (DomainName param) | ❌ | Add |
| Outputs | 9 outputs incl. Function URL | 3 outputs | Complete |
| `cdk.json` mainClass | — | Wrong package (`cloud.plasticity`) | Fix bug |

## Steps

### Step 1 — Fix `cdk.json` bug

`cdk.json` has `cloud.plasticity.eksdx.infra.InfraApp` but the actual class is
`ai.codriverlabs.eksdx.infra.InfraApp`. Fix so `cdk synth` works.

### Step 2 — Split monolithic Lambda into 3 functions

**`credentialFn`** — mirrors `EksDxCredentialFunction`:
- `eks-dx-credential-service/target/function.zip`
- Runtime: JAVA_21, 512 MB, 30 s, SnapStart
- IAM: `dynamodb:GetItem` on clusters + associations ARNs only
- IAM: `sts:AssumeRole` + `sts:TagSession` on `arn:aws:iam::*:role/eks-dx-pod-*`

**`mgmtFn`** — mirrors `EksDxMgmtFunction`:
- `eks-dx-mgmt-service/target/function.zip`
- Runtime: JAVA_21, 256 MB, 30 s, no SnapStart
- IAM: `grantReadWriteData` on clusters + associations
- IAM: `iam:GetRole` on `arn:aws:iam::*:role/*` ← missing from current CDK

**`tenantFn`** — mirrors `EksDxTenantFunction` (entirely new):
- `eks-dx-tenant-service/target/function.zip`
- Runtime: PROVIDED_AL2023, Architecture: ARM_64, 128 MB, 900 s
- Function URL: `AuthType.AWS_IAM`, `InvokeMode.RESPONSE_STREAM`
- Env vars: `EKS_DX_TENANTS_TABLE`, `EKS_DX_CLUSTERS_TABLE`, `EKS_DX_LAUNCH_TEMPLATE_ID`, `EKS_DX_SUBNET_ID`
- IAM: `grantReadWriteData` on tenantsTable
- IAM: `dynamodb:DeleteItem` on clustersTable ARN
- IAM: `ec2:RunInstances`, `TerminateInstances`, `CreateKeyPair`, `DeleteKeyPair`, `DescribeInstances`, `CreateTags` on `*`
- IAM: `iam:CreateRole`, `DeleteRole`, `PutRolePolicy`, `DeleteRolePolicy`, `PassRole` on `arn:aws:iam::*:role/eks-dx-tenant-*`
- IAM: `secretsmanager:CreateSecret`, `DeleteSecret`, `GetSecretValue` on `arn:aws:secretsmanager:*:*:secret:eks-dx/tenant/*`
- IAM: `sts:GetCallerIdentity` on `*`

### Step 3 — Add TenantsTable

```java
Table tenantsTable = Table.Builder.create(this, "TenantsTable")
    .tableName("eks-dx-tenants")
    .partitionKey(Attribute.builder().name("tenantId").type(AttributeType.STRING).build())
    .billingMode(BillingMode.PAY_PER_REQUEST)
    .removalPolicy(RemovalPolicy.RETAIN)
    .pointInTimeRecovery(true)
    .build();
```

### Step 4 — Add SSM Parameters

```java
StringParameter.Builder.create(this, "LaunchTemplateId")
    .parameterName("/eks-dx/tenant/launch-template-id")
    .stringValue("lt-placeholder").build();

StringParameter.Builder.create(this, "SubnetId")
    .parameterName("/eks-dx/tenant/subnet-id")
    .stringValue("subnet-placeholder").build();
```

### Step 5 — Fix API routes

Replace `ANY` with explicit methods and correct auth per route:

| Method | Path | Auth | Function |
|---|---|---|---|
| POST | `/clusters` | IAM | mgmt |
| GET | `/clusters` | IAM | mgmt |
| GET | `/clusters/{name}` | IAM | mgmt |
| DELETE | `/clusters/{name}` | IAM | mgmt |
| PUT | `/clusters/{name}/jwks` | IAM | mgmt |
| POST | `/clusters/{name}/assets` | NONE | credential |
| GET | `/clusters/{name}/pod-identity-associations` | NONE | mgmt |
| POST | `/clusters/{name}/pod-identity-associations` | IAM | mgmt |
| GET | `/clusters/{name}/pod-identity-associations/{id}` | NONE | mgmt |
| DELETE | `/clusters/{name}/pod-identity-associations/{id}` | IAM | mgmt |
| POST | `/tenants` | IAM | tenant |
| GET | `/tenants/{id}` | IAM | tenant |
| DELETE | `/tenants/{id}` | IAM | tenant |

### Step 6 — Fix CloudWatch alarms

Replace generic alarms with SAM-equivalent named alarms:

- `eks-dx-credential-errors` — `credentialFn.metricErrors`, Sum, threshold 5, 1 period
- `eks-dx-credential-p99-duration` — `credentialFn.metricDuration(statistic="p99")`, threshold 5000, 3 periods
- `eks-dx-mgmt-errors` — `mgmtFn.metricErrors`, Sum, threshold 5, 1 period

### Step 7 — Add custom domain (conditional)

```java
CfnParameter domainName = new CfnParameter(this, "DomainName",
    CfnParameterProps.builder().type("String").defaultValue("").build());
CfnParameter certArn = new CfnParameter(this, "CertificateArn",
    CfnParameterProps.builder().type("String").defaultValue("").build());
CfnCondition hasCustomDomain = new CfnCondition(this, "HasCustomDomain",
    CfnConditionProps.builder()
        .expression(Fn.conditionNot(Fn.conditionEquals(domainName.getValueAsString(), "")))
        .build());
// CfnResource ApiDomainName + ApiMapping gated on hasCustomDomain
```

### Step 8 — Complete outputs

Add: `TenantsTableName`, `TenantStreamFunctionUrl`, `CredentialFunctionName`,
`MgmtFunctionName`, `TenantFunctionName`, `EksDxCliPolicyResource`, `CustomEndpoint` (conditional).

### Step 9 — Update GitHub Actions

**`ci.yml`** — replace SAM validate with CDK synth:
```yaml
# Remove:
- name: Validate SAM template
  uses: aws-actions/setup-sam@v2
- run: sam validate -t sam.yaml --lint

# Add:
- name: Synth CDK stack
  run: mvn -B -pl infra compile exec:java
```

**`release.yml`** — replace SAM packaging with CDK synth artifact:
```yaml
# Remove:
- name: Package SAM template
  run: tar czf eks-dx-sam-${VERSION}.tar.gz sam.yaml

# Add:
- name: Synth and package CDK template
  run: |
    VERSION=${GITHUB_REF_NAME#v}
    mvn -B -pl infra compile exec:java
    tar czf eks-dx-cdk-${VERSION}.tar.gz -C infra cdk.out
```

**`deploy-lambda.yml`** — replace `update-function-code` with `cdk deploy`:
```yaml
- name: Deploy via CDK
  run: |
    cd infra && cdk deploy EksDxStack --require-approval never \
      --context account=$(aws sts get-caller-identity --query Account --output text) \
      --context region=${{ inputs.region }}
```

Keep a separate `deploy-lambda-hotfix.yml` with `update-function-code` for fast
code-only updates that don't touch infrastructure.

### Step 10 — Tag

```bash
git add infra/ .github/workflows/
git commit -m "feat(infra): CDK parity with SAM — split functions, tenants table, Function URL, IAM fixes"
git tag v1.0.0-cdk-migration
git push origin v1.0.0-cdk-migration
```

## What stays unchanged

- `sam.yaml` — kept as reference/fallback, not deleted until CDK deploy is validated in prod
- `samconfig.toml` — kept alongside sam.yaml
- All Quarkus modules, Helm charts, CLI, webhook — no changes

## Estimated effort

Steps 1–8 are all in `EksDxStack.java` + `InfraApp.java`. Step 9 touches 3 workflow files.
Total: ~4–6 hours of focused work.
