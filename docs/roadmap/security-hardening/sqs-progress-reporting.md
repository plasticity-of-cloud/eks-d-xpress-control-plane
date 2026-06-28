# Security Hardening — SQS-Based Progress Reporting (Remove DynamoDB Direct Write)

## Status: Planned

## Problem

The EC2 tenant instance currently has `dynamodb:UpdateItem` on the tenants table via its instance profile. The `progress.sh` script writes state, phase, and progress directly to DynamoDB:

```bash
aws dynamodb update-item --table-name "${EKS_DX_TENANTS_TABLE}" \
  --key '{"tenantId":{"S":"'${TENANT_ID}'"}}' \
  --update-expression "SET #s = :s, phase = :p, progress = :n, updatedAt = :t" ...
```

This creates multiple security and reliability concerns:

1. **Trust boundary violation** — a compromised instance can write arbitrary state (claim `ready` prematurely, corrupt fields, write invalid transitions)
2. **No rate limiting** — instance can flood DynamoDB with unlimited UpdateItem calls
3. **No validation** — state transitions are not enforced (e.g., `ready → provisioning` should be impossible)
4. **Broad IAM surface** — instance profile needs table ARN and DynamoDB permissions
5. **No audit trail** — writes go directly to DynamoDB, no intermediate log of what the instance reported vs what was persisted

## Proposed Solution

Replace direct DynamoDB writes with **SQS FIFO queue**. The EC2 instance sends progress messages to the queue; tenant-service Lambda (already running, serving the SSE stream) polls the queue and validates before persisting.

### Architecture

```
EC2 (progress.sh)
  │
  │ sqs:SendMessage (MessageGroupId = tenantId)
  │ MessageDeduplicationId = tenantId + progress (5-min dedup window)
  │
  └──→ SQS FIFO Queue (eks-d-xpress-progress.fifo)
         │
         │ ReceiveMessage (polled by SSE Lambda during stream)
         │
         └──→ Tenant-service Lambda (SSE endpoint)
                │
                ├── Validate: state transition allowed?
                ├── Validate: progress only goes forward?
                ├── Validate: message schema correct?
                │
                └── dynamodb:UpdateItem (if valid)
                      └── Stream SSE event to client
```

### Why SQS (not EventBridge or SNS)

The SSE endpoint Lambda is already running (serving the long-lived streaming response). It needs to actively **poll** for progress updates during the stream. EventBridge and SNS are push-based — they invoke targets but cannot be polled by a running function.

| Requirement | SQS | EventBridge | SNS |
|-------------|-----|-------------|-----|
| Pollable from running Lambda | ✅ | ❌ | ❌ |
| Deduplication (prevent spam) | ✅ FIFO 5-min window | ❌ | ❌ |
| Per-tenant message ordering | ✅ MessageGroupId | ❌ | ❌ |
| Rate protection | ✅ 300 msg/s per group | Rules-based | ❌ |

### Protection Mechanisms

| Threat | Mitigation |
|--------|-----------|
| Instance spams queue | FIFO deduplication (same tenantId + progress value = dropped within 5 min) |
| Instance sends invalid state | Lambda validates state transitions before persisting |
| Instance sends progress backward | Lambda rejects if new progress ≤ current progress |
| Instance writes after completion | Lambda ignores messages for tenants in terminal state (`ready`, `failed`) |
| Instance writes to other tenants | MessageGroupId = tenantId enforced; scoped STS token restricts SendMessage to own tenantId |
| Long-lived credential abuse | STS token expires after 15 minutes (natural revocation); no instance profile SQS access |

### Scoped STS Token (Time-Limited, Single-Tenant)

The tenant-service Lambda generates a short-lived STS token before launching EC2 and stores it in Secrets Manager. The instance fetches it at boot and uses it exclusively for progress reporting.

**Token generation (tenant-service Lambda, before EC2 launch):**

```java
AssumeRoleResponse sts = stsClient.assumeRole(AssumeRoleRequest.builder()
    .roleArn("arn:aws:iam::<account>:role/eks-d-xpress-progress-sender")
    .roleSessionName("tenant-" + tenantId)
    .durationSeconds(900)  // 15 minutes (STS minimum)
    .policy("""
        {
          "Version": "2012-10-17",
          "Statement": [{
            "Effect": "Allow",
            "Action": "sqs:SendMessage",
            "Resource": "arn:aws:sqs:<region>:<account>:eks-d-xpress-progress.fifo",
            "Condition": {
              "StringEquals": { "sqs:MessageGroupId": "%s" }
            }
          }]
        }
        """.formatted(tenantId))
    .build());

// Store in Secrets Manager alongside PKI material
secretsManager.createSecret(CreateSecretRequest.builder()
    .name("eks-dx/tenant/" + tenantId + "/progress-token")
    .secretString(toJson(sts.credentials()))
    .build());
```

**Instance boot (progress.sh):**

```bash
# Fetch scoped STS token (valid 15 min from provisioning start)
PROGRESS_CREDS=$(aws secretsmanager get-secret-value \
  --secret-id "eks-dx/tenant/${TENANT_ID}/progress-token" \
  --query SecretString --output text)

export AWS_ACCESS_KEY_ID=$(echo "$PROGRESS_CREDS" | jq -r .accessKeyId)
export AWS_SECRET_ACCESS_KEY=$(echo "$PROGRESS_CREDS" | jq -r .secretAccessKey)
export AWS_SESSION_TOKEN=$(echo "$PROGRESS_CREDS" | jq -r .sessionToken)

# All sqs:SendMessage calls use the scoped token, not the instance profile
aws sqs send-message --queue-url "$PROGRESS_QUEUE_URL" \
  --message-group-id "$TENANT_ID" \
  --message-deduplication-id "${TENANT_ID}-${progress}" \
  --message-body '{"tenantId":"...","state":"...","phase":"...","progress":N}'
```

**Security properties:**
- Token is scoped to a single SQS queue + single MessageGroupId (tenantId)
- Token expires 15 minutes after provisioning starts — no revocation needed
- Instance profile has NO SQS access (only Secrets Manager read for initial fetch)
- If boot takes >15 min, progress reporting stops (acceptable — timeout → mark failed)
- After provisioning completes, Lambda stops consuming from that group; token expires shortly after

**Why Secrets Manager (not user-data):**
- User-data is visible in EC2 console and instance metadata — credentials would be exposed
- Secrets Manager is encrypted, access-logged, and already fetched at boot for PKI material
- Same instance profile permission (`secretsmanager:GetSecretValue` on `eks-dx/tenant/<id>/*`) covers both PKI and progress token

### IAM Scoping

Instance profile (minimal — no SQS, no DynamoDB):

```json
{
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": "arn:aws:secretsmanager:<region>:<account>:secret:eks-dx/tenant/<tenantId>/*"
    }
  ]
}
```

The instance profile only needs Secrets Manager read. The SQS write capability comes entirely from the scoped STS token stored in Secrets Manager.

Dedicated role for STS assumption (`eks-d-xpress-progress-sender`):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "sqs:SendMessage",
    "Resource": "arn:aws:sqs:<region>:<account>:eks-d-xpress-progress.fifo"
  }]
}
```

The session policy (passed at AssumeRole time) further restricts to the specific tenantId's MessageGroupId.

### Message Schema

```json
{
  "tenantId": "abc123",
  "state": "provisioning",
  "phase": "Installing VPC CNI",
  "progress": 55
}
```

- `MessageGroupId`: `abc123` (tenantId)
- `MessageDeduplicationId`: `abc123-55` (tenantId + progress — prevents duplicate progress at same %)

### SSE Lambda Polling Loop

Current (DynamoDB polling):
```java
while (streaming) {
    TenantItem item = dynamoDb.getItem(...);  // polls DynamoDB
    if (item.progress() > lastProgress) sendSseEvent(item);
    Thread.sleep(1000);
}
```

Proposed (SQS polling):
```java
while (streaming) {
    var messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
        .queueUrl(progressQueueUrl)
        .messageGroupId(tenantId)  // Note: FIFO doesn't support this filter — see below
        .maxNumberOfMessages(10)
        .waitTimeSeconds(1)
        .build()).messages();
    
    for (var msg : messages) {
        ProgressEvent event = parse(msg.body());
        if (event.tenantId().equals(tenantId) && validate(event)) {
            persistAndStream(event);
            sqs.deleteMessage(...);
        }
    }
}
```

**Note:** SQS FIFO doesn't support filtering ReceiveMessage by MessageGroupId. The Lambda receives all available messages and filters in code. At expected scale (<10 concurrent provisioning tenants), this is acceptable. For higher scale, consider per-tenant temporary queues.

### Queue Lifecycle

- **Single shared FIFO queue** created by CDK: `eks-d-xpress-progress.fifo`
- Queue has `ContentBasedDeduplication: false` (explicit deduplication IDs)
- Retention: 1 hour (progress messages are ephemeral)
- Visibility timeout: 30 seconds
- The queue persists across tenant lifecycles — no per-tenant creation/deletion

### Changes Required

| Component | Change |
|-----------|--------|
| `TenantIamService` | Remove `dynamodb:UpdateItem` from instance profile; add `sqs:SendMessage` scoped to progress queue + own MessageGroupId |
| `eks-d-xpress` → `progress.sh` | Replace `aws dynamodb update-item` with `aws sqs send-message --queue-url ... --message-group-id $TENANT_ID --message-body '{"tenantId":"...","state":"...","phase":"...","progress":N}'` |
| `TenantStreamResource` (SSE) | Replace DynamoDB GetItem polling with SQS ReceiveMessage polling + validation |
| CDK stack | Add SQS FIFO queue resource; pass queue URL as env var to tenant-service Lambda |
| `TenantProvisioningService` | Write initial state to DynamoDB (as today); progress updates come via SQS |

### Migration Path

1. Deploy queue + Lambda changes (consumer side)
2. Update instance profile IAM (add SQS, keep DynamoDB temporarily)
3. Update `progress.sh` in Golden AMI (producer side)
4. Remove DynamoDB write permission from instance profile
5. New AMI build picks up the change; old instances still work during transition (DynamoDB write still allowed until step 4)

## References

- `docs/roadmap/implemented/control-plane-managed-oidc-jwks.md` — related: instance profile scoping
- `docs/roadmap/security-hardening/ssm-only-access.md` — related: reducing instance attack surface
- `eks-d-xpress` project: `eks-d-setup/progress.sh` — current DynamoDB write implementation
