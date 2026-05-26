# Review Notes

## Inconsistencies Found

1. **AGENTS.md references stale module names**: The existing AGENTS.md still references `eks-dx-lambda` (the pre-split monolith). It should reference `eks-dx-credential-service`, `eks-dx-mgmt-service`, and `eks-dx-tenant-service`.

2. **CDK vs SAM note is outdated**: AGENTS.md states "CDK is not maintained at parity" — this is no longer true. CDK is now the primary deployment path with full Lambda runtime, SSM lookups, and proper IAM.

3. **sam.yaml may be stale**: The SAM template (`sam.yaml`) predates the 3-Lambda split and tenant-service additions. It likely doesn't match the current CDK stack.

4. **README.md references old architecture**: README still shows the single-Lambda flow and doesn't mention tenant-service or the composable provisioning architecture.

## Completeness Gaps

1. **No deprovision documentation**: The deprovision flow (cleanup of subnets, SG, IAM, SQS, EventBridge, DLM) is implemented but not documented in workflows.

2. **No error handling documentation**: What happens when provisioning partially fails? No rollback/compensation logic is documented.

3. **Hibernate/Resume not implemented**: Documented in `docs/TENANT_HIBERNATE_RESUME.md` but the actual `POST /tenants/{id}/hibernate` and `/resume` endpoints don't exist yet in code.

4. **Kube-API proxy not implemented**: Documented in `docs/KUBE_API_PROXY_ARCHITECTURE.md` but no code exists for this feature.

5. **Missing integration test for tenant provisioning**: No test exercises the full provisioning flow (would need LocalStack or mocked AWS services).

6. **No Helm chart documentation**: Auth-proxy and webhook generate Helm charts via quarkus-helm but chart values/configuration aren't documented.

## Recommendations

1. **Update AGENTS.md** — regenerate with current module structure (this run will do it)
2. **Archive or update sam.yaml** — either bring to parity with CDK or mark as deprecated
3. **Add compensation logic** — if EC2 launch fails, clean up IAM role, subnets, SG created earlier
4. **Implement hibernate/resume endpoints** — code is straightforward per the design doc
5. **Add tenant provisioning integration test** — use LocalStack in CI
