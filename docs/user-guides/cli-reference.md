# EKS-DX CLI Reference

## Installation

Build the native binary (requires GraalVM JDK 25):

```bash
./build-local.sh --only cli --native
# Binary: eks-dx-cli/target/eks-dx
```

Or run from the uber-jar (JVM, no native build required):

```bash
./build-local.sh --only cli
java -jar eks-dx-cli/target/*-runner.jar --help
```

---

## Global options

| Flag | Description |
|------|-------------|
| `--help`, `-h` | Print help |
| `--version`, `-V` | Print version |

AWS credentials are resolved via the standard chain: env vars → `~/.aws/credentials` →
instance profile → IAM Identity Center (SSO). The region defaults to `AWS_REGION` env
var or the value stored in `~/.eks-d-xpress/config`.

---

## Configuration

### `eks-dx configure`

Saves the API endpoint and region to `~/.eks-d-xpress/config`. Required once before
using any other command.

```bash
eks-dx configure --endpoint https://<api-id>.execute-api.us-east-1.amazonaws.com \
                 --region us-east-1
```

If the endpoint is already published to SSM (`/eks-d-xpress/control-plane/api/endpoint`),
the CLI resolves it automatically and you can skip `configure`.

---

## Cluster commands

### `eks-dx create-cluster <name>`

Create a managed cluster (full EKS-D provisioning on EC2) or register a self-managed one
(k3s, microk8s, EKS-D kubeadm).

The server infers the mode from the request: providing `--jwks-uri`/`--jwks-file` and
`--issuer` triggers self-managed registration; omitting them triggers full provisioning.

**Managed mode (default)**

```bash
eks-dx create-cluster my-cluster
eks-dx create-cluster my-cluster --arch arm64 --pricing spot --k8s-version 1.35
eks-dx create-cluster my-cluster --wait          # stream provisioning progress
eks-dx create-cluster my-cluster --ssh-cidr 203.0.113.10/32
```

| Flag | Default | Description |
|------|---------|-------------|
| `--arch` | `arm64` | CPU architecture: `arm64` or `x86_64` |
| `--pricing` | `spot` | EC2 pricing model: `spot` or `ondemand` |
| `--k8s-version` | `1.35` | Kubernetes version |
| `--disk-size` | `20` | Root disk size in GB |
| `--ssh-cidr` | caller IP/32 | CIDR allowed for SSH access |
| `--wait` | off | Stream provisioning progress and save SSH key on completion |
| `--output` | `text` | `text` or `json` |

**Self-managed mode**

```bash
# From a reachable cluster via kubeconfig (auto-discovery):
eks-dx create-cluster my-k3s --kubeconfig ~/.kube/config

# Explicit JWKS + issuer:
eks-dx create-cluster my-k3s \
  --jwks-uri https://my-cluster/.well-known/openid/v1/jwks \
  --issuer https://my-cluster

# From a local JWKS file:
eks-dx create-cluster my-k3s \
  --jwks-file ./jwks.json \
  --issuer https://my-cluster
```

**Error: duplicate name**

If the cluster name already exists the server returns 409 and the CLI prints:

```
Error: Cluster 'my-cluster' already exists. To replace it, run: eks-dx delete-cluster my-cluster
```

---

### `eks-dx delete-cluster <name>`

Delete a cluster. For managed clusters performs full teardown (EC2, IAM, network,
secrets). For self-managed clusters removes the DynamoDB registration only.

```bash
eks-dx delete-cluster my-cluster
```

---

### `eks-dx describe-cluster <name>`

Show registration details (issuer, JWKS metadata, creation timestamp).

```bash
eks-dx describe-cluster my-cluster
```

---

### `eks-dx list-clusters`

List all clusters registered by the current caller.

```bash
eks-dx list-clusters
eks-dx list-clusters --output json
```

---

### `eks-dx update-cluster <name>`

Update the JWKS or issuer for a self-managed cluster.

```bash
eks-dx update-cluster my-k3s --jwks-file ./new-jwks.json --issuer https://new-issuer
```

---

### `eks-dx stop-cluster <name>`

Stop (hibernate) a managed cluster's EC2 instance to save cost.

```bash
eks-dx stop-cluster my-cluster
```

---

### `eks-dx resume-cluster <name>`

Resume a stopped managed cluster.

```bash
eks-dx resume-cluster my-cluster
```

---

### `eks-dx get-cluster-access <name>`

Retrieve the public IP and SSH connection details for a managed cluster on demand.
Useful when the terminal session that ran `create-cluster --wait` is no longer
available, or when connecting from a different machine.

```bash
eks-dx get-cluster-access my-cluster
```

Example output:

```
  Cluster:    my-cluster
  Public IP:  54.12.34.56
  SSH key:    ~/.eks-d-xpress/tenants/us-east-1/a1b2c3d4.pem

  Connect:
  ssh -i ~/.eks-d-xpress/tenants/us-east-1/a1b2c3d4.pem ec2-user@54.12.34.56
```

| Flag | Description |
|------|-------------|
| `--save-key` | Re-fetch the SSH private key from Secrets Manager and overwrite the local `.pem` file |
| `--print-key` | Re-fetch the key and print it to stdout (pipe to a file or clipboard) |
| `--output json` | Emit `{ clusterName, tenantId, publicIp, sshKeyPath, sshCommand }` |
| `--region` | Override the AWS region |

**Error cases**

| Condition | Message |
|-----------|---------|
| Cluster not found | `Error: cluster 'X' not found` |
| Self-managed cluster | `Error: cluster 'X' is self-managed and has no SSH access managed by this system` |
| Stopped / hibernating | `Error: cluster 'X' is stopped. Resume it first: eks-dx resume-cluster X` |
| Still provisioning | `Error: cluster 'X' is not ready yet (state: provisioning, 42%, phase: ...)` |
| No public IP yet | `Error: cluster 'X' has no public IP recorded — it may still be booting` |

**Recovering a lost SSH key**

If you have lost the local `.pem` file, re-fetch it from Secrets Manager:

```bash
eks-dx get-cluster-access my-cluster --save-key
# Saves to: ~/.eks-d-xpress/tenants/<region>/<tenantId>.pem  (chmod 600)
```

---

## Association commands

Pod identity associations map a Kubernetes service account to an IAM role.

### `eks-dx create-association <cluster> <namespace> <service-account> <role-arn>`

```bash
eks-dx create-association my-cluster default my-app \
  arn:aws:iam::123456789012:role/my-app-role
```

### `eks-dx delete-association <cluster> <id>`

```bash
eks-dx delete-association my-cluster abc12345
```

### `eks-dx describe-association <cluster> <id>`

```bash
eks-dx describe-association my-cluster abc12345
```

### `eks-dx list-associations <cluster>`

```bash
eks-dx list-associations my-cluster
eks-dx list-associations my-cluster --output json
```

---

## Typical workflows

### Provision a managed cluster and connect

```bash
# 1. Create and wait — SSH key saved automatically on completion
eks-dx create-cluster prod --wait

# 2. Later sessions — retrieve connection details on demand
eks-dx get-cluster-access prod

# 3. If .pem was lost
eks-dx get-cluster-access prod --save-key
```

### Register a self-managed cluster

```bash
# Auto-discover JWKS + issuer from a reachable cluster
eks-dx create-cluster my-k3s --kubeconfig ~/.kube/my-k3s.yaml

# Set up pod identity for a workload
eks-dx create-association my-k3s default my-app \
  arn:aws:iam::123456789012:role/my-app-role
```

### Cost management — stop overnight, resume in the morning

```bash
eks-dx stop-cluster prod
# ... next day ...
eks-dx resume-cluster prod
eks-dx get-cluster-access prod   # confirm IP (may change if not using EIP)
```
