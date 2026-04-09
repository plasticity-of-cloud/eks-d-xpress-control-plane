package com.plcloud.eksauth.service;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class PodIdentityAssociationServiceTest {

    KubernetesMockServer mockServer;
    KubernetesClient kubernetesClient;

    PodIdentityAssociationService service;

    @BeforeEach
    void setUp() {
        // Stub EksClient to throw so tests exercise the ConfigMap fallback path
        software.amazon.awssdk.services.eks.EksClient stubEks =
            org.mockito.Mockito.mock(software.amazon.awssdk.services.eks.EksClient.class);
        org.mockito.Mockito.when(stubEks.listPodIdentityAssociations(
            org.mockito.ArgumentMatchers.any(
                software.amazon.awssdk.services.eks.model.ListPodIdentityAssociationsRequest.class)))
            .thenThrow(new RuntimeException("EKS API not available in unit tests"));

        service = new PodIdentityAssociationService();
        service.setEksClient(stubEks);
        service.setKubernetesClient(kubernetesClient);
        service.configMapName = "pod-identity-associations";
        service.configMapNamespace = "kube-system";
    }

    @Test
    @DisplayName("Should find role ARN for exact service account match")
    void testExactServiceAccountMatch() {
        // Arrange
        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName("pod-identity-associations")
                .withNamespace("kube-system")
            .endMetadata()
            .withData(Map.of(
                "test-cluster:default:my-sa", "arn:aws:iam::123456789012:role/test-role",
                "test-cluster:production:*", "arn:aws:iam::123456789012:role/production-role"
            ))
            .build();
        
        kubernetesClient.resource(configMap).create();

        // Act
        String roleArn = service.getRoleArnForServiceAccount("test-cluster", "default", "my-sa");

        // Assert
        assertEquals("arn:aws:iam::123456789012:role/test-role", roleArn);
    }

    @Test
    @DisplayName("Should find role ARN for namespace wildcard match")
    void testNamespaceWildcardMatch() {
        // Arrange
        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName("pod-identity-associations")
                .withNamespace("kube-system")
            .endMetadata()
            .withData(Map.of(
                "test-cluster:default:my-sa", "arn:aws:iam::123456789012:role/test-role",
                "test-cluster:production:*", "arn:aws:iam::123456789012:role/production-role"
            ))
            .build();
        
        kubernetesClient.resource(configMap).create();

        // Act
        String roleArn = service.getRoleArnForServiceAccount("test-cluster", "production", "any-sa");

        // Assert
        assertEquals("arn:aws:iam::123456789012:role/production-role", roleArn);
    }

    @Test
    @DisplayName("Should return default role ARN when no match found")
    void testDefaultRoleArnWhenNoMatch() {
        // Arrange
        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName("pod-identity-associations")
                .withNamespace("kube-system")
            .endMetadata()
            .withData(Map.of(
                "test-cluster:default:my-sa", "arn:aws:iam::123456789012:role/test-role"
            ))
            .build();
        
        kubernetesClient.resource(configMap).create();

        // Set environment variable for default role ARN
        System.setProperty("AWS_ACCOUNT_ID", "123456789012");

        // Act
        String roleArn = service.getRoleArnForServiceAccount("test-cluster", "production", "other-sa");

        // Assert
        assertEquals("arn:aws:iam::123456789012:role/eks-pod-identity-production-other-sa", roleArn);
    }

    @Test
    @DisplayName("Should return default role ARN when ConfigMap is null")
    void testDefaultRoleArnWhenConfigMapNull() {
        // Arrange - no ConfigMap created
        System.setProperty("AWS_ACCOUNT_ID", "123456789012");

        // Act
        String roleArn = service.getRoleArnForServiceAccount("test-cluster", "default", "my-sa");

        // Assert
        assertEquals("arn:aws:iam::123456789012:role/eks-pod-identity-default-my-sa", roleArn);
    }

    @Test
    @DisplayName("Should return default role ARN when ConfigMap data is null")
    void testDefaultRoleArnWhenConfigMapDataNull() {
        // Arrange
        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName("pod-identity-associations")
                .withNamespace("kube-system")
            .endMetadata()
            .build(); // No data
        
        kubernetesClient.resource(configMap).create();

        System.setProperty("AWS_ACCOUNT_ID", "123456789012");

        // Act
        String roleArn = service.getRoleArnForServiceAccount("test-cluster", "default", "my-sa");

        // Assert
        assertEquals("arn:aws:iam::123456789012:role/eks-pod-identity-default-my-sa", roleArn);
    }

    @Test
    @DisplayName("Should generate unique association ID")
    void testGenerateAssociationId() throws InterruptedException {
        // Act
        String associationId1 = service.generateAssociationId("test-cluster", "default", "my-sa");
        Thread.sleep(10); // Ensure different timestamp
        String associationId2 = service.generateAssociationId("test-cluster", "default", "my-sa");

        // Assert
        assertTrue(associationId1.startsWith("assoc-test-cluster-default-my-sa-"));
        assertTrue(associationId2.startsWith("assoc-test-cluster-default-my-sa-"));
        assertNotEquals(associationId1, associationId2);
    }

    @Test
    @DisplayName("Should use environment AWS_ACCOUNT_ID for default role ARN")
    void testDefaultRoleArnWithEnvironmentAccountId() {
        // Arrange - no ConfigMap created
        System.setProperty("AWS_ACCOUNT_ID", "999999999999");

        // Act
        String roleArn = service.getRoleArnForServiceAccount("test-cluster", "default", "my-sa");

        // Assert
        assertEquals("arn:aws:iam::999999999999:role/eks-pod-identity-default-my-sa", roleArn);
    }

    @Test
    @DisplayName("Should use default account ID when AWS_ACCOUNT_ID not set")
    void testDefaultRoleArnWithoutEnvironmentAccountId() {
        // Arrange - no ConfigMap created
        System.clearProperty("AWS_ACCOUNT_ID");

        // Act
        String roleArn = service.getRoleArnForServiceAccount("test-cluster", "default", "my-sa");

        // Assert
        assertEquals("arn:aws:iam::123456789012:role/eks-pod-identity-default-my-sa", roleArn);
    }
}
