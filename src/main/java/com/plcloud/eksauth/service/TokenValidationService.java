package com.plcloud.eksauth.service;

import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TokenValidationService {

    private static final Logger LOG = Logger.getLogger(TokenValidationService.class);

    @Inject
    JWTParser jwtParser;

    public static class TokenClaims {
        private final String namespace;
        private final String serviceAccount;
        private final String subject;

        public TokenClaims(String namespace, String serviceAccount, String subject) {
            this.namespace = namespace;
            this.serviceAccount = serviceAccount;
            this.subject = subject;
        }

        public String getNamespace() { return namespace; }
        public String getServiceAccount() { return serviceAccount; }
        public String getSubject() { return subject; }
    }

    public TokenClaims validateToken(String token, String clusterName) {
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        try {
            JsonWebToken jwt = jwtParser.parse(token);

            String namespace = jwt.getClaim("kubernetes.io/serviceaccount/namespace");
            String serviceAccount = jwt.getClaim("kubernetes.io/serviceaccount/service-account.name");

            if (namespace == null || serviceAccount == null) {
                throw new IllegalArgumentException("Missing namespace or service account claims");
            }

            LOG.infof("Validated token for %s/%s in cluster %s", namespace, serviceAccount, clusterName);
            return new TokenClaims(namespace, serviceAccount, jwt.getSubject());

        } catch (ParseException e) {
            LOG.errorf("Token validation failed: %s", e.getMessage());
            throw new IllegalArgumentException("Invalid service account token", e);
        }
    }
}
