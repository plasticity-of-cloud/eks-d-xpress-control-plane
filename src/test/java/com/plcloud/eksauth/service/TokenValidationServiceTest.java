package com.plcloud.eksauth.service;

import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenValidationServiceTest {

    @Mock
    JWTParser jwtParser;

    @Mock
    JsonWebToken jwt;

    @InjectMocks
    TokenValidationService tokenValidationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("Should validate token with correct audience")
    void testValidTokenWithCorrectAudience() throws ParseException {
        // Arrange
        String token = "valid-token";
        when(jwtParser.parse(token)).thenReturn(jwt);
        when(jwt.getClaim("kubernetes.io/serviceaccount/namespace")).thenReturn("default");
        when(jwt.getClaim("kubernetes.io/serviceaccount/service-account.name")).thenReturn("my-sa");
        when(jwt.getClaim("aud")).thenReturn("pods.eks.amazonaws.com");
        when(jwt.getClaim("exp")).thenReturn(Instant.now().plusSeconds(3600).getEpochSecond());
        when(jwt.getClaim("kubernetes.io/serviceaccount/service-account.uid")).thenReturn("sa-uid-123");
        when(jwt.getClaim("kubernetes.io/pod/name")).thenReturn("my-pod");
        when(jwt.getClaim("kubernetes.io/pod/uid")).thenReturn("pod-uid-456");
        when(jwt.getSubject()).thenReturn("system:serviceaccount:default:my-sa");

        // Act
        TokenValidationService.TokenClaims claims = tokenValidationService.validateToken(token, "my-cluster");

        // Assert
        assertEquals("default", claims.getNamespace());
        assertEquals("my-sa", claims.getServiceAccount());
        assertEquals("sa-uid-123", claims.getServiceAccountUid());
        assertEquals("my-pod", claims.getPodName());
        assertEquals("pod-uid-456", claims.getPodUid());
        assertNotNull(claims.getExpiration());
        
        Map<String, String> tags = claims.getSessionTags();
        assertEquals("default", tags.get("kubernetes-namespace"));
        assertEquals("my-sa", tags.get("kubernetes-service-account"));
        assertEquals("my-pod", tags.get("kubernetes-pod-name"));
        assertEquals("pod-uid-456", tags.get("kubernetes-pod-uid"));
    }

    @Test
    @DisplayName("Should reject token with wrong audience")
    void testTokenWithWrongAudience() throws ParseException {
        // Arrange
        String token = "wrong-audience-token";
        when(jwtParser.parse(token)).thenReturn(jwt);
        when(jwt.getClaim("kubernetes.io/serviceaccount/namespace")).thenReturn("default");
        when(jwt.getClaim("kubernetes.io/serviceaccount/service-account.name")).thenReturn("my-sa");
        when(jwt.getClaim("aud")).thenReturn("sts.amazonaws.com");

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> tokenValidationService.validateToken(token, "my-cluster")
        );
        assertTrue(exception.getMessage().contains("Invalid token audience"));
    }

    @Test
    @DisplayName("Should accept token with wrong audience when skip check is enabled")
    void testTokenWithWrongAudienceSkipCheck() throws ParseException {
        // Arrange
        String token = "wrong-audience-token";
        when(jwtParser.parse(token)).thenReturn(jwt);
        when(jwt.getClaim("kubernetes.io/serviceaccount/namespace")).thenReturn("default");
        when(jwt.getClaim("kubernetes.io/serviceaccount/service-account.name")).thenReturn("my-sa");
        when(jwt.getClaim("exp")).thenReturn(Instant.now().plusSeconds(3600).getEpochSecond());
        when(jwt.getSubject()).thenReturn("system:serviceaccount:default:my-sa");

        // Act
        TokenValidationService.TokenClaims claims = tokenValidationService.validateToken(token, "my-cluster", true);

        // Assert
        assertEquals("default", claims.getNamespace());
        assertEquals("my-sa", claims.getServiceAccount());
    }

    @Test
    @DisplayName("Should reject expired token")
    void testExpiredToken() throws ParseException {
        // Arrange
        String token = "expired-token";
        when(jwtParser.parse(token)).thenReturn(jwt);
        when(jwt.getClaim("kubernetes.io/serviceaccount/namespace")).thenReturn("default");
        when(jwt.getClaim("kubernetes.io/serviceaccount/service-account.name")).thenReturn("my-sa");
        when(jwt.getClaim("aud")).thenReturn("pods.eks.amazonaws.com");
        when(jwt.getClaim("exp")).thenReturn(Instant.now().minusSeconds(60).getEpochSecond());

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> tokenValidationService.validateToken(token, "my-cluster")
        );
        assertTrue(exception.getMessage().contains("Token has expired"));
    }

    @Test
    @DisplayName("Should reject token missing namespace claim")
    void testTokenMissingNamespace() throws ParseException {
        // Arrange
        String token = "missing-namespace-token";
        when(jwtParser.parse(token)).thenReturn(jwt);
        when(jwt.getClaim("kubernetes.io/serviceaccount/namespace")).thenReturn(null);
        when(jwt.getClaim("kubernetes.io/serviceaccount/service-account.name")).thenReturn("my-sa");

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> tokenValidationService.validateToken(token, "my-cluster")
        );
        assertTrue(exception.getMessage().contains("Missing namespace or service account claims"));
    }

    @Test
    @DisplayName("Should strip Bearer prefix from token")
    void testBearerTokenStripping() throws ParseException {
        // Arrange
        String tokenWithoutBearer = "actual-token";
        String tokenWithBearer = "Bearer " + tokenWithoutBearer;
        when(jwtParser.parse(tokenWithoutBearer)).thenReturn(jwt);
        when(jwt.getClaim("kubernetes.io/serviceaccount/namespace")).thenReturn("default");
        when(jwt.getClaim("kubernetes.io/serviceaccount/service-account.name")).thenReturn("my-sa");
        when(jwt.getClaim("aud")).thenReturn("pods.eks.amazonaws.com");
        when(jwt.getClaim("exp")).thenReturn(Instant.now().plusSeconds(3600).getEpochSecond());
        when(jwt.getSubject()).thenReturn("system:serviceaccount:default:my-sa");

        // Act
        TokenValidationService.TokenClaims claims = tokenValidationService.validateToken(tokenWithBearer, "my-cluster");

        // Assert
        assertEquals("default", claims.getNamespace());
        verify(jwtParser).parse(tokenWithoutBearer);
    }

    @Test
    @DisplayName("Should handle array audience claim")
    void testArrayAudienceClaim() throws ParseException {
        // Arrange
        String token = "array-audience-token";
        when(jwtParser.parse(token)).thenReturn(jwt);
        when(jwt.getClaim("kubernetes.io/serviceaccount/namespace")).thenReturn("default");
        when(jwt.getClaim("kubernetes.io/serviceaccount/service-account.name")).thenReturn("my-sa");
        when(jwt.getClaim("aud")).thenReturn(new String[]{"pods.eks.amazonaws.com", "other-audience"});
        when(jwt.getClaim("exp")).thenReturn(Instant.now().plusSeconds(3600).getEpochSecond());
        when(jwt.getSubject()).thenReturn("system:serviceaccount:default:my-sa");

        // Act
        TokenValidationService.TokenClaims claims = tokenValidationService.validateToken(token, "my-cluster");

        // Assert
        assertEquals("default", claims.getNamespace());
    }

    @Test
    @DisplayName("Should handle missing optional claims")
    void testMissingOptionalClaims() throws ParseException {
        // Arrange
        String token = "minimal-token";
        when(jwtParser.parse(token)).thenReturn(jwt);
        when(jwt.getClaim("kubernetes.io/serviceaccount/namespace")).thenReturn("default");
        when(jwt.getClaim("kubernetes.io/serviceaccount/service-account.name")).thenReturn("my-sa");
        when(jwt.getClaim("aud")).thenReturn("pods.eks.amazonaws.com");
        when(jwt.getClaim("exp")).thenReturn(Instant.now().plusSeconds(3600).getEpochSecond());
        when(jwt.getClaim("kubernetes.io/serviceaccount/service-account.uid")).thenReturn(null);
        when(jwt.getClaim("kubernetes.io/pod/name")).thenReturn(null);
        when(jwt.getClaim("kubernetes.io/pod/uid")).thenReturn(null);
        when(jwt.getSubject()).thenReturn("system:serviceaccount:default:my-sa");

        // Act
        TokenValidationService.TokenClaims claims = tokenValidationService.validateToken(token, "my-cluster");

        // Assert
        assertNull(claims.getServiceAccountUid());
        assertNull(claims.getPodName());
        assertNull(claims.getPodUid());
        
        Map<String, String> tags = claims.getSessionTags();
        assertEquals("", tags.get("kubernetes-pod-name"));
        assertEquals("", tags.get("kubernetes-pod-uid"));
    }
}
