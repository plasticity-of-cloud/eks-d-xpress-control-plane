# Knowledge Base Index

## Purpose
This file is the primary entry point for AI assistants working with the eks-d-xpress-control-plane codebase. Load this file first to understand what documentation is available and where to find detailed information.

## File Index

| File | Purpose | Consult When |
|------|---------|--------------|
| `codebase_info.md` | Tech stack, module list, prerequisites | Understanding project basics |
| `architecture.md` | System design, Lambda split, data flow | Architecture questions, adding new services |
| `components.md` | Module responsibilities, key classes | Finding where code lives |
| `interfaces.md` | REST APIs, auth model, event contracts | API changes, integration work |
| `data_models.md` | DynamoDB schemas, GSIs, key design | Data layer changes, queries |
| `workflows.md` | Provisioning flow, credential exchange, build/deploy | Understanding end-to-end processes |
| `dependencies.md` | External libraries, AWS services used | Dependency upgrades, compatibility |
| `review_notes.md` | Known gaps, unimplemented designs, TODOs | Planning next work |

## Quick Navigation

- **"How does tenant provisioning work?"** → `workflows.md` § Tenant Provisioning
- **"What permissions does the Lambda need?"** → `architecture.md` § IAM Model + `components.md` § tenant-service
- **"What's the DynamoDB schema?"** → `data_models.md`
- **"How do I build/deploy?"** → `workflows.md` § Build & Deploy, also `.kiro/steering/DEVELOPMENT.md`
- **"What's not implemented yet?"** → `review_notes.md`
- **"How does auth work?"** → `interfaces.md` § Authentication Model
