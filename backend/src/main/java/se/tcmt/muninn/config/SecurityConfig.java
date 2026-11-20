package se.tcmt.muninn.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import se.tcmt.muninn.security.BearerTokenFilter;
import se.tcmt.muninn.service.TokenService;

@Configuration
public class SecurityConfig {

  /**
   * Configures the stateless API: bearer authentication, public auth and setup paths, ADMIN-only
   * settings.
   */
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, TokenService tokens) throws Exception {
    return http
        // CSRF protection is off because the API is stateless: no session, no auth cookies.
        // Credentials travel only in the Authorization bearer header, which a browser never
        // attaches to cross-site requests on its own. Revisit if cookie auth is ever added.
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .addFilterBefore(new BearerTokenFilter(tokens), UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            a ->
                a
                    // Let Boot render error responses (e.g. a 403 from a public endpoint) as-is.
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    // Setup endpoints guard themselves: setup state, setup/recovery token or admin
                    // bearer.
                    .requestMatchers("/api/setup/**")
                    .permitAll()
                    // Pre-login auth endpoints, listed one by one so new /api/auth routes default
                    // to protected.
                    .requestMatchers(HttpMethod.GET, "/api/auth/config")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/auth/callback",
                        "/api/auth/refresh",
                        "/api/auth/logout")
                    .permitAll()
                    .requestMatchers("/api/settings", "/api/settings/**")
                    .hasRole("ADMIN")
                    // Everything else under /api needs a token; the web app (static files and
                    // client-side routes) is public.
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .build();
  }
}
