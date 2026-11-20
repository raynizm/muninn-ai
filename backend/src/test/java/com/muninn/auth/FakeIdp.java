package com.muninn.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muninn.crypto.SecretCipher;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** In-process OpenID provider: discovery, JWKS and a token endpoint, signing with a generated RSA key. */
public final class FakeIdp implements AutoCloseable {

    public static final String CLIENT_ID = "muninn-test-client";
    public static final String CLIENT_SECRET = "test-client-secret";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final RSAKey key;
    private final String issuer;
    private final Map<String, String> idTokenByCode = new ConcurrentHashMap<>();
    private volatile Map<String, String> lastTokenRequest;

    private FakeIdp() throws IOException, JOSEException {
        key = rsaKey();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/tenant";
        server.createContext(
                "/tenant/.well-known/openid-configuration",
                ex -> json(
                        ex,
                        200,
                        Map.of(
                                "issuer", issuer,
                                "authorization_endpoint", issuer + "/authorize",
                                "token_endpoint", issuer + "/token",
                                "jwks_uri", issuer + "/jwks")));
        server.createContext("/tenant/jwks", ex -> json(ex, 200, new JWKSet(key.toPublicJWK()).toJSONObject()));
        server.createContext("/tenant/token", this::token);
        server.start();
    }

    public static FakeIdp start() {
        try {
            return new FakeIdp();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static RSAKey rsaKey() throws JOSEException {
        return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    }

    public String issuer() {
        return issuer;
    }

    Map<String, String> lastTokenRequest() {
        return lastTokenRequest;
    }

    /** Writes the OIDC and policy settings the backend reads from app_setting. */
    void seedSettings(JdbcTemplate jdbc, SecretCipher cipher, String allowedDomains, String tenantId) {
        put(jdbc, "oidc.issuer", issuer);
        put(jdbc, "oidc.clientId", CLIENT_ID);
        put(jdbc, "oidc.clientSecret", cipher.encrypt(CLIENT_SECRET));
        put(jdbc, "auth.allowedDomains", allowedDomains);
        if (tenantId != null) {
            put(jdbc, "auth.tenantId", tenantId);
        }
    }

    static void put(JdbcTemplate jdbc, String key, String value) {
        jdbc.update(
                "insert into app_setting(key, value) values (?, ?) "
                        + "on conflict (key) do update set value = excluded.value",
                key,
                value);
    }

    /** Valid claims; the subject is derived from the email so repeat logins are the same identity. */
    public JWTClaimsSet.Builder validClaims(String email) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(CLIENT_ID)
                .subject("sub-" + email.toLowerCase(Locale.ROOT))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("email", email)
                .claim("email_verified", true)
                .claim("name", "Test User");
    }

    public String sign(JWTClaimsSet claims) {
        return signWith(key, claims);
    }

    static String signWith(RSAKey signingKey, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(signingKey.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    /** Registers a one-time authorization code that the token endpoint exchanges for {@code idToken}. */
    public String codeFor(String idToken) {
        String code = UUID.randomUUID().toString();
        idTokenByCode.put(code, idToken);
        return code;
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> form = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastTokenRequest = form;
        String idToken = form.get("code") == null ? null : idTokenByCode.remove(form.get("code"));
        boolean ok = "POST".equals(ex.getRequestMethod())
                && "authorization_code".equals(form.get("grant_type"))
                && CLIENT_ID.equals(form.get("client_id"))
                && CLIENT_SECRET.equals(form.get("client_secret"))
                && form.get("code_verifier") != null
                && form.get("redirect_uri") != null
                && idToken != null;
        if (!ok) {
            json(ex, 400, Map.of("error", "invalid_grant"));
            return;
        }
        json(ex, 200, Map.of("access_token", "idp-access", "token_type", "Bearer", "id_token", idToken));
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                out.put(
                        URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
