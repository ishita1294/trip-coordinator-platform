package com.ishita.tripcoordinatorplatform.auth;

import com.ishita.tripcoordinatorplatform.model.User;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;

/** Provides the local application user for the verified JWT identity. Resolves the identity once per HTTP request. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CurrentUser {
    private final UserIdentityService identities;
    public CurrentUser(UserIdentityService identities) { this.identities = identities; }

    public User require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        var request = RequestContextHolder.getRequestAttributes();
        String key = CurrentUser.class.getName();
        if (request != null && request.getAttribute(key, RequestAttributes.SCOPE_REQUEST) instanceof User user) return user;
        var user = identities.resolve(token.getToken());
        // Cache the user only for this request so later requests resolve their own signed-in user.
        if (request != null) request.setAttribute(key, user, RequestAttributes.SCOPE_REQUEST);
        return user;
    }
}
