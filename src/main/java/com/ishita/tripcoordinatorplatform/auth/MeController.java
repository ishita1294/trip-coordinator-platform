package com.ishita.tripcoordinatorplatform.auth;
import org.springframework.web.bind.annotation.*;
/** Returns the signed-in user's local application profile. */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class MeController {
 private final CurrentUser current;
 public MeController(CurrentUser current){this.current=current;}
 public record Me(Long id,String name,String email) {}
 @GetMapping("/me") public Me me(){var user=current.require();return new Me(user.getId(),user.getName(),user.getEmail());}
}
