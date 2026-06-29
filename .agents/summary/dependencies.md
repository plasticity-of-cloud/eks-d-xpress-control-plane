# Dependencies

## AWS Services

| Service | Used by | Purpose |
|---------|---------|---------|
| DynamoDB | credential, mgmt, tenant | Cluster registry, associations, tenant state |
| STS | credential, tenant | AssumeRole (credentials), GetCallerIdentity (account) |
| IAM | tenant | Create/delete tenant roles + instance profiles |
| EC2 | tenant | Launch/terminate instances, subnets, SGs, EIPs |
| KMS | tenant | Sign CA certificates (asymmetric RSA-2048) |
| Secrets Manager | tenant | Store CA key, SA key, SSH key per tenant |
| SQS | tenant | Karpenter interruption queue per cluster |
| EventBridge | tenant | Spot interruption / rebalance event rules |
| DLM | tenant | Daily etcd volume snapshot policies |
| SSM Parameter Store | CDK, tenant, CLI | Configuration interface between stacks |
| Lambda | all | Compute runtime |
| API Gateway | credential, mgmt | REST API with IAM auth |
| CloudWatch Logs | all | Logging |

## Java Libraries

| Library | Module | Purpose |
|---------|--------|---------|
| Quarkus 3.36+ | all | Application framework, DI, REST, Lambda integration |
| AWS SDK v2 | all | EC2, IAM, STS, DynamoDB, KMS, SM, SQS, Events, DLM, SSM |
| Bouncy Castle (bcpkix-jdk18on 1.84) | tenant | X.509 cert construction, KMS ContentSigner |
| Fabric8 Kubernetes Client | auth-proxy | TokenReview API calls |
| Jackson | all | JSON serialization |
| Picocli | cli | Command-line parsing |
| SmallRye JWT | credential, mgmt | JWT/JWKS validation |
| Lambda Web Adapter | tenant | Bridges HTTP server to Lambda streaming Runtime API |

## Build Tools

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 25 | Runtime (GraalVM JDK for native) |
| Maven | 3.9+ | Build system |
| Docker | — | Native builds via Mandrel container |
| AWS CDK | 2.260+ | Infrastructure deployment |

## Container Images

| Image | Purpose | Registry |
|-------|---------|----------|
| eks-dx-auth-proxy | In-cluster proxy | GHCR / ECR |
| eks-dx-pod-identity-webhook | Admission webhook | GHCR / ECR |
| eks-dx-karpenter-support | EC2NodeClass webhook + reconciler | ECR |
| eks-pod-identity-agent | AWS DaemonSet (169.254.170.23) | ECR (602401143452) |
