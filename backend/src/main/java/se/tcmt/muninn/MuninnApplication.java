package se.tcmt.muninn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// No password accounts: drop Boot's default in-memory user (and its generated-password log line).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class MuninnApplication {

  /** Starts the application. */
  public static void main(String[] args) {
    SpringApplication.run(MuninnApplication.class, args);
  }
}
