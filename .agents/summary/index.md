# Knowledge Base Index

## How to Use This Documentation

This directory contains structured documentation about the EKS-DX Control Plane system. The `AGENTS.md` file in the project root provides a navigational overview. For deeper detail, consult the files below.

## File Index

| File | Purpose | Consult when... |
|------|---------|-----------------|
| `architecture.md` | System architecture, component relationships, deployment topology | Understanding how services interact, credential exchange flow, CDK stack structure |
| `components.md` | Each module's responsibility, key classes, entry points | Finding where specific functionality lives |
| `interfaces.md` | REST APIs, CLI commands, inter-service contracts | Building integrations, understanding request/response formats |
| `data_models.md` | DynamoDB schemas, record types, key design | Working with data layer, understanding partition keys |
| `workflows.md` | Provisioning, teardown, credential exchange, rollback sequences | Debugging failures, understanding state transitions |
| `dependencies.md` | External libraries, AWS services, version constraints | Upgrading dependencies, understanding what each library does |
| `review_notes.md` | Documentation gaps, inconsistencies, improvement areas | Maintaining documentation quality |

## Quick Lookup

- **"Where does credential exchange happen?"** → `architecture.md` (flow diagram) + `components.md` (credential-service)
- **"What DynamoDB tables exist?"** → `data_models.md`
- **"How does provisioning work?"** → `workflows.md` (provisioning sequence)
- **"What CLI commands are available?"** → `interfaces.md` (CLI section)
- **"What naming convention do resources use?"** → `components.md` (TenantNaming section)
