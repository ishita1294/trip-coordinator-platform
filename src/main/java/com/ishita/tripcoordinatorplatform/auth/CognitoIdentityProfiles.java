package com.ishita.tripcoordinatorplatform.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Retrieves a verified Cognito profile when a local user needs to be created. The profile must match the signed-in identity and have a verified email address. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CognitoIdentityProfiles implements IdentityProfiles {
    private final RestClient client;
    private final String endpoint;

    @org.springframework.beans.factory.annotation.Autowired
    public CognitoIdentityProfiles(@Value("${app.auth.user-info-uri}") String endpoint) {
        this(endpoint, client());
    }

    // Tests supply a mock HTTP client; runtime authentication still uses Cognito.
    CognitoIdentityProfiles(String endpoint, RestClient client) {
        SecurityConfig.requireHttps(endpoint);
        this.endpoint = endpoint;
        this.client = client;
    }

    private static RestClient client() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Profile verifiedProfile(Jwt jwt) {
        try {
            var facts = client.get().uri(endpoint).headers(h -> h.setBearerAuth(jwt.getTokenValue()))
                    .retrieve().body(Map.class);
            // Cognito documents email_verified as the string "true"; also accept OIDC boolean true.
            if (facts == null || !jwt.getSubject().equals(facts.get("sub"))
                    || !(Boolean.TRUE.equals(facts.get("email_verified"))
                    || "true".equals(facts.get("email_verified")))) throw new IllegalArgumentException();
            Object email = facts.get("email");
            Object name = facts.get("name");
            if (!(email instanceof String text) || text.length() > 255
                    || !text.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException();
            String displayName = name instanceof String textName && !textName.isBlank() ? textName : text;
            if (displayName.length() > 255) throw new IllegalArgumentException();
            return new Profile(displayName, text);
        } catch (RuntimeException failure) {
            // Never include the token, response body or provider exception in client-facing errors.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Verified account profile is unavailable; no local account was created");
        }
    }
}
