# Dependencies

## Runtime Dependencies by Module

### eks-dx-lambda
| Dependency | Version | Purpose |
|-----------|---------|---------|
| `quarkus-amazon-lambda-rest` | 3.20.3 | Wraps JAX-RS as Lambda handler |
| `quarkus-rest-jackson` | 3.20.3 | REST + JSON |
| `quarkus-amazon-dynamodb` | 3.20.3 | DynamoDB client (Quarkiverse) |
| `quarkus-amazon-sts` | 3.20.3 | STS AssumeRole |
| `quarkus-amazon-iam` | 3.20.3 | IAM GetRole (trust policy validation) |
| `quarkus-smallrye-jwt` | 3.20.3 | JWT/JWKS validation (jose4j) |
| `quarkus-smallrye-health` | 3.20.3 | Health endpoints |
| `software.amazon.awssdk:url-connection-client` | 2.29.15 | Sync HTTP client for AWS SDK |

### eks-dx-auth-proxy
| Dependency | Version | Purpose |
|-----------|---------|---------|
| `quarkus-rest-jackson` | 3.20.3 | REST + JSON |
| `quarkus-smallrye-health` | 3.20.3 | Health probes |
| `quarkus-micrometer-registry-prometheus` | 3.20.3 | Prometheus metrics |
| `io.fabric8:kubernetes-client` | 6.13.4 | Kubernetes TokenReview API |
| `quarkus-kubernetes-client` | 3.20.3 | Quarkus Fabric8 integration |
| `quarkus-container-image-jib` | 3.20.3 | Container image build |
| `quarkus-kubernetes` | 3.20.3 | K8s manifest generation |
| `quarkus-helm` (Quarkiverse) | 1.4.0 | Helm chart generation |

### eks-dx-pod-identity-webhook
| Dependency | Version | Purpose |
|-----------|---------|---------|
| `quarkus-rest-jackson` | 3.20.3 | REST + JSON |
| `quarkus-kubernetes-client` | 3.20.3 | K8s client |
| `quarkus-smallrye-health` | 3.20.3 | Health probes |
| `quarkus-container-image-jib` | 3.20.3 | Container image build |
| `quarkus-helm` (Quarkiverse) | 1.4.0 | Helm chart generation |
| `io.javaoperatorsdk:kubernetes-webhooks-framework-core` | 3.0.1 | Admission webhook framework |

### eks-dx-cli
| Dependency | Version | Purpose |
|-----------|---------|---------|
| `quarkus-picocli` | 3.20.3 | CLI framework |
| `quarkus-kubernetes-client` | 3.20.3 | OIDC/JWKS discovery from kube-apiserver |
| `quarkus-jackson` | 3.20.3 | JSON parsing |

### infra
| Dependency | Version | Purpose |
|-----------|---------|---------|
| `software.amazon.awscdk:aws-cdk-lib` | 2.180.0 | CDK constructs |
| `software.constructs:constructs` | 10.4.2 | CDK constructs base |

## Test Dependencies (all modules)
| Dependency | Version | Purpose |
|-----------|---------|---------|
| `quarkus-junit5` | 3.20.3 | Quarkus test framework |
| `quarkus-junit5-mockito` | 3.20.3 | Mockito integration |
| `io.rest-assured:rest-assured` | (managed) | HTTP API testing |
| `org.testcontainers:testcontainers` | 1.20.4 | Container-based integration tests (lambda only) |
| `io.fabric8:kubernetes-server-mock` | (managed) | Mock K8s server (cli only) |

## External AWS Services
| Service | Usage |
|---------|-------|
| AWS Lambda | Hosts eks-dx-lambda (Java 21, SnapStart) |
| API Gateway (REST v1) | Routes requests to Lambda |
| DynamoDB | Stores cluster registrations and pod identity associations |
| STS | `AssumeRole` + `TagSession` for credential exchange |
| IAM | `GetRole` for trust policy validation on association creation |
| CloudWatch Logs | API Gateway access logs (`/aws/apigateway/eks-dx`, 30-day retention) |
| CloudWatch Alarms | Lambda errors, throttles, p99 duration; DynamoDB throttles |

## IAM Permissions Required by Lambda
```
sts:AssumeRole    arn:aws:iam::*:role/eks-dx-pod-*
sts:TagSession    arn:aws:iam::*:role/eks-dx-pod-*
iam:GetRole       arn:aws:iam::*:role/*
dynamodb:*        eks-dx-clusters, eks-dx-associations tables
```

## IAM Permissions Required by CLI User
```
execute-api:Invoke  arn:aws:execute-api:{region}:{account}:{api-id}/prod/*/*
```
