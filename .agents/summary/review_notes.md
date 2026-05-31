# Review Notes

## Unimplemented Designs

### Tenant Authorization & Quota Enforcement
`docs/TENANT_AUTHORIZATION_ROLES.md` defines a four-tier role model (SingleTenant, MultiTenant, ProvisioningOperator, Administrator) with session tag-based resolution and quota enforcement. **Not yet implemented in code** — the tenant-service currently accepts any IAM-authenticated request without role/quota checks.

### Source IP Extraction
`TenantResource.java` reads `ctx.getProperty("sourceIp")` for auto-detecting SSH CIDR, but no JAX-RS filter sets this property from the API Gateway request context. Callers must pass `--ssh-cidr` explicitly.

## Known Issues

### DLM Delete
`TenantDlmService.deleteEtcdBackupPolicy()` uses `tagsToAdd` filter on `GetLifecyclePolicies` — verify this correctly filters by the `Tenant` tag. May need to use `targetTags` instead.

### Orphaned Resources
If the Lambda times out (900s) mid-provisioning, the rollback won't execute. Consider a separate cleanup Lambda triggered by DynamoDB stream on `state=failed` or a scheduled sweeper.

## Gaps

- No unit tests for `TenantProvisioningService.rollback()`
- No integration test for full provisioning flow
- `ownerArn` and `provisionedBy` fields not yet written to DynamoDB (authorization design not implemented)
- GSIs (`ownerArn-index`, `provisionedBy-index`) not yet created in CDK stack
- CLI `provision_tenant.sh` wrapper script is a thin shim — consider folding into `eks-dx create tenant`
