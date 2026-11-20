package com.muninn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// No password accounts: drop Boot's default in-memory user (and its generated-password log line).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class MuninnApplication {

    public static void main(String[] args) {
        SpringApplication.run(MuninnApplication.class, args);
    }
}
