# ADR: Repository Boundaries — eks-d-xpress, eks-d-xpress-infra, eks-d-xpress-control-plane

## Status: Accepted — Implemented (2026-06-03)

### Implementation Summary

- **Phase 1** ✅ `scripts/install-eks-dx-pod-identity.sh` created in `eks-d-xpress-control-plane`; `eks-d-setup/12-install-eks-dx-pod-identity.sh` updated to thin wrapper sourcing `/opt/eks-d/scripts/`
- **Phase 2** ✅ `ami-builder/` and `eks-d-setup/` moved from `eks-d-xpress-infra` → `eks-d-xpress`; `eks-d-xpress/ami-builder/build-control-plane-ami.sh` is the canonical packer build entry point
- **Phase 3** ✅ `eks-d-xpress-infra/build-control-plane-ami.sh` archived; `ami-builder/` and `eks-d-setup/` archived in `eks-d-xpress-infra/archived/`

## Context

Three repositories exist today with overlapping responsibilities that create a chicken-and-egg dependency problem:

```
eks-d-xpress-control-plane  (public)
  Lambda services, CLI, Helm charts (auth-proxy, webhook)
  docs/user_guides/ec2-k3s-pod-identity/  ← generic Pod Identity integration guide
  ↓ releases CLI binary + Helm charts to GHCR

eks-d-xpress-infra  (private, account-specific)
  ami-builder/           ← Packer build: consumes eks-d-xpress-control-plane releases
  eks-d-setup/           ← EKS-D boot scripts
    12-install-eks-dx-pod-identity.sh  ← EKS-D-specific Pod Identity setup
  cdk/                   ← shared VPC, launch templates, SSM parameters
  ↑ depends on CLI binary + Helm charts from eks-d-xpress-control-plane

eks-d-xpress  (public, currently empty)
```

### The Core Problem

`12-install-eks-dx-pod-identity.sh` in `eks-d-xpress-infra` and the k3s guide in
`eks-d-xpress-control-plane` implement **the same logic**:

1. Register cluster with eks-dx control plane (`eks-dx create cluster`)
2. Install `eks-dx-auth-proxy` via Helm
3. Install `eks-dx-pod-identity-webhook` via Helm
4. Install `eks-pod-identity-agent` (AWS DaemonSet) with `--endpoint` redirected to auth-proxy

The script is not EKS-D-specific. It works on any Kubernetes distribution. Yet it lives in
`eks-d-xpress-infra` — a private, account-specific repo — making it invisible to users of k3s,
microk8s, or any other distribution.

Additionally, the AMI build (`ami-builder/`) has a versioned dependency on
`eks-d-xpress-control-plane` releases (CLI binary, Helm chart tarballs). This is a
cross-repo compile-time dependency that currently lives in the wrong repo:
`eks-d-xpress-infra` is supposed to be account infra, not a distribution builder.

### Current Dependency Graph (Problematic)

```
eks-d-xpress-control-plane  ──releases──►  GHCR
                                               ↑
eks-d-xpress-infra  ──consumes releases────────┘
    │
    └── ami-builder/  (should not be here: account-infra ≠ distribution)
    └── eks-d-setup/  (should not be here: generic k8s logic mixed with account-infra)
```

---

## Decision

### Repository Responsibilities (Target State)

#### `eks-d-xpress-control-plane` (public — the *service*)

Owns the credential service and the **distribution-agnostic** integration:

- Lambda services, Helm charts, CLI, CDK stack
- `docs/user_guides/` — generic guides for any k8s distribution (k3s, microk8s, EKS-D)
- The canonical **`install-eks-dx-pod-identity.sh`** script — a single, distribution-agnostic
  script that registers any cluster and installs the three components

The script lives here because it is a direct artifact of this service — like the Helm charts.
It is released alongside them (same version tag) and consumed by downstream distributions.

#### `eks-d-xpress` (public — the *distribution*)

Owns everything needed to build and run an EKS-D cluster on EC2 with eks-dx Pod Identity:

- `ami-builder/` — moved from `eks-d-xpress-infra`
- `eks-d-setup/` — moved from `eks-d-xpress-infra` (EKS-D-specific boot scripts)
- `COMPONENT_VERSIONS.md` — pinned versions of all consumed artifacts
- Sources `install-eks-dx-pod-identity.sh` from `eks-d-xpress-control-plane` release assets
- GitHub Actions: builds and publishes versioned AMIs; writes AMI IDs to SSM

`12-install-eks-dx-pod-identity.sh` **moves** to `eks-d-xpress` as a thin wrapper:

```bash
# eks-d-setup/12-install-eks-dx-pod-identity.sh (in eks-d-xpress)
# Downloads and runs the canonical script released by eks-d-xpress-control-plane
curl -sL "https://github.com/plasticity-of-cloud/eks-d-xpress-control-plane/releases/download/\
v${EKS_DX_CONTROL_PLANE_VERSION}/install-eks-dx-pod-identity.sh" | bash
```

Or, since the script is pre-downloaded into the AMI at build time, it runs from
`/opt/eks-d/scripts/install-eks-dx-pod-identity.sh` — no runtime download needed.

#### `eks-d-xpress-infra` (private — *account infrastructure*)

Owns only what is account-specific and cannot be public:

- `cdk/` — shared VPC, subnets, route tables, launch templates, SSM parameter writes
- `provision-shared-infra.sh`, `deprovision-shared-infra.sh`
- `build-control-plane-ami.sh` — thin wrapper that invokes the AMI build from `eks-d-xpress`
  (or simply calls the `eks-d-xpress` GitHub Actions workflow via API)

No application logic. No Helm chart references. No eks-dx CLI usage.

### Target Dependency Graph

```
eks-d-xpress-control-plane  ──releases──►  GHCR + GitHub Releases
    │                                          (CLI binary, Helm charts,
    │                                           install-eks-dx-pod-identity.sh)
    │                                               ↓
    └── docs/user_guides/ (generic guides)    eks-d-xpress
                                              ami-builder/ + eks-d-setup/
                                              ──produces──► versioned AMIs
                                                                ↓ SSM
                                              eks-d-xpress-infra
                                              cdk/ (reads AMI IDs from SSM)
```

No circular dependencies. Each repo consumes only **released, versioned artifacts** from upstream.

---

## The Canonical Script Question

The `install-eks-dx-pod-identity.sh` logic is **identical** regardless of distribution
(k3s, EKS-D, microk8s):

```
1. eks-dx create cluster --name ... --jwks-file ...
2. helm install eks-dx-auth-proxy
3. helm install eks-dx-pod-identity-webhook
4. helm install eks-pod-identity-agent (with --endpoint redirect)
```

The only distribution-specific inputs are environment variables (`CLUSTER_NAME`,
`EKS_DX_ENDPOINT`, `AWS_REGION`). The script itself is generic.

**Canonical location: `eks-d-xpress-control-plane`**, released as a GitHub Release asset
alongside the CLI binary and Helm charts.

Consumers:
- `eks-d-xpress` AMI build: downloads at build time, bakes into `/opt/eks-d/scripts/`
- k3s users: download directly from GitHub Releases as instructed in the user guide
- Future distributions: same pattern

The k3s user guide in `eks-d-xpress-control-plane/docs/user_guides/` becomes a thin
reference to the canonical script, not a copy of it.

---

## Migration Plan

### Phase 1 — Canonicalize the script (in `eks-d-xpress-control-plane`)
1. Create `scripts/install-eks-dx-pod-identity.sh` — merge the k3s guide steps and `12-install-eks-dx-pod-identity.sh` into one distribution-agnostic script
2. Add it to the GitHub Actions release workflow as a release asset
3. Update the k3s user guide to reference it

### Phase 2 — Populate `eks-d-xpress`
1. Move `ami-builder/` from `eks-d-xpress-infra` → `eks-d-xpress`
2. Move `eks-d-setup/` from `eks-d-xpress-infra` → `eks-d-xpress`
3. Update `12-install-eks-dx-pod-identity.sh` to source the canonical script from `/opt/eks-d/scripts/` (pre-baked in AMI)
4. Add GitHub Actions workflow to build and publish AMIs, write SSM parameters
5. Add `COMPONENT_VERSIONS.md` with pinned versions

### Phase 3 — Slim down `eks-d-xpress-infra`
1. Remove `ami-builder/` and `eks-d-setup/` (now in `eks-d-xpress`)
2. `build-control-plane-ami.sh` becomes a wrapper that triggers `eks-d-xpress` workflow or runs packer from the cloned `eks-d-xpress` repo
3. Only `cdk/` and provision/deprovision scripts remain

---

## Consequences

**Good:**
- Single canonical Pod Identity integration script, versioned and released
- Any k8s distribution (k3s, microk8s, EKS-D, future) can consume it the same way
- `eks-d-xpress-infra` becomes purely account-infra — safe to review/audit without application logic
- `eks-d-xpress` is independently useful: users can build their own AMIs without touching infra
- Version alignment is explicit: `COMPONENT_VERSIONS.md` in `eks-d-xpress` pins the control-plane release

**Trade-offs:**
- Phase 2 is a meaningful migration (moving ~15 scripts + packer config across repos)
- `eks-d-xpress-infra` CI/CD needs to be updated to pull AMI IDs from SSM rather than building inline
- During transition, two copies of the script exist — needs a clear cutover date

## References

- `docs/user_guides/ec2-k3s-pod-identity/README.md` — current k3s integration guide
- `eks-d-xpress-infra/eks-d-setup/12-install-eks-dx-pod-identity.sh` — current EKS-D implementation
- `eks-d-xpress-infra/ami-builder/scripts/install.sh` — AMI build consuming this repo's releases
