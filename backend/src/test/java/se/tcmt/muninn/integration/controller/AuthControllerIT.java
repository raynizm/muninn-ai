package se.tcmt.muninn.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import se.tcmt.muninn.crypto.SecretCipher;
import se.tcmt.muninn.service.SetupService;
import se.tcmt.muninn.support.AbstractPostgresIT;
import se.tcmt.muninn.support.FakeIdp;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HTTP surface of auth plus the bearer-token protection of /api/**. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIT extends AbstractPostgresIT {

  private static final FakeIdp IDP = FakeIdp.start();

  @Autowired MockMvc mvc;

  @Autowired SetupService setup;

  @Autowired SecretCipher cipher;

  @Autowired JdbcTemplate jdbc;

  @Autowired ObjectMapper json;

  @AfterAll
  static void stopIdp() {
    IDP.close();
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from app_user");
    jdbc.update("delete from app_setting");
    IDP.seedSettings(jdbc, cipher, "acme.com", null);
    setup.markComplete();
  }

  private JsonNode postJson(String path, Object body, int expectedStatus) throws Exception {
    String response =
        mvc.perform(
                post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().is(expectedStatus))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return response.isEmpty() ? null : json.readTree(response);
  }

  private JsonNode callback(String email) throws Exception {
    String code = IDP.codeFor(IDP.sign(IDP.validClaims(email).build()));
    return postJson(
        "/api/auth/callback",
        Map.of("code", code, "codeVerifier", "verifier", "redirectUri", "http://localhost/cb"),
        200);
  }

  @Test
  void configExposesPublicFieldsOnly() throws Exception {
    String body =
        mvc.perform(get("/api/auth/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issuer").value(IDP.issuer()))
            .andExpect(jsonPath("$.clientId").value(FakeIdp.CLIENT_ID))
            .andExpect(jsonPath("$.authorizationEndpoint").value(IDP.issuer() + "/authorize"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain(FakeIdp.CLIENT_SECRET).doesNotContainIgnoringCase("secret");
  }

  @Test
  void callbackRefreshLogoutRoundTrip() throws Exception {
    JsonNode login = callback("ann@acme.com");
    assertThat(login.at("/user/email").asText()).isEqualTo("ann@acme.com");
    assertThat(login.at("/user/role").asText()).isEqualTo("USER");
    String refresh = login.get("refreshToken").asText();

    JsonNode rotated = postJson("/api/auth/refresh", Map.of("refreshToken", refresh), 200);
    String newRefresh = rotated.get("refreshToken").asText();
    assertThat(rotated.get("accessToken").asText()).isNotBlank();
    postJson("/api/auth/refresh", Map.of("refreshToken", refresh), 401);

    postJson("/api/auth/logout", Map.of("refreshToken", newRefresh), 204);
    postJson("/api/auth/refresh", Map.of("refreshToken", newRefresh), 401);
  }

  @Test
  void callbackRejectsDisallowedDomainAndMissingFields() throws Exception {
    String code = IDP.codeFor(IDP.sign(IDP.validClaims("ann@evil.com").build()));
    postJson(
        "/api/auth/callback",
        Map.of("code", code, "codeVerifier", "v", "redirectUri", "http://localhost/cb"),
        403);
    postJson("/api/auth/callback", Map.of("code", "x"), 400);
  }

  @Test
  void apiRequiresValidBearerToken() throws Exception {
    mvc.perform(get("/api/anything")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/anything").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/anything").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
        .andExpect(status().isUnauthorized());

    // Only the four listed auth endpoints are public; other /api/auth paths are protected.
    mvc.perform(get("/api/auth/anything-else")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/auth/refresh")).andExpect(status().isUnauthorized());

    String access = callback("ann@acme.com").get("accessToken").asText();
    // Authenticated: passes security and reaches MVC, which has no such route.
    mvc.perform(get("/api/anything").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
        .andExpect(status().isNotFound());
  }

  @Test
  void meReturnsTheSignedInUserAndNeedsAToken() throws Exception {
    mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

    String access = callback("ann@acme.com").get("accessToken").asText();
    mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("ann@acme.com"))
        .andExpect(jsonPath("$.role").value("USER"));
  }

  @Test
  void webAppRoutesAreServedWithoutAuthentication() throws Exception {
    for (String route : new String[] {"/", "/login", "/login/callback", "/setup", "/settings"}) {
      mvc.perform(get(route)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }
    mvc.perform(get("/recordings/6b2f3c52-98b5-4b6e-9d6a-7a1c9a5c2b10"))
        .andExpect(forwardedUrl("/index.html"));
  }

  @Test
  void setupStatusStaysPublic() throws Exception {
    mvc.perform(get("/api/setup/status")).andExpect(status().isOk());
  }
}
