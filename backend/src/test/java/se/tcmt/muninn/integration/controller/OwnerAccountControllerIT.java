package se.tcmt.muninn.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import se.tcmt.muninn.repository.UserRepository;
import se.tcmt.muninn.service.OwnerAccountService;
import se.tcmt.muninn.service.SetupService;
import se.tcmt.muninn.support.AbstractPostgresIT;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HTTP surface of the single owner account: first-run creation, login, password reset. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class OwnerAccountControllerIT extends AbstractPostgresIT {

  private static final Pattern SETUP_TOKEN = Pattern.compile("SETUP TOKEN: (\\S+)");
  private static final Pattern RECOVERY_TOKEN = Pattern.compile("SETUP RECOVERY TOKEN: (\\S+)");
  private static final String HEADER = "X-Setup-Token";
  private static final String PASSWORD = "correct horse battery";

  @Autowired MockMvc mvc;

  @Autowired SetupService setup;

  @Autowired UserRepository users;

  @Autowired JdbcTemplate jdbc;

  @Autowired ObjectMapper json;

  @BeforeEach
  void reset() {
    jdbc.update("delete from app_user");
    jdbc.update("delete from app_setting");
  }

  private static String lastMatch(Pattern p, CapturedOutput out) {
    Matcher m = p.matcher(out.getAll());
    List<String> all = m.results().map(r -> r.group(1)).toList();
    assertThat(all).isNotEmpty();
    return all.get(all.size() - 1);
  }

  private String freshSetupToken(CapturedOutput out) {
    setup.reissueToken();
    return lastMatch(SETUP_TOKEN, out);
  }

  private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder b, Object body)
      throws Exception {
    return b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
  }

  private JsonNode body(MvcResult result) throws Exception {
    String content = result.getResponse().getContentAsString();
    return content.isEmpty() ? null : json.readTree(content);
  }

  private static String email() {
    return "owner-" + UUID.randomUUID() + "@example.com";
  }

  private JsonNode createOwner(String token, String email) throws Exception {
    return body(
        mvc.perform(
                withJson(post("/api/setup/account"), Map.of("email", email, "password", PASSWORD))
                    .header(HEADER, token))
            .andExpect(status().isCreated())
            .andReturn());
  }

  @Test
  void firstRunCreatesTheOwnerAndSignsThemIn(CapturedOutput out) throws Exception {
    String email = email();

    JsonNode created = createOwner(freshSetupToken(out), email.toUpperCase());

    assertThat(created.at("/user/email").asText()).isEqualTo(email);
    String access = created.get("accessToken").asText();
    mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(email));
    mvc.perform(get("/api/setup/status")).andExpect(jsonPath("$.complete").value(true));
    mvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
        .andExpect(status().isOk());
  }

  @Test
  void noSecondAccountIsPossible(CapturedOutput out) throws Exception {
    String token = freshSetupToken(out);
    createOwner(token, email());

    mvc.perform(
            withJson(post("/api/setup/account"), Map.of("email", email(), "password", PASSWORD))
                .header(HEADER, token))
        .andExpect(status().isNotFound());
    setup.reissueToken(); // after completion this prints a recovery token
    mvc.perform(
            withJson(post("/api/setup/account"), Map.of("email", email(), "password", PASSWORD))
                .header(HEADER, lastMatch(RECOVERY_TOKEN, out)))
        .andExpect(status().isNotFound());
    assertThat(users.count()).isEqualTo(1);
  }

  @Test
  void loginAcceptsTheRightPasswordAndRefreshes(CapturedOutput out) throws Exception {
    String email = email();
    createOwner(freshSetupToken(out), email);

    JsonNode login =
        body(
            mvc.perform(
                    withJson(post("/api/auth/login"), Map.of("email", email, "password", PASSWORD)))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(login.at("/user/role").asText()).isEqualTo("ADMIN");

    // No SSO settings exist at all, yet the owner can keep refreshing.
    mvc.perform(
            withJson(
                post("/api/auth/refresh"),
                Map.of("refreshToken", login.get("refreshToken").asText())))
        .andExpect(status().isOk());
  }

  @Test
  void loginFailuresLookIdenticalAndThrottle(CapturedOutput out) throws Exception {
    String email = email();
    createOwner(freshSetupToken(out), email);

    MvcResult wrongPassword =
        mvc.perform(
                withJson(
                    post("/api/auth/login"), Map.of("email", email, "password", "nope nope nope")))
            .andExpect(status().isUnauthorized())
            .andReturn();
    MvcResult unknownEmail =
        mvc.perform(
                withJson(
                    post("/api/auth/login"),
                    Map.of("email", email(), "password", "nope nope nope")))
            .andExpect(status().isUnauthorized())
            .andReturn();
    assertThat(wrongPassword.getResponse().getErrorMessage())
        .isEqualTo(OwnerAccountService.BAD_CREDENTIALS)
        .isEqualTo(unknownEmail.getResponse().getErrorMessage());
    assertThat(wrongPassword.getResponse().getContentAsString())
        .isEqualTo(unknownEmail.getResponse().getContentAsString());

    for (int i = 0; i < 4; i++) { // five failures for this email in total
      mvc.perform(
              withJson(
                  post("/api/auth/login"), Map.of("email", email, "password", "nope nope nope")))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(withJson(post("/api/auth/login"), Map.of("email", email, "password", PASSWORD)))
        .andExpect(status().isTooManyRequests());

    mvc.perform(withJson(post("/api/auth/login"), Map.of("email", " ", "password", PASSWORD)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void passwordResetWithARecoveryToken(CapturedOutput out) throws Exception {
    String email = email();
    JsonNode created = createOwner(freshSetupToken(out), email);
    setup.reissueToken();
    String recovery = lastMatch(RECOVERY_TOKEN, out);

    mvc.perform(
            withJson(put("/api/setup/account"), Map.of("password", "a brand new password"))
                .header(HEADER, recovery))
        .andExpect(status().isNoContent());

    mvc.perform(withJson(post("/api/auth/login"), Map.of("email", email, "password", PASSWORD)))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            withJson(
                post("/api/auth/login"),
                Map.of("email", email, "password", "a brand new password")))
        .andExpect(status().isOk());
    mvc.perform(
            withJson(
                post("/api/auth/refresh"),
                Map.of("refreshToken", created.get("refreshToken").asText())))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            withJson(put("/api/setup/account"), Map.of("password", "yet another password"))
                .header(HEADER, recovery))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void weakPasswordIsRejectedAndNothingIsCreated(CapturedOutput out) throws Exception {
    mvc.perform(
            withJson(post("/api/setup/account"), Map.of("email", email(), "password", "123456789"))
                .header(HEADER, freshSetupToken(out)))
        .andExpect(status().isBadRequest());

    assertThat(users.count()).isZero();
    assertThat(setup.isComplete()).isFalse();
  }
}
