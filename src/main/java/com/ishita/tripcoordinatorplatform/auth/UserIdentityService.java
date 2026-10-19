package com.ishita.tripcoordinatorplatform.auth;

import com.ishita.tripcoordinatorplatform.model.User;
import com.ishita.tripcoordinatorplatform.repository.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Maps a verified JWT identity to a local user, creating the user when needed. Existing users are never linked automatically by matching email addresses. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UserIdentityService {
    private final UserAuthIdentityRepository identities;
    private final UserRepository users;
    private final IdentityProfiles profiles;
    private final TransactionTemplate transaction;

    public UserIdentityService(UserAuthIdentityRepository identities, UserRepository users,
                               IdentityProfiles profiles, PlatformTransactionManager manager) {
        this.identities = identities;
        this.users = users;
        this.profiles = profiles;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public User resolve(Jwt jwt) {
        String issuer = jwt.getIssuer().toString();
        String subject = jwt.getSubject();
        var existing = identities.findByIssuerAndSubject(issuer, subject);
        if (existing.isPresent()) return existing.get().getUser();

        // Fetch the verified profile before starting the local account-creation transaction.
        var profile = profiles.verifiedProfile(jwt);
        try {
            return transaction.execute(status -> {
                var raced = identities.findByIssuerAndSubject(issuer, subject);
                if (raced.isPresent()) return raced.get().getUser();
                if (users.existsByEmailIgnoreCase(profile.email())) {
                    // Another login may have created this identity since the first lookup.
                    var committed = identities.findByIssuerAndSubject(issuer, subject);
                    if (committed.isPresent()) return committed.get().getUser();
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "This account requires reviewed linking to an existing user");
                }
                var user = new User();
                user.setName(profile.name());
                user.setEmail(profile.email());
                users.saveAndFlush(user);
                identities.saveAndFlush(new UserAuthIdentity(issuer, subject, user));
                return user;
            });
        } catch (DataIntegrityViolationException conflict) {
            // Account creation rolled back, including the new user. Check whether another login created the identity.
            return identities.findByIssuerAndSubject(issuer, subject)
                    .map(UserAuthIdentity::getUser)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "Account identity creation conflicted; retry login"));
        }
    }
}
