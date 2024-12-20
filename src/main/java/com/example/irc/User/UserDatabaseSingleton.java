package com.example.irc.User;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class UserDatabaseSingleton {
    private static volatile UserDatabaseSingleton instance;
    private final UserService userService;

    @Autowired
    private UserDatabaseSingleton(UserService userService) {
        this.userService = userService;
    }

    public static UserDatabaseSingleton getInstance(UserService userService) {
        UserDatabaseSingleton result = instance;
        if (result == null) {
            synchronized (UserDatabaseSingleton.class) {
                result = instance;
                if (result == null) {
                    instance = result = new UserDatabaseSingleton(userService);
                }
            }
        }
        return result;
    }

    public Optional<User> registerUser(String username, String password, String email) {
        User newUser = User.builder()
                .username(username)
                .password(password)
                .email(email)
                .build();
        return userService.registerUser(newUser);
    }

    public Optional<User> authenticateUser(String username, String password) {
        return userService.authenticateUser(username, password);
    }

    public Optional<User> findUser(String username) {
        return userService.findByUsername(username);
    }
}
