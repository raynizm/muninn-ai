package se.tcmt.muninn.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
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
import se.tcmt.muninn.service.JobWorker;
import se.tcmt.muninn.service.SetupService;
import se.tcmt.muninn.support.AbstractPostgresIT;
import se.tcmt.muninn.support.FakeIdp;
import se.tcmt.muninn.support.FakeOpenAi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Milestone 1 end to end, over HTTP only: setup wizard, SSO sign-in as a normal user, audio upload,
 * background processing against an OpenAI-compatible server, and the privacy rule that nobody else
 * (an admin included) can see the recording.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class E2EIT extends AbstractPostgresIT {

  private static final Pattern SETUP_TOKEN = Pattern.compile("SETUP TOKEN: (\\S+)");
  private static final String PROVIDER_KEY = "sk-e2e-secret-key-789";
  private static final String SUMMARY =
      """
      {"summary": "The team agreed to ship on Friday.", "actionItems": ["Email Bob", "Book QA"]}""";

  private static final FakeIdp IDP = FakeIdp.start();
  private static final FakeOpenAi AI = new FakeOpenAi();

  @Autowired MockMvc mvc;

  @Autowired JdbcTemplate jdbc;

  @Autowired ObjectMapper json;

  @Autowired SetupService setup;

  @Autowired JobWorker worker;

  @AfterAll
  static void stop() {
    IDP.close();
    AI.close();
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from ai_provider");
    jdbc.update("delete from app_user");
    jdbc.update("delete from app_setting");
    AI.reset();
    AI.chatContent(SUMMARY);
  }

  @Test
  void recordingBecomesTranscriptAndSummary(CapturedOutput out) throws Exception {
    String admin = completeSetupThroughTheApi(out);

    // A coworker signs in with SSO and uploads a recording.
    String ann = signIn("ann@acme.com");
    JsonNode me = body(mvc.perform(bearer(get("/api/auth/me"), ann)).andExpect(status().isOk()));
    assertThat(me.get("role").asText()).isEqualTo("USER");

    UUID id = UUID.randomUUID();
    byte[] audio = new byte[2048];
    new Random(42).nextBytes(audio);
    JsonNode uploaded =
        body(
            mvc.perform(
                    bearer(put("/api/recordings/" + id), ann)
                        .contentType("audio/mp4")
                        .header("X-Content-SHA256", sha256(audio))
                        .header("X-Recording-Duration-Ms", "65000")
                        .content(audio))
                .andExpect(status().isCreated()));
    assertThat(uploaded.get("uploadStatus").asText()).isEqualTo("UPLOADED");
    assertThat(uploaded.get("processingStatus").asText()).isEqualTo("PENDING");

    // The worker picks the job up: transcription, then the summary.
    for (int i = 0; i < 3 && !processingStatusOf(ann, id).equals("COMPLETED"); i++) {
      worker.runOnce();
    }

    JsonNode detail =
        body(mvc.perform(bearer(get("/api/recordings/" + id), ann)).andExpect(status().isOk()));
    assertThat(detail.get("processingStatus").asText()).isEqualTo("COMPLETED");
    assertThat(detail.at("/transcript/text").asText()).isEqualTo("hi all");
    assertThat(detail.at("/transcript/segments")).hasSize(2);
    assertThat(detail.at("/summary/text").asText()).isEqualTo("The team agreed to ship on Friday.");
    assertThat(detail.at("/summary/actionItems")).hasSize(2);
    assertThat(detail.at("/summary/actionItems/0").asText()).isEqualTo("Email Bob");

    // The audio is served back to its owner.
    mvc.perform(bearer(get("/api/recordings/" + id + "/audio"), ann)).andExpect(status().isOk());

    // The provider secret reaches the AI server but never any API response.
    assertThat(AI.requests()).anyMatch(r -> PROVIDER_KEY.equals(bearerKey(r.authorization())));
    String providers =
        mvc.perform(bearer(get("/api/providers"), admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(providers).doesNotContain(PROVIDER_KEY);

    // Privacy: another user, and the admin, get 404 for every access to Ann's recording.
    String bob = signIn("bob@acme.com");
    for (String other : List.of(bob, admin)) {
      mvc.perform(bearer(get("/api/recordings/" + id), other)).andExpect(status().isNotFound());
      mvc.perform(bearer(get("/api/recordings/" + id + "/audio"), other))
          .andExpect(status().isNotFound());
      mvc.perform(bearer(delete("/api/recordings/" + id), other)).andExpect(status().isNotFound());
      assertThat(
              body(mvc.perform(bearer(get("/api/recordings"), other)).andExpect(status().isOk())))
          .isEmpty();
    }

    // Uploading the same id with different bytes is refused and leaves the original intact.
    byte[] other = new byte[2048];
    new Random(7).nextBytes(other);
    mvc.perform(
            bearer(put("/api/recordings/" + id), ann)
                .contentType("audio/mp4")
                .header("X-Content-SHA256", sha256(other))
                .content(other))
        .andExpect(status().isConflict());
    assertThat(processingStatusOf(ann, id)).isEqualTo("COMPLETED");

    // The owner can delete it; it is gone afterwards.
    mvc.perform(bearer(delete("/api/recordings/" + id), ann)).andExpect(status().isNoContent());
    mvc.perform(bearer(get("/api/recordings/" + id), ann)).andExpect(status().isNotFound());
  }

  // ---- steps ----

  /** First-run wizard exactly as the web app drives it; returns the first admin's access token. */
  private String completeSetupThroughTheApi(CapturedOutput out) throws Exception {
    mvc.perform(get("/api/setup/status"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.complete")
                .value(false));

    setup.reissueToken();
    Matcher m = SETUP_TOKEN.matcher(out.getAll());
    String token = null;
    while (m.find()) {
      token = m.group(1);
    }
    assertThat(token).isNotNull();

    Map<String, Object> sso = new HashMap<>();
    sso.put("issuer", IDP.issuer());
    sso.put("clientId", FakeIdp.CLIENT_ID);
    sso.put("clientSecret", FakeIdp.CLIENT_SECRET);
    sso.put("allowedDomains", List.of("acme.com"));
    mvc.perform(json(post("/api/setup/sso"), sso).header("X-Setup-Token", token))
        .andExpect(status().isOk());

    JsonNode login = callback("admin@acme.com", token);
    assertThat(login.at("/user/role").asText()).isEqualTo("ADMIN");
    String admin = login.get("accessToken").asText();

    JsonNode provider =
        body(
            mvc.perform(
                    bearer(
                        json(
                            post("/api/setup/provider"),
                            Map.of(
                                "name",
                                "Fake AI",
                                "baseUrl",
                                AI.baseUrl(),
                                "apiKey",
                                PROVIDER_KEY)),
                        admin))
                .andExpect(status().isCreated()));
    String providerId = provider.get("id").asText();
    assertThat(provider.get("keySet").asBoolean()).isTrue();

    mvc.perform(bearer(post("/api/providers/" + providerId + "/test"), admin))
        .andExpect(status().isOk());
    Map<String, String> models = new HashMap<>();
    body(mvc.perform(bearer(post("/api/providers/" + providerId + "/discover"), admin)))
        .forEach(
            model -> models.put(model.get("modelIdentifier").asText(), model.get("id").asText()));
    mvc.perform(
            bearer(
                json(
                    post("/api/setup/models"),
                    Map.of(
                        "chatModelId", models.get("gpt-4o-mini"),
                        "transcriptionModelId", models.get("whisper-1"))),
                admin))
        .andExpect(status().isOk());
    mvc.perform(bearer(post("/api/setup/finish"), admin)).andExpect(status().isOk());

    mvc.perform(get("/api/setup/status"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.complete")
                .value(true));
    return admin;
  }

  private String signIn(String email) throws Exception {
    return callback(email, null).get("accessToken").asText();
  }

  private JsonNode callback(String email, String setupToken) throws Exception {
    String code = IDP.codeFor(IDP.sign(IDP.validClaims(email).build()));
    Map<String, Object> request = new HashMap<>();
    request.put("code", code);
    request.put("codeVerifier", "verifier");
    request.put("redirectUri", "http://localhost/login/callback");
    if (setupToken != null) {
      request.put("setupToken", setupToken);
    }
    return body(mvc.perform(json(post("/api/auth/callback"), request)).andExpect(status().isOk()));
  }

  private String processingStatusOf(String access, UUID id) throws Exception {
    return body(mvc.perform(bearer(get("/api/recordings/" + id), access)))
        .get("processingStatus")
        .asText();
  }

  // ---- helpers ----

  private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, Object body)
      throws Exception {
    return b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
  }

  private static MockHttpServletRequestBuilder bearer(
      MockHttpServletRequestBuilder b, String access) {
    return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + access);
  }

  private JsonNode body(org.springframework.test.web.servlet.ResultActions actions)
      throws Exception {
    String content = actions.andReturn().getResponse().getContentAsString();
    return content.isEmpty() ? null : json.readTree(content);
  }

  private static String bearerKey(String authorization) {
    return authorization != null && authorization.startsWith("Bearer ")
        ? authorization.substring("Bearer ".length())
        : null;
  }

  private static String sha256(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }
}
