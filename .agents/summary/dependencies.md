# Dependencies

## Core Framework
| Dependency | Version | Purpose |
|-----------|---------|---------|
| Quarkus Platform | 3.33.1 (LTS) | Application framework, DI, REST, Lambda integration |
| Java | 25 | Language runtime |
| Mandrel | 25 (jdk-25) | GraalVM native-image for tenant-service + CLI |

## AWS SDKs
| Dependency | Module | Purpose |
|-----------|--------|---------|
| quarkus-amazon-dynamodb | credential, mgmt, tenant | DynamoDB client (Quarkus-managed) |
| quarkus-amazon-iam | mgmt, tenant | IAM client (Quarkus-managed) |
| quarkus-amazon-sts | credential, tenant | STS AssumeRole (Quarkus-managed) |
| software.amazon.awssdk:ec2 | tenant | EC2 RunInstances, key pairs |
| software.amazon.awssdk:secretsmanager | tenant | Signing key + SSH key storage |
| software.amazon.awssdk:sqs | tenant | Karpenter interruption queue |
| software.amazon.awssdk:cloudwatchevents | tenant | EventBridge rules |
| software.amazon.awssdk:dlm | tenant | Data Lifecycle Manager |

## Infrastructure
| Dependency | Version | Purpose |
|-----------|---------|---------|
| aws-cdk-lib | 2.256.1 | CDK infrastructure definitions |
| constructs | 10.4.2 | CDK construct library |

## Kubernetes / Webhooks
| Dependency | Version | Purpose |
|-----------|---------|---------|
| quarkus-kubernetes-client | (BOM) | Fabric8 Kubernetes client |
| josdk-webhooks (kubernetes-webhooks-framework-core) | 3.0.3 | Admission webhook framework |
| quarkus-helm | 1.4.0 | Helm chart generation |

## Security / JWT
| Dependency | Module | Purpose |
|-----------|--------|---------|
| quarkus-smallrye-jwt | credential, mgmt | JWT/JWKS validation (jose4j) |

## CLI
| Dependency | Purpose |
|-----------|---------|
| picocli | Command-line parsing |
| quarkus-picocli | Quarkus integration for picocli |

## Build Tools
| Tool | Version | Purpose |
|------|---------|---------|
| Maven | 3.9+ | Build system |
| maven-compiler-plugin | 3.12.1 | Java compilation |
| surefire-plugin | 3.2.5 | Unit tests |
| Jib (quarkus-container-image-jib) | (BOM) | Container image builds |

## AWS Services Used at Runtime
| Service | Purpose |
|---------|---------|
| Lambda | Function execution (3 functions) |
| DynamoDB | Cluster, association, tenant state storage |
| API Gateway | REST API with IAM auth |
| STS | AssumeRole for pod credentials |
| Secrets Manager | SA signing keys, SSH keys |
| EC2 | Tenant instance lifecycle |
| SQS | Karpenter interruption queue |
| EventBridge | Spot interruption / state change events |
| DLM | Automated etcd volume snapshots |
| SSM Parameter Store | Infrastructure config interface |
| CloudWatch | Logs, metrics, alarms |
