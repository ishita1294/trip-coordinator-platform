package com.ishita.tripcoordinatorplatform.auth;
import org.springframework.security.oauth2.jwt.Jwt;
/** Provides verified account details for creating a local user from an external identity. */
public interface IdentityProfiles {
 record Profile(String name,String email) {}
 Profile verifiedProfile(Jwt jwt);
}
