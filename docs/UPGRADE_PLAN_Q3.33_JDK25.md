# Upgrade Plan: Quarkus 3.33 LTS + Java 25 + Mandrel 25

**Date**: 2026-05-25
**Tag (pre-upgrade baseline)**: `v0.9.0-pre-upgrade`
**Target tag**: `v1.0.0-rc1`

## Current State

| Component | Version |
|-----------|---------|
| Quarkus | 3.20.3 |
| Java (compile + runtime) | 21 |
| Mandrel builder image | `ubi-quarkus-mandrel-builder-image:jdk-21` |
| josdk-webhooks | 3.0.1 |
| quarkus-helm | 1.4.0 |
| aws-cdk-lib | 2.180.0 |

## Target State

| Component | Version |
|-----------|---------|
| Quarkus | 3.33.1 (LTS, released 2026-03-25) |
| Java (compile + runtime) | 25 |
| Mandrel builder image | `ubi9-quarkus-mandrel-builder-image:jdk-25` |
| josdk-webhooks | 3.0.3 |
| quarkus-helm | 1.4.0 (verify compatibility, upgrade if needed) |
| aws-cdk-lib | 2.180.0 (no change — CDK is independent) |

## Why We Were Behind

The project was pinned to Quarkus 3.20.3 (released mid-2024). The josdk-webhooks dependency (3.0.1) is **not** the blocker — it's a lightweight library with no Quarkus version coupling (it only depends on fabric8 kubernetes-client, which Quarkus manages via its BOM). The project simply was never upgraded.

## Migration Steps

### Phase 1: Quarkus 3.20.3 → 3.33.1 LTS

Key breaking changes across this range:

1. **3.28**: Security annotation changes, REST client improvements
2. **3.29**: Cache backend API changes
3. **3.30**: Hibernate Validator 9.1
4. **3.31** (biggest jump):
   - Full Java 25 support
   - JUnit 5 → JUnit 6 (`-junit5` artifacts renamed to `-junit`)
   - Testcontainers 1 → 2
   - Hibernate ORM 6.x → 7.2
   - New `quarkus` Maven packaging (optional, not required)
   - Maven 3.9+ required
5. **3.32**: Project Leyden integration, graceful shutdown improvements
6. **3.33**: LTS stabilization (no new breaking changes over 3.32)

### Phase 2: Java 21 → 25

1. Update `maven.compiler.release` from `21` to `25`
2. Update Mandrel builder image to `ubi9-quarkus-mandrel-builder-image:jdk-25`
3. Verify native builds pass (reflection configs may need regeneration)

### Phase 3: Dependency Updates

1. `josdk-webhooks` 3.0.1 → 3.0.3
2. Verify `quarkus-helm` 1.4.0 compatibility with Quarkus 3.33

## Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Reflection config breakage in native builds | Medium | Run full native build + integration tests |
| JUnit 6 migration breaks test imports | High (but mechanical) | `quarkus update` handles renames |
| Hibernate ORM 7.2 API changes | Low (we don't use Hibernate) | N/A — project uses DynamoDB only |
| Fabric8 client version conflict with josdk-webhooks | Low | Exclusion already in place; Quarkus BOM wins |
| GraalVM 25 stricter metadata validation | Medium | Test all native modules; fix missing registrations |

## Implementation Order

```
1. Update parent POM: quarkus.platform.version → 3.33.1
2. Update parent POM: maven.compiler.release → 25
3. Update parent POM: Mandrel builder image → jdk-25
4. Update josdk-webhooks → 3.0.3
5. Fix any compilation errors (API renames, deprecation removals)
6. Fix test compilation (JUnit 6 imports if needed)
7. Run full JVM build: ./build-local.sh --skip-tests
8. Run tests: mvn test
9. Run native build: ./build-local.sh --native
10. Run integration tests
11. Tag v1.0.0-rc1
```

## Rollback

If the upgrade fails catastrophically:
```bash
git checkout v0.9.0-pre-upgrade
```
