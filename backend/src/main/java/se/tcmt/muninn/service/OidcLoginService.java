package se.tcmt.muninn.service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.repository.UserRepository;

/**
 * Authorization-code (PKCE) login against the configured OpenID provider. The ID token is validated
 * (JWKS signature, iss, aud, azp, exp) and run through {@link DomainPolicy} before any user is created.
 */
@Service
public class OidcLoginService {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginService.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    public record LoginResult(TokenService.TokenPair tokens, AppUser user) {}

    private record Provider(String authorizationEndpoint, String tokenEndpoint, JwtDecoder decoder) {}

    /** A validated IdP identity. (issuer, subject) is the account key; email is only a policy input. */
    private record Identity(String issuer, String subject, String email, String name) {}

    private final AuthSettings settings;
    private final DomainPolicy policy;
    private final UserRepository users;
    private final TokenService tokens;
    private final SetupService setup;
    private final TransactionTemplate tx;
    private final RestClient http;
    private final RestTemplate jwksHttp;
    private final ConcurrentMap<String, Provider> providers = new ConcurrentHashMap<>();

    public OidcLoginService(
            AuthSettings settings,
            DomainPolicy policy,
            UserRepository users,
            TokenService tokens,
            SetupService setup,
            TransactionTemplate tx) {
        this.settings = settings;
        this.policy = policy;
        this.users = users;
        this.tokens = tokens;
        this.setup = setup;
        this.tx = tx;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
        this.jwksHttp = new RestTemplate(factory);
    }

    /** Normal login: setup must be complete. New users get USER; existing users keep their role. */
    public LoginResult login(String code, String codeVerifier, String redirectUri) {
        if (!setup.isComplete()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "setup not complete");
        }
        Identity id = authenticate(code, codeVerifier, redirectUri);
        AppUser user = resolveUser(id, AppUser.Role.USER);
        return new LoginResult(tokens.issue(user), user);
    }

    /** First-run login: a valid setup token makes this user the first ADMIN, if none exists yet. */
    public LoginResult loginAsFirstAdmin(String code, String codeVerifier, String redirectUri, String setupToken) {
        if (!setup.verifyToken(setupToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid setup token");
        }
        Identity id = authenticate(code, codeVerifier, redirectUri);
        AppUser user = tx.execute(status -> {
            users.lockForAdminBootstrap();
            if (users.adminExists()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "an admin already exists");
            }
            AppUser resolved = resolveUser(id, AppUser.Role.ADMIN);
            return resolved.role() == AppUser.Role.ADMIN ? resolved : users.setRole(resolved, AppUser.Role.ADMIN);
        });
        return new LoginResult(tokens.issue(user), user);
    }

    /**
     * Finds the account bound to (issuer, subject). An unbound legacy row with the same email is bound
     * once; an email already bound to a different subject is refused, so a recycled or edited email
     * address can never take over an existing account. New accounts get {@code roleForNew}.
     */
    private AppUser resolveUser(Identity id, AppUser.Role roleForNew) {
        Optional<AppUser> bound = users.findByOidcIdentity(id.issuer(), id.subject());
        if (bound.isPresent()) {
            return bound.get();
        }
        Optional<AppUser> sameEmail = users.findByEmail(id.email());
        if (sameEmail.isPresent()) {
            users.bindOidcIdentityIfUnbound(sameEmail.get().id(), id.issuer(), id.subject());
        } else {
            // on conflict do nothing: a concurrent first login of the same identity may have won.
            users.insertIfAbsent(id.email(), id.name(), roleForNew, id.issuer(), id.subject());
        }
        return users.findByOidcIdentity(id.issuer(), id.subject())
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.FORBIDDEN, "email is linked to a different account"));
    }

    public String authorizationEndpoint(AuthSettings.Oidc cfg) {
        return provider(cfg).authorizationEndpoint();
    }

    private Identity authenticate(String code, String codeVerifier, String redirectUri) {
        AuthSettings.Oidc cfg = settings.oidc()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "oidc not configured"));
        Provider provider = provider(cfg);
        String idToken = exchange(provider, cfg, code, codeVerifier, redirectUri);

        Jwt jwt;
        try {
            jwt = provider.decoder().decode(idToken);
        } catch (JwtException e) {
            // Message only: never log the token itself.
            log.warn("rejected id token: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid id token");
        }

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid id token");
        }
        String email = jwt.getClaimAsString("email");
        String tenantId = jwt.getClaimAsString("tid");
        Object verifiedClaim = jwt.getClaims().get("email_verified");
        boolean verified = Boolean.TRUE.equals(verifiedClaim) || "true".equals(verifiedClaim);
        // Entra often omits email_verified. Only when a tenant is pinned do we accept that,
        // relying on DomainPolicy's tid match instead. An explicit false is never accepted.
        if (verifiedClaim == null && settings.tenantId().isPresent()) {
            verified = true;
        }
        if (!policy.allows(email, verified, tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account not allowed");
        }
        // cfg.issuer() equals the token's iss: the issuer validator enforced it.
        return new Identity(cfg.issuer(), subject, email.toLowerCase(Locale.ROOT), jwt.getClaimAsString("name"));
    }

    private String exchange(
            Provider provider, AuthSettings.Oidc cfg, String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", cfg.clientId());
        form.add("client_secret", cfg.clientSecret());
        Map<String, Object> body;
        try {
            body = http.post()
                    .uri(provider.tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JSON_MAP);
        } catch (RestClientResponseException e) {
            log.warn("code exchange failed with status {}", e.getStatusCode().value());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "code exchange failed");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "identity provider unreachable");
        }
        if (body == null || !(body.get("id_token") instanceof String idToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no id token");
        }
        return idToken;
    }

    private Provider provider(AuthSettings.Oidc cfg) {
        return providers.computeIfAbsent(cfg.issuer() + "\n" + cfg.clientId(), k -> discover(cfg));
    }

    private Provider discover(AuthSettings.Oidc cfg) {
        String issuer = cfg.issuer();
        String url = (issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer)
                + "/.well-known/openid-configuration";
        Map<String, Object> doc;
        try {
            doc = http.get().uri(url).retrieve().body(JSON_MAP);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "oidc discovery failed");
        }
        if (doc == null
                || !issuer.equals(doc.get("issuer"))
                || !(doc.get("authorization_endpoint") instanceof String authz)
                || !(doc.get("token_endpoint") instanceof String token)
                || !(doc.get("jwks_uri") instanceof String jwks)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "invalid oidc discovery document");
        }
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withJwkSetUri(jwks).restOperations(jwksHttp).build();
        decoder.setJwtValidator(idTokenValidator(issuer, cfg.clientId()));
        return new Provider(authz, token, decoder);
    }

    /** azp is optional, but when present it must be this client. */
    private static OAuth2TokenValidator<Jwt> azpValidator(String clientId) {
        return jwt -> {
            Object azp = jwt.getClaim("azp");
            return azp == null || clientId.equals(azp)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                            new OAuth2Error("invalid_token", "The azp claim is not valid", null));
        };
    }

    private static OAuth2TokenValidator<Jwt> idTokenValidator(String issuer, String clientId) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), // exp/nbf (60s skew) + iss
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(clientId)),
                new JwtClaimValidator<Object>(JwtClaimNames.EXP, Objects::nonNull),
                azpValidator(clientId));
    }
}
