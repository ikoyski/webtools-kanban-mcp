package com.ikoyski.webtools.kanban.mcp.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/** Validates signature, issuer, token timestamps and the audience assigned to this MCP resource. */
@Configuration
public class JwtDecoderConfig {

    /**
     * Resolves the configured authorization-server service ID through Eureka when retrieving
     * the public JWK set. The JWK endpoint is used only to obtain public verification keys;
     * issuer validation still uses the canonical issuer URL.
     */
    @Bean
    @LoadBalanced
    public RestTemplate jwkSetRestTemplate() {
        return new RestTemplate();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${security.oauth2.issuer}") String issuer,
            @Value("${security.oauth2.jwk-set-uri}") String jwkSetUri,
            @Value("${security.oauth2.audience}") String audience,
            RestOperations jwkSetRestTemplate) {

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(jwkSetRestTemplate)
                .build();

        OAuth2TokenValidator<Jwt> issuerAndTime = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Token audience is not this MCP resource", null));

        decoder.setJwtValidator(jwt -> {
            OAuth2TokenValidatorResult issuerResult = issuerAndTime.validate(jwt);
            return issuerResult.hasErrors() ? issuerResult : audienceValidator.validate(jwt);
        });
        return decoder;
    }
}
