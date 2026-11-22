package se.tcmt.muninn.integration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import se.tcmt.muninn.service.SetupService;
import se.tcmt.muninn.support.AbstractPostgresIT;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Changing the owner's email and password while signed in. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AccountControllerIT extends AbstractPostgresIT {

  private static final Pattern SETUP_TOKEN = Pattern.compile("SETUP TOKEN: (\\S+)");
  private static final String PASSWORD = "correct horse battery";

  @Autowired MockMvc mvc;

  @Autowired SetupService setup;

  @Autowired JdbcTemplate jdbc;

  @Autowired ObjectMapper json;

  @BeforeEach
  void reset() {
    jdbc.update("delete from app_user");
    jdbc.update("delete from app_setting");
  }

  private String freshSetupToken(CapturedOutput out) {
    setup.reissueToken();
    Matcher m = SETUP_TOKEN.matcher(out.getAll());
    List<String> all = m.results().map(r -> r.group(1)).toList();
    return all.get(all.size() - 1);
  }

  private static String email() {
    return "owner-" + UUID.randomUUID() + "@example.com";
  }

  private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder b, Object body)
      throws Exception {
    return b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
  }

  private static MockHttpServletRequestBuilder bearer(
      MockHttpServletRequestBuilder b, String access) {
    return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + access);
  }

  /** Creates the owner and returns the response with the session tokens. */
  private JsonNode createOwner(CapturedOutput out, String email) throws Exception {
    String content =
        mvc.perform(
                withJson(post("/api/setup/account"), Map.of("email", email, "password", PASSWORD))
                    .header("X-Setup-Token", freshSetupToken(out)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(content);
  }

  @Test
  void emailChangeRoundTrip(CapturedOutput out) throws Exception {
    String access = createOwner(out, email()).get("accessToken").asText();
    String newEmail = email();

    mvc.perform(
            bearer(
                withJson(
                    put("/api/account/email"),
                    Map.of("currentPassword", PASSWORD, "email", newEmail)),
                access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(newEmail));

    mvc.perform(bearer(get("/api/auth/me"), access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(newEmail));
  }

  @Test
  void passwordChangeReturnsNewTokensAndRevokesTheOldOnes(CapturedOutput out) throws Exception {
    JsonNode created = createOwner(out, email());
    String access = created.get("accessToken").asText();

    String content =
        mvc.perform(
                bearer(
                    withJson(
                        put("/api/account/password"),
                        Map.of("currentPassword", PASSWORD, "newPassword", "a brand new password")),
                    access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode renewed = json.readTree(content);

    mvc.perform(
            withJson(
                post("/api/auth/refresh"),
                Map.of("refreshToken", created.get("refreshToken").asText())))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            withJson(
                post("/api/auth/refresh"),
                Map.of("refreshToken", renewed.get("refreshToken").asText())))
        .andExpect(status().isOk());
  }

  @Test
  void bothEndpointsNeedAToken() throws Exception {
    mvc.perform(
            withJson(
                put("/api/account/email"), Map.of("currentPassword", PASSWORD, "email", email())))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            withJson(
                put("/api/account/password"),
                Map.of("currentPassword", PASSWORD, "newPassword", "a brand new password")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aWrongCurrentPasswordIs403NotAnExpiredSession(CapturedOutput out) throws Exception {
    String access = createOwner(out, email()).get("accessToken").asText();

    mvc.perform(
            bearer(
                withJson(
                    put("/api/account/email"),
                    Map.of("currentPassword", "not my password", "email", email())),
                access))
        .andExpect(status().isForbidden());
  }

  @Test
  void blankFieldsAre400(CapturedOutput out) throws Exception {
    String access = createOwner(out, email()).get("accessToken").asText();

    mvc.perform(
            bearer(
                withJson(
                    put("/api/account/email"), Map.of("currentPassword", " ", "email", email())),
                access))
        .andExpect(status().isBadRequest());
    mvc.perform(
            bearer(
                withJson(
                    put("/api/account/password"),
                    Map.of("currentPassword", PASSWORD, "newPassword", "")),
                access))
        .andExpect(status().isBadRequest());
  }
}
