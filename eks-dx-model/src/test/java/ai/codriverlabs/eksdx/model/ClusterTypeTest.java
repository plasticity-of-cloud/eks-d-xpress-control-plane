package ai.codriverlabs.eksdx.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTypeTest {

    @Test
    void shouldHaveThreeValues() {
        assertEquals(3, ClusterType.values().length);
    }

    @ParameterizedTest
    @CsvSource({
        "EKS_DX, EKS_DX",
        "EKS_MANAGED, EKS_MANAGED",
        "ECS_OVERLAY, ECS_OVERLAY",
        "eks_dx, EKS_DX",
        "eks-managed, EKS_MANAGED",
        "ecs-overlay, ECS_OVERLAY",
        "eks_managed, EKS_MANAGED",
        "Eks_Dx, EKS_DX"
    })
    void fromStringShouldParseValidValues(String input, ClusterType expected) {
        assertEquals(expected, ClusterType.fromString(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void fromStringShouldDefaultToEksDxForNullOrEmpty(String input) {
        assertEquals(ClusterType.EKS_DX, ClusterType.fromString(input));
    }

    @Test
    void fromStringShouldDefaultToEksDxForUnknownValue() {
        assertEquals(ClusterType.EKS_DX, ClusterType.fromString("UNKNOWN"));
        assertEquals(ClusterType.EKS_DX, ClusterType.fromString("lambda"));
        assertEquals(ClusterType.EKS_DX, ClusterType.fromString("invalid-type"));
    }
}
