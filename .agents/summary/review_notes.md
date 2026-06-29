# Review Notes

## Consistency Issues

- **SM path in boot script**: `eks-d-xpress` project (`07-install-eks-d.sh`) uses `eks-dx/tenant/` but hasn't been deployed with the latest naming yet. Ensure Golden AMI rebuild after merging.
- **CDK stack tag** uses `Project=eks-d-xpress-control-plane` (stack-level) vs `Platform=eks-d-xpress` (resource-level). Two different concerns but could confuse.

## Completeness Gaps

- **Authorization roles not yet implemented**: `authorization-roles.md` design exists but the tenant-service doesn't yet resolve `eks-dx-role` session tags or enforce role-based quotas beyond the simple max-per-caller check.
- **SQS progress reporting** (roadmap): EC2 still writes directly to DynamoDB for progress updates. Scoped STS token approach documented but not implemented.
- **GraalVM native reflect-config**: `reflect-config.json` for Bouncy Castle exists but hasn't been validated with a native build end-to-end.
- **Integration tests**: No end-to-end test that exercises the full `create-cluster → EC2 boot → credential exchange` flow in CI.

## Recommendations

1. Implement role resolution from session tags (Priority 1 for GA)
2. Add integration test using LocalStack or DynamoDB Local + mocked EC2 for provisioning flow
3. Validate native build with Bouncy Castle reflect-config before releasing native binary
4. Rebuild Golden AMI with updated `07-install-eks-d.sh` (SM fetch + service-account-issuer)
5. Consider moving EventBridge/SQS resource naming into `TenantNaming` for the Karpenter queue on the tenant instance (currently hardcoded in boot scripts)
