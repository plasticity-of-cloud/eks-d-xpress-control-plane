# Dependencies

## Framework
- **Quarkus 3.35.4 LTS** — Lambda runtime, REST, CDI, native image support
- **Picocli** — CLI command framework (via quarkus-picocli)

## AWS SDK v2
- `dynamodb` — Table operations (clusters, associations, tenants)
- `ec2` — Instance launch, subnets, security groups, key pairs, EIP
- `iam` — Role/policy/instance profile management
- `sts` — AssumeRole, GetCallerIdentity
- `secretsmanager` — Signing keys, SSH keys
- `dlm` — EBS snapshot lifecycle policies
- `sqs` — Spot interruption queue
- `eventbridge` — Spot termination event rules
- `ssm` — Parameter Store reads

## Security
- **jose4j** — JWKS fetching, JWT validation (credential-service)

## Infrastructure
- **AWS CDK (Java)** — Stack definition, deployment

## Testing
- JUnit 5
- Mockito
- Quarkus test framework
- DynamoDB Local (integration tests)

## Container Images
- `quarkus-container-image-jib` — auth-proxy, webhook builds
