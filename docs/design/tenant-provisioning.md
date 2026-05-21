# Tenant Provisioning Design

Automated provisioning of a kubeadm-based Kubernetes cluster per tenant, integrated with EKS-DX Pod Identity.

## Overview

A REST endpoint (Lambda or ECS Fargate) provisions a tenant cluster by:
1. Generating per-tenant credentials (SSH key pair + SA signing key)
2. Launching an EC2 instance via Launch Template
3. The instance self-registers with eks-dx-lambda using a pre-provisioned signing key

Initial infrastructure (Launch Template, IAM instance profile, VPC, Secrets Manager KMS key) is set up once via Terraform.

## Architecture

```
POST /tenants
  Provisioning Service (Lambda or Fargate)
    │
    ├─ 1. Generate RSA-2048 key pair (SA signing key)
    │      └─ private key → Secrets Manager: eks-dx/tenant/{tenantId}/signing-key
    │
    ├─ 2. ec2:CreateKeyPair("eks-dx-tenant-{tenantId}")
    │      └─ private key PEM → Secrets Manager: eks-dx/tenant/{tenantId}/ssh-key
    │
    ├─ 3. iam:CreateRole("eks-dx-tenant-{tenantId}-instance-role")
    │      └─ inline policy: GetSecretValue on eks-dx/tenant/{tenantId}/*
    │                         execute-api:Invoke on POST /clusters (eks-dx-lambda)
    │
    ├─ 4. ec2:RunInstances(LaunchTemplate, KeyName, IamInstanceProfile)
    │
    ├─ 5. DynamoDB.put({ tenantId, instanceId, state: "provisioning" })
    └─ 6. → 202 { tenantId }

  EC2 Instance (user data — kubeadm bootstrap):
    │
    ├─ 1. Pull signing key from Secrets Manager
    ├─ 2. Write to /etc/kubernetes/pki/sa.key  (and derive sa.pub)
    ├─ 3. kubeadm init --service-account-signing-key-file /etc/kubernetes/pki/sa.key
    │                   --service-account-issuer https://{publicIp}
    ├─ 4. Derive public JWKS from sa.pub
    └─ 5. POST /clusters { name: tenantId, issuer, jwks }  (SigV4 via instance profile)
           → eks-dx-lambda stores cluster in DynamoDB → cluster is live

GET /tenants/{id}
  Lambda → DynamoDB → { state, publicIp, keySecretArn }
```

## Key Design Decisions

### Why the signing key must persist in Secrets Manager

`JwksTokenValidationService` verifies every pod SA token against the **public JWKS stored in DynamoDB**. That JWKS is derived from the private signing key. kubeadm writes `sa.key` to `/etc/kubernetes/pki/sa.key` and uses it to sign all SA tokens.

If the instance reboots, the key is already on the EBS volume (`/etc/kubernetes/pki/sa.key`). Secrets Manager is the source of truth for disaster recovery and re-provisioning. Delete the secret only when deprovisioning the tenant.

### kubeadm SA signing key placement

kubeadm expects the SA signing key at `/etc/kubernetes/pki/sa.key` (private) and `/etc/kubernetes/pki/sa.pub` (public). The user data script must write these **before** running `kubeadm init`, otherwise kubeadm generates its own key and the pre-registered JWKS won't match.

```bash
# user data excerpt
SECRET=$(aws secretsmanager get-secret-value \
  --secret-id eks-dx/tenant/${TENANT_ID}/signing-key \
  --query SecretString --output text)

echo "$SECRET" > /etc/kubernetes/pki/sa.key
openssl rsa -in /etc/kubernetes/pki/sa.key -pubout -out /etc/kubernetes/pki/sa.pub

kubeadm init \
  --service-account-signing-key-file /etc/kubernetes/pki/sa.key \
  --service-account-issuer "https://${PUBLIC_IP}"
```

### Self-registration flow

The instance calls `POST /clusters` on eks-dx-lambda using SigV4 signed by the instance profile. The instance profile role has `execute-api:Invoke` scoped to the registration endpoint only. No shared secret or bootstrap token is needed.

```bash
# Derive public JWKS from sa.pub and register
JWKS=$(python3 -c "
import json, subprocess, base64
# ... derive JWK from sa.pub ...
")

aws apigateway ... # or curl with SigV4
curl -X POST https://eks-dx.codriverlabs.ai/clusters \
  -H "Authorization: AWS4-HMAC-SHA256 ..." \
  -d "{\"name\": \"${TENANT_ID}\", \"issuer\": \"https://${PUBLIC_IP}\", \"jwks\": ${JWKS}}"
```

In practice, use the `eks-dx` CLI (already handles SigV4) from the instance:

```bash
eks-dx create cluster ${TENANT_ID} \
  --issuer "https://${PUBLIC_IP}" \
  --jwks-file /tmp/jwks.json
```

### Async API (mandatory — API Gateway 29s timeout)

```
POST /tenants          → 202 { tenantId }
GET  /tenants/{id}     → { state: "provisioning"|"ready"|"failed", publicIp, sshKeySecretArn }
DELETE /tenants/{id}   → deprovision (terminate EC2, delete IAM role, delete secrets, deregister cluster)
```

State is tracked in DynamoDB. The instance updates state to `"ready"` after successful cluster registration (via a callback to the provisioning API or directly to DynamoDB via instance profile).

### IAM role per tenant

A new IAM role is created per tenant with least-privilege inline policy:

```json
{
  "Effect": "Allow",
  "Action": "secretsmanager:GetSecretValue",
  "Resource": "arn:aws:secretsmanager:*:*:secret:eks-dx/tenant/{tenantId}/*"
},
{
  "Effect": "Allow",
  "Action": "execute-api:Invoke",
  "Resource": "arn:aws:execute-api:{region}:{accountId}:{apiId}/*/POST/clusters"
}
```

The role is deleted on tenant deprovisioning.

## Secrets Layout

| Secret name | Content | Lifecycle |
|---|---|---|
| `eks-dx/tenant/{id}/signing-key` | RSA-2048 private key PEM (SA signing) | Persist for cluster lifetime |
| `eks-dx/tenant/{id}/ssh-key` | EC2 key pair private key PEM | Persist for cluster lifetime |

## Deprovisioning

```
DELETE /tenants/{id}
  1. ec2:TerminateInstances
  2. DELETE /clusters/{tenantId}  (eks-dx-lambda — removes DynamoDB entry)
  3. secretsmanager:DeleteSecret eks-dx/tenant/{id}/signing-key
  4. secretsmanager:DeleteSecret eks-dx/tenant/{id}/ssh-key
  5. ec2:DeleteKeyPair eks-dx-tenant-{id}
  6. iam:DeleteRolePolicy + iam:DeleteRole eks-dx-tenant-{id}-instance-role
  7. DynamoDB.delete({ tenantId })
```
