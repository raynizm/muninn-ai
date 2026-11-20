package se.tcmt.muninn.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import se.tcmt.muninn.crypto.SecretCipher;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.repository.UserRepository;
import se.tcmt.muninn.service.SetupService;
import se.tcmt.muninn.service.TokenService;
import se.tcmt.muninn.support.AbstractPostgresIT;
import se.tcmt.muninn.support.FakeIdp;
import se.tcmt.muninn.support.FakeOpenAi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * First-run wizard (sso -> first-admin sign-in -> provider -> model -> finish), recovery, admin
 * settings.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SetupWizardControllerIT extends AbstractPostgresIT {

  private static final Pattern SETUP_TOKEN = Pattern.compile("SETUP TOKEN: (\\S+)");
  private static final Pattern RECOVERY_TOKEN = Pattern.compile("SETUP RECOVERY TOKEN: (\\S+)");
  private static final String HEADER = "X-Setup-Token";
  private static final String API_KEY = "sk-wizard-secret-456";

  private static final FakeIdp IDP = FakeIdp.start();
  private static final FakeOpenAi AI = new FakeOpenAi();

  @Autowired MockMvc mvc;

  @Autowired SetupService setup;

  @Autowired JdbcTemplate jdbc;

  @Autowired ObjectMapper json;

  @Autowired SecretCipher cipher;

  @Autowired UserRepository users;

  @Autowired TokenService tokens;

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
  }

  // ---- helpers ----

  private static String lastMatch(Pattern p, CapturedOutput out) {
    Matcher m = p.matcher(out.getAll());
    List<String> all = m.results().map(r -> r.group(1)).toList();
    assertThat(all).isNotEmpty();
    return all.get(all.size() - 1);
  }

  /** Fresh setup token while setup is incomplete. */
  private String setupToken(CapturedOutput out) {
    setup.reissueToken();
    return lastMatch(SETUP_TOKEN, out);
  }

  private Map<String, Object> ssoBody(String clientId) {
    Map<String, Object> body = new HashMap<>();
    body.put("issuer", IDP.issuer());
    body.put("clientId", clientId);
    body.put("clientSecret", FakeIdp.CLIENT_SECRET);
    body.put("allowedDomains", List.of(" ACME.com ", "Example.org"));
    return body;
  }

  private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder b, Object body)
      throws Exception {
    return b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
  }

  private static MockHttpServletRequestBuilder bearer(
      MockHttpServletRequestBuilder b, String access) {
    return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + access);
  }

  private JsonNode read(String body) throws Exception {
    return body.isEmpty() ? null : json.readTree(body);
  }

  private String access(String email, AppUser.Role role) {
    users.insertIfAbsent(email, email, role, "https://idp.test", "sub-" + email);
    return tokens.issue(users.findByEmail(email).orElseThrow()).access();
  }

  private String setting(String key) {
    return jdbc.query(
        "select value from app_setting where key = ?",
        rs -> rs.next() ? rs.getString(1) : null,
        key);
  }

  private void putSetting(String key, String value) {
    jdbc.update(
        "insert into app_setting(key, value) values (?, ?) "
            + "on conflict (key) do update set value = excluded.value",
        key,
        value);
  }

  private UUID createProvider(String access) throws Exception {
    String body =
        mvc.perform(
                bearer(
                    withJson(
                        post("/api/setup/provider"),
                        Map.of("name", "OpenAI", "baseUrl", AI.baseUrl(), "apiKey", API_KEY)),
                    access))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain(API_KEY);
    JsonNode view = read(body);
    assertThat(view.get("scope").asText()).isEqualTo("SYSTEM");
    return UUID.fromString(view.get("id").asText());
  }

  /** Discovers through the existing provider API; returns identifier -> model id. */
  private Map<String, String> discover(String access, UUID providerId) throws Exception {
    JsonNode models =
        read(
            mvc.perform(bearer(post("/api/providers/" + providerId + "/discover"), access))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    Map<String, String> ids = new HashMap<>();
    models.forEach(m -> ids.put(m.get("modelIdentifier").asText(), m.get("id").asText()));
    return ids;
  }

  private UUID userModel(UUID ownerId) {
    UUID providerId = UUID.randomUUID();
    jdbc.update(
        "insert into ai_provider(id, scope, owner_user_id, name, base_url) values (?, 'USER', ?, 'mine', ?)",
        providerId,
        ownerId,
        AI.baseUrl());
    UUID modelId = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model(id, provider_id, model_identifier, capabilities) "
            + "values (?, ?, 'own-chat', '[\"chat\"]'::jsonb)",
        modelId,
        providerId);
    return modelId;
  }

  /** Completes setup through the API and returns the first admin's access token. */
  private String completeSetup(CapturedOutput out) throws Exception {
    String token = setupToken(out);
    mvc.perform(withJson(post("/api/setup/sso"), ssoBody(FakeIdp.CLIENT_ID)).header(HEADER, token))
        .andExpect(status().isOk());
    String code = IDP.codeFor(IDP.sign(IDP.validClaims("admin@acme.com").build()));
    JsonNode login =
        read(
            mvc.perform(
                    withJson(
                        post("/api/auth/callback"),
                        Map.of(
                            "code",
                            code,
                            "codeVerifier",
                            "verifier",
                            "redirectUri",
                            "http://localhost/cb",
                            "setupToken",
                            token)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(login.at("/user/role").asText()).isEqualTo("ADMIN");
    String admin = login.get("accessToken").asText();

    UUID providerId = createProvider(admin);
    Map<String, String> models = discover(admin, providerId);
    mvc.perform(
            bearer(
                withJson(
                    post("/api/setup/models"),
                    Map.of(
                        "chatModelId", models.get("gpt-4o-mini"),
                        "transcriptionModelId", models.get("whisper-1"))),
                admin))
        .andExpect(status().isOk());
    mvc.perform(bearer(post("/api/setup/finish"), admin)).andExpect(status().isOk());
    return admin;
  }

  // ---- wizard ----

  @Test
  void wizardHappyPathCompletesSetup(CapturedOutput out) throws Exception {
    String admin = completeSetup(out);

    assertThat(setup.isComplete()).isTrue();
    mvc.perform(get("/api/setup/status"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.complete")
                .value(true));

    // Stored: domains normalised, secret encrypted (never raw), provider key encrypted.
    assertThat(setting("auth.allowedDomains")).isEqualTo("acme.com,example.org");
    String storedSecret = setting("oidc.clientSecret");
    assertThat(storedSecret).isNotEqualTo(FakeIdp.CLIENT_SECRET);
    assertThat(cipher.decrypt(storedSecret)).isEqualTo(FakeIdp.CLIENT_SECRET);
    assertThat(setting("settings.defaultChatModelId")).isNotNull();
    assertThat(setting("settings.defaultTranscriptionModelId")).isNotNull();

    // Settings view: oidc metadata but never the secret.
    String settings =
        mvc.perform(bearer(get("/api/settings"), admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode view = read(settings);
    assertThat(view.get("oidcConfigured").asBoolean()).isTrue();
    assertThat(view.get("issuer").asText()).isEqualTo(IDP.issuer());
    assertThat(view.get("clientId").asText()).isEqualTo(FakeIdp.CLIENT_ID);
    assertThat(view.get("allowedDomains")).hasSize(2);
    assertThat(view.get("defaultChatModelId").asText())
        .isEqualTo(setting("settings.defaultChatModelId"));
    assertThat(settings).doesNotContain(FakeIdp.CLIENT_SECRET).doesNotContainIgnoringCase("secret");

    // Setup cannot be re-run with any credential.
    String token = lastMatch(SETUP_TOKEN, out);
    mvc.perform(withJson(post("/api/setup/sso"), ssoBody("evil")).header(HEADER, token))
        .andExpect(status().isNotFound());
    mvc.perform(
            bearer(
                withJson(post("/api/setup/provider"), Map.of("name", "x", "baseUrl", AI.baseUrl())),
                admin))
        .andExpect(status().isNotFound());
    mvc.perform(bearer(withJson(post("/api/setup/models"), Map.of()), admin))
        .andExpect(status().isNotFound());
    mvc.perform(bearer(post("/api/setup/finish"), admin)).andExpect(status().isNotFound());
    mvc.perform(withJson(post("/api/setup/verify"), Map.of("token", token)))
        .andExpect(status().isNotFound());
    // Malformed body still 404: nothing about the endpoint leaks after completion.
    mvc.perform(
            bearer(
                post("/api/setup/models").contentType(MediaType.APPLICATION_JSON).content("{nope"),
                admin))
        .andExpect(status().isNotFound());
    assertThat(setting("oidc.clientId")).isEqualTo(FakeIdp.CLIENT_ID);
  }

  @Test
  void ssoRejectedWithoutToken(CapturedOutput out) throws Exception {
    String token = setupToken(out);
    String admin = access("admin@acme.com", AppUser.Role.ADMIN);

    mvc.perform(withJson(post("/api/setup/sso"), ssoBody("c")))
        .andExpect(status().isUnauthorized());
    mvc.perform(withJson(post("/api/setup/sso"), ssoBody("c")).header(HEADER, token + "x"))
        .andExpect(status().isUnauthorized());
    // An admin bearer token does not replace the setup token here.
    mvc.perform(bearer(withJson(post("/api/setup/sso"), ssoBody("c")), admin))
        .andExpect(status().isUnauthorized());
    assertThat(setting("oidc.clientId")).isNull();

    // Validation (with a valid token).
    Map<String, Object> plainHttp = ssoBody("c");
    plainHttp.put("issuer", "http://idp.example.com/tenant");
    Map<String, Object> notUrl = ssoBody("c");
    notUrl.put("issuer", "not a url");
    Map<String, Object> noDomains = ssoBody("c");
    noDomains.put("allowedDomains", List.of(" "));
    Map<String, Object> noSecret = ssoBody("c");
    noSecret.remove("clientSecret");
    Map<String, Object> noClient = ssoBody("c");
    noClient.put("clientId", " ");
    for (Map<String, Object> bad : List.of(plainHttp, notUrl, noDomains, noSecret, noClient)) {
      mvc.perform(withJson(post("/api/setup/sso"), bad).header(HEADER, token))
          .andExpect(status().isBadRequest());
    }
    assertThat(setting("oidc.clientId")).isNull();

    Map<String, Object> ok = ssoBody("c");
    ok.put("issuer", "https://login.example.com/tenant/v2.0");
    ok.put("tenantId", " tid-1 ");
    String body =
        mvc.perform(withJson(post("/api/setup/sso"), ok).header(HEADER, token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain(FakeIdp.CLIENT_SECRET);
    assertThat(setting("oidc.issuer")).isEqualTo("https://login.example.com/tenant/v2.0");
    assertThat(setting("auth.tenantId")).isEqualTo("tid-1");
  }

  @Test
  void setupProviderModelsFinishNeedFirstAdminBearer(CapturedOutput out) throws Exception {
    String token = setupToken(out);
    String user = access("ann@acme.com", AppUser.Role.USER);
    Map<String, String> provider = Map.of("name", "OpenAI", "baseUrl", AI.baseUrl());

    for (String path : List.of("/api/setup/provider", "/api/setup/models", "/api/setup/finish")) {
      mvc.perform(withJson(post(path), provider)).andExpect(status().isUnauthorized());
      mvc.perform(withJson(post(path), provider).header(HEADER, token))
          .andExpect(status().isUnauthorized());
      mvc.perform(bearer(withJson(post(path), provider), "not.a.jwt"))
          .andExpect(status().isUnauthorized());
      mvc.perform(bearer(withJson(post(path), provider), user)).andExpect(status().isForbidden());
    }
    assertThat(jdbc.queryForObject("select count(*) from ai_provider", Integer.class)).isZero();
    assertThat(setup.isComplete()).isFalse();
  }

  @Test
  void modelsRejectUnknownAndNonSystemIds(CapturedOutput out) throws Exception {
    setupToken(out);
    String admin = access("admin@acme.com", AppUser.Role.ADMIN);
    UUID providerId = createProvider(admin);
    Map<String, String> models = discover(admin, providerId);
    UUID foreign = userModel(users.findByEmail("admin@acme.com").orElseThrow().id());

    for (Map<String, Object> bad :
        List.<Map<String, Object>>of(
            Map.of(
                "chatModelId",
                UUID.randomUUID().toString(),
                "transcriptionModelId",
                models.get("whisper-1")),
            Map.of(
                "chatModelId", foreign.toString(), "transcriptionModelId", models.get("whisper-1")),
            Map.of("chatModelId", models.get("gpt-4o-mini")),
            Map.of("chatModelId", "nope", "transcriptionModelId", models.get("whisper-1")))) {
      mvc.perform(bearer(withJson(post("/api/setup/models"), bad), admin))
          .andExpect(status().isBadRequest());
    }
    assertThat(setting("settings.defaultChatModelId")).isNull();

    mvc.perform(
            bearer(
                withJson(
                    post("/api/setup/models"),
                    Map.of(
                        "chatModelId", models.get("gpt-4o-mini"),
                        "transcriptionModelId", models.get("whisper-1"))),
                admin))
        .andExpect(status().isOk());
    assertThat(setting("settings.defaultChatModelId")).isEqualTo(models.get("gpt-4o-mini"));
    assertThat(setting("settings.defaultTranscriptionModelId")).isEqualTo(models.get("whisper-1"));
  }

  @Test
  void finishRequiresDomainsAndProvider(CapturedOutput out) throws Exception {
    String token = setupToken(out);
    mvc.perform(withJson(post("/api/setup/sso"), ssoBody(FakeIdp.CLIENT_ID)).header(HEADER, token))
        .andExpect(status().isOk());
    String admin = access("admin@acme.com", AppUser.Role.ADMIN);

    // No provider, no model.
    mvc.perform(bearer(post("/api/setup/finish"), admin)).andExpect(status().isBadRequest());

    UUID providerId = createProvider(admin);
    mvc.perform(bearer(post("/api/setup/finish"), admin)).andExpect(status().isBadRequest());

    Map<String, String> models = discover(admin, providerId);
    putSetting("settings.defaultChatModelId", models.get("gpt-4o-mini"));
    // Chat model only.
    mvc.perform(bearer(post("/api/setup/finish"), admin)).andExpect(status().isBadRequest());
    putSetting("settings.defaultTranscriptionModelId", models.get("whisper-1"));

    // Empty domain list.
    putSetting("auth.allowedDomains", " , ");
    mvc.perform(bearer(post("/api/setup/finish"), admin)).andExpect(status().isBadRequest());
    jdbc.update("delete from app_setting where key = 'auth.allowedDomains'");
    mvc.perform(bearer(post("/api/setup/finish"), admin)).andExpect(status().isBadRequest());
    assertThat(setup.isComplete()).isFalse();

    putSetting("auth.allowedDomains", "acme.com");
    mvc.perform(bearer(post("/api/setup/finish"), admin)).andExpect(status().isOk());
    assertThat(setup.isComplete()).isTrue();
  }

  // ---- recovery ----

  @Test
  void recoveryTokenReplacesSsoOnceAndNothingElse(CapturedOutput out) throws Exception {
    String admin = completeSetup(out);
    String oldSetupToken = lastMatch(SETUP_TOKEN, out);

    // PUT is not part of the normal flow; it needs a recovery token, which only exists after
    // completion.
    setup.reissueToken();
    String recovery = lastMatch(RECOVERY_TOKEN, out);
    assertThat(setting("setup.recovery")).isNotNull().isNotEqualTo(recovery);
    assertThat(setting("setup.token_hash")).isNull();
    assertThat(setup.verifyToken(recovery)).isFalse();

    Map<String, Object> replacement = ssoBody("muninn-recovered-client");
    replacement.put("allowedDomains", List.of("acme.com"));

    // Wrong or missing token => 401; the old setup token is not a recovery token.
    mvc.perform(withJson(put("/api/setup/sso"), replacement)).andExpect(status().isUnauthorized());
    mvc.perform(withJson(put("/api/setup/sso"), replacement).header(HEADER, recovery + "x"))
        .andExpect(status().isUnauthorized());
    mvc.perform(withJson(put("/api/setup/sso"), replacement).header(HEADER, oldSetupToken))
        .andExpect(status().isUnauthorized());
    // An admin bearer is not enough either.
    mvc.perform(bearer(withJson(put("/api/setup/sso"), replacement), admin))
        .andExpect(status().isUnauthorized());

    // Recovery token opens nothing else.
    mvc.perform(withJson(post("/api/setup/sso"), replacement).header(HEADER, recovery))
        .andExpect(status().isNotFound());
    mvc.perform(
            withJson(post("/api/setup/provider"), Map.of("name", "x", "baseUrl", AI.baseUrl()))
                .header(HEADER, recovery))
        .andExpect(status().isNotFound());
    mvc.perform(withJson(post("/api/setup/models"), Map.of()).header(HEADER, recovery))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/setup/finish").header(HEADER, recovery))
        .andExpect(status().isNotFound());
    mvc.perform(withJson(post("/api/setup/verify"), Map.of("token", recovery)))
        .andExpect(status().isNotFound());

    // Invalid body does not burn the token.
    Map<String, Object> bad = ssoBody("x");
    bad.put("allowedDomains", List.of());
    mvc.perform(withJson(put("/api/setup/sso"), bad).header(HEADER, recovery))
        .andExpect(status().isBadRequest());

    String body =
        mvc.perform(withJson(put("/api/setup/sso"), replacement).header(HEADER, recovery))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain(FakeIdp.CLIENT_SECRET);
    assertThat(setting("oidc.clientId")).isEqualTo("muninn-recovered-client");
    assertThat(setting("auth.allowedDomains")).isEqualTo("acme.com");
    assertThat(setting("setup.recovery")).isNull();
    assertThat(setup.isComplete()).isTrue();

    // Single use.
    mvc.perform(withJson(put("/api/setup/sso"), ssoBody("again")).header(HEADER, recovery))
        .andExpect(status().isUnauthorized());
    assertThat(setting("oidc.clientId")).isEqualTo("muninn-recovered-client");
  }

  @Test
  void recoveryEndpointIsNotFoundBeforeCompletion(CapturedOutput out) throws Exception {
    String token = setupToken(out);
    assertThat(setting("setup.token_hash")).isNotNull();
    assertThat(setting("setup.recovery")).isNull();
    mvc.perform(withJson(put("/api/setup/sso"), ssoBody("c")).header(HEADER, token))
        .andExpect(status().isNotFound());
    assertThat(setting("oidc.clientId")).isNull();
  }

  // ---- admin settings ----

  @Test
  void nonAdminCannotPutSettings(CapturedOutput out) throws Exception {
    String admin = completeSetup(out);
    String user = access("ann@acme.com", AppUser.Role.USER);
    Map<String, Object> update = Map.of("userOverrideEnabled", true);

    mvc.perform(withJson(put("/api/settings"), update)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
    mvc.perform(bearer(withJson(put("/api/settings"), update), user))
        .andExpect(status().isForbidden());
    mvc.perform(bearer(get("/api/settings"), user)).andExpect(status().isForbidden());
    assertThat(setting("settings.userOverrideEnabled")).isNull();

    mvc.perform(bearer(withJson(put("/api/settings"), update), admin)).andExpect(status().isOk());
    assertThat(setting("settings.userOverrideEnabled")).isEqualTo("true");
  }

  @Test
  void adminPutSettingsValidates(CapturedOutput out) throws Exception {
    String admin = completeSetup(out);
    String chat = setting("settings.defaultChatModelId");
    UUID foreign = userModel(users.findByEmail("admin@acme.com").orElseThrow().id());

    for (Map<String, Object> bad :
        List.<Map<String, Object>>of(
            Map.of("allowedDomains", List.of()),
            Map.of("allowedDomains", List.of("  ")),
            Map.of("allowedDomains", List.of("not a domain")),
            Map.of("defaultChatModelId", UUID.randomUUID().toString()),
            Map.of("defaultChatModelId", foreign.toString()),
            Map.of("userOverrideEnabled", true, "defaultTranscriptionModelId", chat))) {
      mvc.perform(bearer(withJson(put("/api/settings"), bad), admin))
          .andExpect(status().isBadRequest());
    }
    // Nothing partially applied.
    assertThat(setting("settings.userOverrideEnabled")).isNull();
    assertThat(setting("auth.allowedDomains")).isEqualTo("acme.com,example.org");

    // A model of a disabled system provider is rejected.
    jdbc.update("update ai_provider set enabled = false where scope = 'GLOBAL'");
    mvc.perform(bearer(withJson(put("/api/settings"), Map.of("defaultChatModelId", chat)), admin))
        .andExpect(status().isBadRequest());
    jdbc.update("update ai_provider set enabled = true where scope = 'GLOBAL'");

    JsonNode view =
        read(
            mvc.perform(
                    bearer(
                        withJson(
                            put("/api/settings"),
                            Map.of(
                                "allowedDomains",
                                List.of(" Corp.EXAMPLE ", "acme.com"),
                                "userOverrideEnabled",
                                true,
                                "defaultChatModelId",
                                chat)),
                        admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(view.get("userOverrideEnabled").asBoolean()).isTrue();
    assertThat(view.get("allowedDomains").toString()).isEqualTo("[\"acme.com\",\"corp.example\"]");
    assertThat(view.get("defaultChatModelId").asText()).isEqualTo(chat);
    assertThat(view.toString()).doesNotContainIgnoringCase("secret");
    assertThat(setting("auth.allowedDomains")).isEqualTo("acme.com,corp.example");
  }
}
