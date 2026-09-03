package com.ishita.tripcoordinatorplatform.service;
import com.ishita.tripcoordinatorplatform.model.User;
import com.ishita.tripcoordinatorplatform.repository.UserRepository;

import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User createUser(User user) {
        return userRepository.save(user);
    }

}
