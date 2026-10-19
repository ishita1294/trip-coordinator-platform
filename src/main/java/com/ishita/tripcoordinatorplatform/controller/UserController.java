package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.CurrentUser;
import com.ishita.tripcoordinatorplatform.auth.MeController;
import com.ishita.tripcoordinatorplatform.model.User;
import com.ishita.tripcoordinatorplatform.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class UserController {
    private final CurrentUser current;
    private final UserRepository users;
    public UserController(CurrentUser current, UserRepository users) { this.current=current; this.users=users; }
    /** Keeps the legacy registration URL limited to updating the signed-in user's name. The submitted email must match that user, and a user ID must not be supplied. */
    @PostMapping("/users")
    public MeController.Me register(@RequestBody User submitted) {
        var actor=current.require();
        if (submitted.getId()!=null || !actor.getEmail().equals(submitted.getEmail()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Registration must match your verified identity");
        return update(submitted.getName());
    }
    public record Profile(String name) {}
    @PutMapping("/me")
    public MeController.Me profile(@RequestBody Profile request) { return update(request.name()); }
    private MeController.Me update(String name) {
        if(name==null || name.isBlank() || name.length()>255)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Display name must contain 1–255 characters");
        var actor=current.require(); actor.setName(name.trim()); users.save(actor);
        return new MeController.Me(actor.getId(),actor.getName(),actor.getEmail());
    }
}
