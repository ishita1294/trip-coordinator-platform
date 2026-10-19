package com.ishita.tripcoordinatorplatform.auth;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;
/** Finds an external identity together with its linked application user. */
public interface UserAuthIdentityRepository extends JpaRepository<UserAuthIdentity,Long> {
 @EntityGraph(attributePaths="user") Optional<UserAuthIdentity> findByIssuerAndSubject(String issuer,String subject);
}
