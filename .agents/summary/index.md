# Knowledge Base Index

This index is the primary entry point for AI assistants. Read this file first to understand what documentation is available and which file to consult for specific questions.

## How to Use This Documentation

1. **Start here** — this index contains summaries sufficient to answer many questions without reading other files.
2. **For deeper detail** — use the file references below to locate the right document.
3. **For code navigation** — use `AGENTS.md` in the repo root for entry points and module layout.

## Documentation Files

| File | Purpose | Consult for |
|------|---------|-------------|
| `codebase_info.md` | Project identity, tech stack, module list | "What is this project?", "What version of X is used?" |
| `architecture.md` | System diagrams, design principles, auth flows | "How does credential exchange work?", "What calls what?" |
| `components.md` | Per-component class descriptions and behaviors | "What does X class do?", "Where is Y logic?" |
| `interfaces.md` | All API endpoints, CLI commands, config properties | "What endpoints exist?", "How do I configure X?" |
| `data_models.md` | DynamoDB schemas, Java DTOs, session tags | "What's the DynamoDB key schema?", "What fields does X have?" |
| `workflows.md` | Step-by-step sequence diagrams for key flows | "How does pod startup work?", "How do I register a cluster?" |
| `dependencies.md` | All libraries, AWS services, IAM permissions | "What IAM permissions does Lambda need?", "What version of X?" |
| `review_notes.md` | Gaps and inconsistencies found during review | "What's not documented?", "Known issues?" |

## Quick Reference

### The 5-module layout
- `eks-dx-lambda` — serverless backend (Lambda + API Gateway + DynamoDB)
- `eks-dx-auth-proxy` — in-cluster proxy (TokenReview fast-fail + Lambda forwarding)
- `eks-dx-pod-identity-webhook` — admission webhook (injects env vars + token volume into pods)
- `eks-dx-cli` — native CLI for cluster/association management
- `infra` — CDK infrastructure (alternative to `sam.yaml`)

### The credential exchange in one sentence
Pod → Pod Identity Agent → auth-proxy (TokenReview fast-fail) → Lambda (JWKS validate + DynamoDB association lookup + STS AssumeRole) → credentials.

### Key DynamoDB access pattern
Role ARN lookup is O(1): `GetItem(PK=CLUSTER#<name>, SK=<namespace>#<serviceAccount>)`.

### Auth model
- `/clusters/{name}/assets` — no API Gateway auth; token is in the request body, validated by Lambda via JWKS
- Management endpoints (`/clusters`, `/clusters/{name}`, JWKS PUT, association POST/DELETE) — IAM SigV4
- Association GET endpoints — open at API Gateway; optionally validated by `WebhookAuthFilter` when a Bearer token is present
- Webhook → Lambda — Bearer SA token with audience `eks-dx.codriverlabs.ai`

### JWKS caching
Lambda caches `JWTAuthContextInfo` per `clusterName|audience` for 5 minutes in a `ConcurrentHashMap`. Cache is per Lambda instance (not shared across invocations after cold start).

### Role naming constraint
STS `AssumeRole` is scoped to `arn:aws:iam::*:role/eks-dx-pod-*`. IAM roles used for pod identity must match this prefix.
