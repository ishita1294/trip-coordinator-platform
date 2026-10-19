package com.ishita.tripcoordinatorplatform.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import java.net.URI;
import java.util.Arrays;

/** Verifies Cognito access tokens and requires authentication for the listed API routes. All other routes are denied unless explicitly allowed. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(@Value("${app.auth.issuer}") String issuer,
                          @Value("${app.auth.jwk-set-uri}") String jwks,
                          @Value("${app.auth.client-id}") String client,
                          @Value("${app.auth.api-scope}") String scope,
                          @Value("${app.auth.audience:}") String audience) {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3))
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));
        return configuredDecoder(issuer, jwks, client, scope, audience,
                new org.springframework.web.client.RestTemplate(factory));
    }

    static NimbusJwtDecoder configuredDecoder(String issuer, String jwks, String client, String scope,
                                              String audience, org.springframework.web.client.RestOperations http) {
        requireHttps(issuer);
        requireHttps(jwks);
        if (!URI.create(jwks).getHost().equals(URI.create(issuer).getHost())) {
            throw new IllegalArgumentException("Authentication JWKS must belong to the configured issuer host");
        }
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).restOperations(http).build();
        decoder.setJwtValidator(validators(issuer, client, scope, audience));
        return decoder;
    }

    static void requireHttps(String value) {
        var uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Authentication endpoints require HTTPS");
        }
    }

    public static OAuth2TokenValidator<Jwt> validators(String issuer, String client, String scope, String audience) {
        if (issuer.isBlank() || client.isBlank() || scope.isBlank()) {
            throw new IllegalArgumentException("Explicit authentication issuer, client and API scope are required");
        }
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            String subject = jwt.getSubject();
            Object scopes = jwt.getClaims().get("scope");
            boolean valid = "access".equals(jwt.getClaimAsString("token_use"))
                    && client.equals(jwt.getClaimAsString("client_id"))
                    && subject != null && !subject.isBlank() && subject.length() <= 255
                    && !subject.equals(client) && jwt.getExpiresAt() != null
                    && scopes instanceof String text && Arrays.asList(text.split(" ")).contains(scope)
                    && (audience.isBlank() || (jwt.getAudience() != null && jwt.getAudience().contains(audience)));
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid API access token", null));
        };
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), claims);
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // API authentication uses bearer tokens, not browser cookies.
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a
                        // Allow Spring to render errors; direct requests to /error are still denied.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/me",
                                "/trips",
                                "/trips/{id:[0-9]+}"
                        ).authenticated()
                        .requestMatchers(HttpMethod.POST, "/trips").authenticated()
                        .requestMatchers(HttpMethod.GET, "/trips/{id:[0-9]+}/members").authenticated()
                        .requestMatchers(HttpMethod.POST, "/trips/{id:[0-9]+}/members").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/trips/{id:[0-9]+}/members/{userId:[0-9]+}").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/trips/{id:[0-9]+}/members/{userId:[0-9]+}/role").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/trips/{id:[0-9]+}/itinerary",
                                "/trips/{id:[0-9]+}/itinerary/items",
                                "/trips/{id:[0-9]+}/itinerary/items/{item:[0-9]+}/participants",
                                "/trips/{id:[0-9]+}/members/{member:[0-9]+}/itinerary/conflicts",
                                "/trips/{id:[0-9]+}/reservations",
                                "/trips/{id:[0-9]+}/reservations/{reservation:[0-9]+}/flight-segments",
                                "/trips/{id:[0-9]+}/destinations",
                                "/trips/{id:[0-9]+}/destinations/{destination:[0-9]+}/activities"
                        ).authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/trips/{id:[0-9]+}/itinerary",
                                "/trips/{id:[0-9]+}/itinerary/items",
                                "/trips/{id:[0-9]+}/itinerary/items/{item:[0-9]+}/participants/{member:[0-9]+}",
                                "/trips/{id:[0-9]+}/members/{member:[0-9]+}/itinerary/conflicts/{conflict:[0-9]+}/suggestions",
                                "/trips/{id:[0-9]+}/members/{member:[0-9]+}/itinerary/conflicts/{conflict:[0-9]+}/apply",
                                "/trips/{id:[0-9]+}/reservations",
                                "/trips/{id:[0-9]+}/reservations/{reservation:[0-9]+}/flight-segments",
                                "/trips/{id:[0-9]+}/destinations",
                                "/trips/{id:[0-9]+}/destinations/{destination:[0-9]+}/activities"
                        ).authenticated()
                        .requestMatchers(HttpMethod.PUT,
                                "/trips/{id:[0-9]+}",
                                "/trips/{id:[0-9]+}/itinerary/items/{item:[0-9]+}",
                                "/trips/{id:[0-9]+}/reservations/{reservation:[0-9]+}",
                                "/trips/{id:[0-9]+}/destinations/{destination:[0-9]+}",
                                "/trips/{id:[0-9]+}/destinations/{destination:[0-9]+}/activities/{activity:[0-9]+}"
                        ).authenticated()
                        .requestMatchers(HttpMethod.DELETE,
                                "/trips/{id:[0-9]+}",
                                "/trips/{id:[0-9]+}/itinerary/items/{item:[0-9]+}",
                                "/trips/{id:[0-9]+}/reservations/{reservation:[0-9]+}",
                                "/trips/{id:[0-9]+}/destinations/{destination:[0-9]+}",
                                "/trips/{id:[0-9]+}/destinations/{destination:[0-9]+}/activities/{activity:[0-9]+}"
                        ).authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/trips/{id:[0-9]+}/documents",
                                "/trips/{id:[0-9]+}/documents/{doc:[0-9]+}/extractions",
                                "/trips/{id:[0-9]+}/documents/{doc:[0-9]+}/extraction",
                                "/trips/{id:[0-9]+}/documents/{doc:[0-9]+}/extractions/{extraction:[0-9]+}"
                        ).authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/users",
                                "/trips/{id:[0-9]+}/documents",
                                "/trips/{id:[0-9]+}/documents/upload-url",
                                "/trips/{id:[0-9]+}/documents/complete-upload",
                                "/trips/{id:[0-9]+}/documents/{doc:[0-9]+}/process",
                                "/trips/{id:[0-9]+}/documents/{doc:[0-9]+}/confirm"
                        ).authenticated()
                        .requestMatchers(HttpMethod.PUT,"/me").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {}))
                .build();
    }
}
