package se.tcmt.muninn.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.service.TokenService;

/**
 * Authenticates requests carrying {@code Authorization: Bearer <access jwt>}. An invalid token
 * leaves the request anonymous, so protected paths answer 401 via the entry point. Not a bean:
 * registered only in the security chain, never as a plain servlet filter.
 */
@RequiredArgsConstructor
public final class BearerTokenFilter extends OncePerRequestFilter {

  private static final String PREFIX = "Bearer ";

  private final TokenService tokens;

  /**
   * Authenticates the request from a valid bearer access token; an invalid token leaves it
   * anonymous.
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
      try {
        AppUser user = tokens.verifyAccess(header.substring(PREFIX.length()).trim());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))));
        SecurityContextHolder.setContext(ctx);
      } catch (ResponseStatusException e) {
        SecurityContextHolder.clearContext();
      }
    }
    chain.doFilter(request, response);
  }
}
