package com.ishita.tripcoordinatorplatform.auth;
import com.ishita.tripcoordinatorplatform.model.User;
import jakarta.persistence.*;
import java.time.Instant;
/** Stores the permanent link between an external identity and a local application user. */
@Entity
@Table(name="user_auth_identities",uniqueConstraints=@UniqueConstraint(name="uq_user_auth_identity",columnNames={"issuer","subject"}))
public class UserAuthIdentity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,length=512,updatable=false) private String issuer;
 @Column(nullable=false,length=255,updatable=false) private String subject;
 @ManyToOne(optional=false) @JoinColumn(name="user_id",nullable=false,updatable=false) private User user;
 @Column(nullable=false,updatable=false) private Instant linkedAt;
 protected UserAuthIdentity() {}
 public UserAuthIdentity(String issuer,String subject,User user){this.issuer=issuer;this.subject=subject;this.user=user;this.linkedAt=Instant.now();}
 public User getUser(){return user;}
}
