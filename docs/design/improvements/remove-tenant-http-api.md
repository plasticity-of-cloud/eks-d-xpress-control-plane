# Remove HTTP API for Tenant Service

## Status

Proposed

## Problem

The tenant-service Lambda is configured with `AWS_LWA_INVOKE_MODE=response_stream` to support SSE progress streaming via its Function URL. This makes the separate HTTP API (`eks-d-xpress-tenant-api`, API ID `vf3rkpl8n3`) completely non-functional — every request returns **500 Internal Server Error** because API Gateway's proxy integration cannot parse the Lambda Web Adapter's streaming response format.

The CLI was working around this by treating DELETE timeouts as "accepted" (the Lambda continued running in the background), but GET polling always failed with 500, causing `delete_tenant.sh` to time out.

## Root Cause

```
HTTP API (standard Invoke) → Lambda (LWA in response_stream mode) → streaming response
                           ↓
              API Gateway cannot parse → 500 Internal Server Error
```

Lambda Function URLs with `InvokeMode: RESPONSE_STREAM` handle both streaming and non-streaming responses correctly. The HTTP API does not.

## Proposal

Remove the HTTP API entirely. The Lambda Function URL is the sole entry point for tenant operations.

### What to Remove

| Resource | CDK Logical ID | Description |
|----------|---------------|-------------|
| HTTP API | `TenantHttpApiDAE28F7D` | API Gateway v2 HTTP API |
| Integration | `uq9y3o2` | Lambda proxy integration |
| Routes | `ixjfhrd`, `rrw2bph`, `w0hss6f` | POST/GET/DELETE routes |
| SSM Parameter | `/eks-d-xpress/control-plane/api/tenant-api-url` | Points to the broken API |

### What Stays

| Resource | Value | Description |
|----------|-------|-------------|
| Function URL | `https://52ji3uz67tttx2plettea6srhm0gxkzw.lambda-url.us-east-1.on.aws/` | Working endpoint |
| SSM Parameter | `/eks-d-xpress/control-plane/api/provisioning-url` | Points to Function URL |

### CDK Changes

1. Remove the `HttpApi` construct and its routes from `EksDXpressControlPlaneStack`
2. Remove the SSM parameter for `tenant-api-url`
3. Update any references to `tenantApiUrl` in CDK outputs

### CLI Changes (already done)

The CLI (`DeleteTenantCommand`) was fixed in commit `d2cf675` to use `provisioning-url` (Function URL) with `lambda` service SigV4 signing. The `getTenantApiUrl()` config method is now only used as a fallback.

After the HTTP API is removed, `getTenantApiUrl()` and the `EKS_DX_TENANT_API_URL` env var can be deprecated/removed from `EksDxConfig`.

## Migration

1. Deploy CDK stack update (removes HTTP API)
2. Remove `tenant-api-url` from `~/.eks-d-xpress/config` on dev machines (if cached)
3. Optionally remove `getTenantApiUrl()` from `EksDxConfig` in a follow-up

No client migration needed — the CLI already uses the Function URL.

## Verification

```bash
# Confirm Function URL works for all operations:
./create_tenant.sh test-tenant
./delete_tenant.sh test-tenant
```
