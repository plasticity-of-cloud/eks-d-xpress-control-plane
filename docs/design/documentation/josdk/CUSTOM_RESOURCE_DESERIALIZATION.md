# Custom Resource Deserialization in JOSDK Webhooks

## Problem

When using JOSDK's `AdmissionController<T>` with a custom Fabric8 `CustomResource` type, the admission review's embedded `object` field deserializes as `GenericKubernetesResource` instead of `T`, causing a `ClassCastException` in `DefaultAdmissionRequestMutator.handle()`.

## Root Cause

Fabric8 client (since issue #4579) no longer implicitly registers custom resource types. The `KubernetesDeserializer` only maps `apiVersion`+`kind` → Java class for types it knows about. Unrecognized types fall back to `GenericKubernetesResource`.

From the Fabric8 release notes:

> Fix #4579: the implicit registration of resource and list types that happens when using the `resource(class)` methods has been removed. If you expect to see instances of a custom type from an untyped api call — typically `KubernetesClient.load`, `KubernetesClient.resourceList`, `KubernetesClient.resource(InputStream|String)` — then you must create a `META-INF/services/io.fabric8.kubernetes.api.model.KubernetesResource` file, or make calls to `KubernetesDeserializer.registerCustomKind` (internal, not preferred).

## Solution

Create `src/main/resources/META-INF/services/io.fabric8.kubernetes.api.model.KubernetesResource` listing the FQCN of each custom resource class:

```
ai.codriverlabs.karpenter.model.Ec2NodeClass
```

This registers the type via Java's `ServiceLoader` mechanism at classload time, allowing Fabric8 to correctly deserialize admission review payloads.

## Reference

- JOSDK Webhook README, section "Using Custom Resources in the API": https://github.com/java-operator-sdk/admission-controller-framework
- Fabric8 issue #4579 / #3923
- Sample: https://github.com/java-operator-sdk/admission-controller-framework/blob/main/samples/commons/src/main/resources/META-INF/services/io.fabric8.kubernetes.api.model.KubernetesResource

## Applies To

Any module using JOSDK `AdmissionController<T>` where `T` is a custom `CustomResource` subclass — including the `eks-dx-karpenter-support` webhook.
