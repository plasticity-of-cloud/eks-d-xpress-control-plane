# Tenant Service API Gateway Migration: REST → HTTP API

## Context

The tenant service Lambda is invoked via two different mechanisms:

1. **Function URL** (HTTP API v2 payload) — used for `POST /tenants` provisioning and SSE stream
2. **API Gateway REST API** (v1 payload) — used for `GET /tenants/{id}` and `DELETE /tenants/{id}`

These two payload formats are incompatible. `quarkus-amazon-lambda-http` handles v2 (Function URL),
while `quarkus-amazon-lambda-rest` handles v1 (REST API). A single Lambda cannot correctly
deserialize both formats simultaneously.

## Decision

Split tenant invocation paths:

| Operation | Transport | Payload Format | Quarkus Extension |
|---|---|---|---|
| `POST /tenants` (provision) | Lambda Function URL | HTTP API v2 | `quarkus-amazon-lambda-http` |
| `GET /tenants/{id}/stream` (SSE) | Lambda Function URL | HTTP API v2 | `quarkus-amazon-lambda-http` |
| `GET /tenants/{id}` | API Gateway **HTTP API** | HTTP API v2 | `quarkus-amazon-lambda-http` |
| `DELETE /tenants/{id}` | API Gateway **HTTP API** | HTTP API v2 | `quarkus-amazon-lambda-http` |

SSE streaming stays on the Function URL directly — API Gateway has a 29s timeout which
is incompatible with 15-minute provisioning streams.

The existing REST API retains cluster and association CRUD (mgmt-service) unchanged.

## Why HTTP API over REST API for tenant CRUD

- Same payload format (v2) as Function URL — single Quarkus extension works for all paths
- Lower cost: $1.00/million vs $3.50/million requests
- Lower latency: ~60ms vs ~100ms overhead
- No REST API features needed for simple GET/DELETE with IAM auth

## Implementation

### CDK changes (`EksDXpressControlPlaneStack.java`)
- Add `HttpApi` for tenant CRUD alongside existing REST API
- Wire `GET /tenants/{id}` and `DELETE /tenants/{id}` to tenant Lambda via HTTP API
- Write new SSM param: `/eks-d-xpress/control-plane/api/tenant-api-url`

### CLI changes (`EksDxConfig.java`, `DeleteTenantCommand.java`, `GetTenantCommand.java`)
- Add `getTenantApiUrl()` — resolves `EKS_DX_TENANT_API_URL` → config file → SSM param
- `CreateTenantCommand` continues using provisioning URL (Function URL)
- `DeleteTenantCommand` and `GetTenantCommand` use tenant API URL (HTTP API)

### SSM Parameters

| Parameter | Value | Used by |
|---|---|---|
| `/eks-d-xpress/control-plane/api/endpoint` | REST API URL | cluster/association CRUD |
| `/eks-d-xpress/control-plane/api/provisioning-url` | Function URL | `POST /tenants` |
| `/eks-d-xpress/control-plane/api/stream-url` | Function URL | SSE stream |
| `/eks-d-xpress/control-plane/api/tenant-api-url` | HTTP API URL | `GET/DELETE /tenants/{id}` |

### No changes needed
- `12-install-eks-dx-pod-identity.sh` — only registers cluster via mgmt endpoint
- mgmt-service — unaffected, stays on REST API with `quarkus-amazon-lambda-rest`
- credential-service — unaffected
