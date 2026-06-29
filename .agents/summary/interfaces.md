# Interfaces

## CLI Commands

All commands use positional cluster name (AWS CLI style):

```
eks-dx create-cluster <name> [--arch arm64|x86_64] [--pricing spot|ondemand] [--wait]
eks-dx create-cluster <name> --jwks-file <path> --issuer <url>   (self-managed)
eks-dx delete-cluster <name>
eks-dx stop-cluster <name>
eks-dx resume-cluster <name>
eks-dx describe-cluster <name>
eks-dx list-clusters
eks-dx create-association --cluster-name <c> --namespace <ns> --service-account <sa> --role-arn <arn>
eks-dx delete-association --cluster-name <c> --association-id <id>
eks-dx describe-association --cluster-name <c> --association-id <id>
eks-dx list-associations --cluster-name <c>
eks-dx configure [--endpoint <url>] [--region <r>]
```

## Tenant Service REST API (Function URL)

| Method | Path | Mode | Description |
|--------|------|------|-------------|
| POST | `/clusters` | Both | Create cluster (server infers mode from `jwks` field presence) |
| DELETE | `/clusters/{name}` | Both | Delete cluster (managed=full teardown, self-managed=remove record) |
| POST | `/tenants` | Managed | Legacy: provision tenant (still supported) |
| GET | `/tenants/{id}` | — | Get tenant state |
| DELETE | `/tenants/{id}` | — | Deprovision tenant |
| POST | `/tenants/{id}/stop` | — | Hibernate instance |
| POST | `/tenants/{id}/resume` | — | Resume instance |
| GET | `/tenants/{id}/stream` | — | SSE progress stream |

### POST /clusters Request Body

```json
// Managed (no jwks → full provisioning)
{"clusterName": "my-cluster", "arch": "arm64", "ec2PricingModel": "spot", "diskSizeGb": 20}

// Self-managed (jwks present → register only)
{"clusterName": "my-k3s", "jwks": "{\"keys\":[...]}", "issuer": "https://..."}
```

## Mgmt Service REST API (API Gateway, IAM auth)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/clusters` | List clusters |
| GET | `/clusters/{name}` | Describe cluster |
| PUT | `/clusters/{name}/jwks` | Refresh JWKS |
| DELETE | `/clusters/{name}` | Deregister cluster |
| POST | `/clusters/{name}/pod-identity-associations` | Create association |
| GET | `/clusters/{name}/pod-identity-associations` | List associations |
| GET | `/clusters/{name}/pod-identity-associations/{id}` | Describe association |
| DELETE | `/clusters/{name}/pod-identity-associations/{id}` | Delete association |

## Credential Service REST API (API Gateway, no auth)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/clusters/{name}/assets` | Exchange token for AWS credentials |

## SSM Parameter Contract

| Parameter | Written by | Read by |
|-----------|-----------|---------|
| `/eks-d-xpress/infra/launch-template/{arch}/{pricing}` | Shared infra CDK | tenant-service |
| `/eks-d-xpress/infra/ami/{arch}/{k8s-version}` | AMI pipeline | tenant-service |
| `/eks-d-xpress/infra/network/vpc-id` | Shared infra CDK | tenant-service |
| `/eks-d-xpress/control-plane/api/endpoint` | Control plane CDK | CLI, EC2 boot |
| `/eks-d-xpress/control-plane/api/stream-url` | Control plane CDK | CLI |
| `/eks-d-xpress/control-plane/api/provisioning-url` | Control plane CDK | CLI |
