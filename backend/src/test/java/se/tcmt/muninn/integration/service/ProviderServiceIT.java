package se.tcmt.muninn.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;
import se.tcmt.muninn.dto.ProviderRequest;
import se.tcmt.muninn.model.AiProvider;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.model.EffectiveProvider;
import se.tcmt.muninn.model.TaskType;
import se.tcmt.muninn.repository.UserRepository;
import se.tcmt.muninn.service.ProviderService;
import se.tcmt.muninn.service.TokenService;
import se.tcmt.muninn.support.AbstractPostgresIT;
import se.tcmt.muninn.support.FakeOpenAi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ProviderServiceIT extends AbstractPostgresIT {

    private static final FakeOpenAi AI = new FakeOpenAi();
    private static final String KEY = "sk-super-secret-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    ProviderService providers;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    AppUser admin;
    AppUser ann;
    AppUser bob;

    @AfterAll
    static void stop() {
        AI.close();
    }

    @BeforeEach
    void reset() {
        jdbc.update("delete from ai_provider");
        jdbc.update("delete from app_user");
        jdbc.update("delete from app_setting");
        AI.requests().clear();
        AI.requireKey(null);
        admin = user("admin@acme.com", AppUser.Role.ADMIN);
        ann = user("ann@acme.com", AppUser.Role.USER);
        bob = user("bob@acme.com", AppUser.Role.USER);
    }

    private AppUser user(String email, AppUser.Role role) {
        users.insertIfAbsent(email, email, role, "https://idp.test", "sub-" + email);
        return users.findByEmail(email).orElseThrow();
    }

    private ProviderRequest req(String name) {
        return new ProviderRequest(name, AI.baseUrl(), KEY, true);
    }

    private UUID addModel(UUID providerId, String identifier, String capabilitiesJson, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into ai_model(id, provider_id, model_identifier, capabilities, enabled) "
                        + "values (?, ?, ?, ?::jsonb, ?)",
                id,
                providerId,
                identifier,
                capabilitiesJson,
                enabled);
        return id;
    }

    private void setting(String key, String value) {
        jdbc.update(
                "insert into app_setting(key, value) values (?, ?) on conflict (key) do update set value = ?",
                key,
                value,
                value);
    }

    private ResultActions call(AppUser as, String method, String path, Object body) throws Exception {
        var b =
                switch (method) {
                    case "POST" -> post(path);
                    case "PUT" -> put(path);
                    case "DELETE" -> delete(path);
                    default -> get(path);
                };
        b.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(as).access());
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(b);
    }

    private JsonNode body(ResultActions r, int expected) throws Exception {
        String s = r.andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return s.isEmpty() ? null : json.readTree(s);
    }

    /** System provider with a default chat model and a default transcription model. */
    private AiProvider systemProviderWithDefaults() {
        AiProvider sys = providers.create(null, req("system"));
        setting(
                "settings.defaultChatModelId",
                addModel(sys.id(), "sys-chat", "[\"chat\"]", true).toString());
        setting(
                "settings.defaultTranscriptionModelId",
                addModel(sys.id(), "sys-whisper", "[\"transcription\"]", true).toString());
        return sys;
    }

    @Test
    void resolvePrefersUserOverrideWhenAllowed() {
        AiProvider sys = systemProviderWithDefaults();
        AiProvider own = providers.create(ann.id(), req("mine"));
        addModel(own.id(), "own-chat", "[\"chat\"]", true);
        setting("settings.userOverrideEnabled", "true");

        EffectiveProvider chat = providers.resolve(ann.id(), TaskType.CHAT).orElseThrow();
        assertThat(chat.providerId()).isEqualTo(own.id());
        assertThat(chat.modelIdentifier()).isEqualTo("own-chat");
        assertThat(chat.connection().apiKey()).isEqualTo(KEY);
        // The own provider has no transcription model: that task falls back to the system provider.
        EffectiveProvider stt =
                providers.resolve(ann.id(), TaskType.TRANSCRIPTION).orElseThrow();
        assertThat(stt.providerId()).isEqualTo(sys.id());
        assertThat(stt.modelIdentifier()).isEqualTo("sys-whisper");
        // Another user without own provider gets the system one.
        assertThat(providers.resolve(bob.id(), TaskType.CHAT).orElseThrow().providerId())
                .isEqualTo(sys.id());
    }

    @Test
    void resolveIgnoresUserProviderWhenOverrideOff() {
        AiProvider sys = systemProviderWithDefaults();
        AiProvider own = providers.create(ann.id(), req("mine"));
        addModel(own.id(), "own-chat", "[\"chat\"]", true);

        EffectiveProvider chat = providers.resolve(ann.id(), TaskType.CHAT).orElseThrow();
        assertThat(chat.providerId()).isEqualTo(sys.id());
        setting("settings.userOverrideEnabled", "false");
        assertThat(providers.resolve(ann.id(), TaskType.CHAT).orElseThrow().providerId())
                .isEqualTo(sys.id());
    }

    @Test
    void resolveIsCapabilityAwareAndEmptyWhenNothingConfigured() {
        assertThat(providers.resolve(ann.id(), TaskType.CHAT)).isEqualTo(Optional.empty());
        AiProvider sys = providers.create(null, req("system"));
        // Default chat model lacks the chat capability: does not resolve.
        setting(
                "settings.defaultChatModelId",
                addModel(sys.id(), "emb", "[\"embeddings\"]", true).toString());
        assertThat(providers.resolve(ann.id(), TaskType.CHAT)).isEmpty();
        // A disabled own model is skipped even with override on.
        AiProvider own = providers.create(ann.id(), req("mine"));
        addModel(own.id(), "off", "[\"chat\"]", false);
        setting("settings.userOverrideEnabled", "true");
        assertThat(providers.resolve(ann.id(), TaskType.CHAT)).isEmpty();
    }

    @Test
    void userCannotCreateOrReadSystemProviderViaApi() throws Exception {
        Map<String, Object> sysBody = Map.of("name", "sys", "baseUrl", AI.baseUrl(), "apiKey", KEY, "scope", "SYSTEM");
        call(ann, "POST", "/api/providers", sysBody).andExpect(status().isForbidden());

        JsonNode created = body(call(admin, "POST", "/api/providers", sysBody), 201);
        String id = created.get("id").asText();
        assertThat(created.get("scope").asText()).isEqualTo("SYSTEM");

        call(ann, "GET", "/api/providers/" + id, null).andExpect(status().isNotFound());
        call(ann, "PUT", "/api/providers/" + id, Map.of("name", "x", "baseUrl", AI.baseUrl()))
                .andExpect(status().isNotFound());
        call(ann, "DELETE", "/api/providers/" + id, null).andExpect(status().isNotFound());
        call(ann, "POST", "/api/providers/" + id + "/test", null).andExpect(status().isNotFound());
        call(ann, "POST", "/api/providers/" + id + "/discover", null).andExpect(status().isNotFound());
        call(ann, "GET", "/api/providers/" + id + "/model", null).andExpect(status().isNotFound());
        assertThat(body(call(ann, "GET", "/api/providers", null), 200)).isEmpty();
        assertThat(body(call(admin, "GET", "/api/providers", null), 200)).hasSize(1);
        call(admin, "GET", "/api/providers/" + id, null).andExpect(status().isOk());
    }

    @Test
    void userCannotTouchAnotherUsersProvider() throws Exception {
        String id = body(
                        call(
                                ann,
                                "POST",
                                "/api/providers",
                                Map.of("name", "mine", "baseUrl", AI.baseUrl(), "apiKey", KEY)),
                        201)
                .get("id")
                .asText();
        assertThat(body(call(ann, "GET", "/api/providers/" + id, null), 200)
                        .get("scope")
                        .asText())
                .isEqualTo("USER");
        call(bob, "GET", "/api/providers/" + id, null).andExpect(status().isNotFound());
        call(bob, "POST", "/api/providers/" + id + "/test", null).andExpect(status().isNotFound());
        call(bob, "POST", "/api/providers/" + id + "/discover", null).andExpect(status().isNotFound());
        call(bob, "GET", "/api/providers/" + id + "/model", null).andExpect(status().isNotFound());
        call(bob, "DELETE", "/api/providers/" + id, null).andExpect(status().isNotFound());
        assertThat(AI.requests()).isEmpty();
        // Admin has no access to a USER-scope provider either.
        call(admin, "GET", "/api/providers/" + id, null).andExpect(status().isNotFound());
    }

    @Test
    void responseNeverContainsKey() throws Exception {
        ResultActions created =
                call(ann, "POST", "/api/providers", Map.of("name", "mine", "baseUrl", AI.baseUrl(), "apiKey", KEY));
        String createdBody = created.andReturn().getResponse().getContentAsString();
        JsonNode view = json.readTree(createdBody);
        String id = view.get("id").asText();
        assertThat(view.get("keySet").asBoolean()).isTrue();
        assertThat(view.get("isLocal").asBoolean()).isTrue();

        String all = createdBody
                + call(ann, "GET", "/api/providers", null)
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
                + call(ann, "GET", "/api/providers/" + id, null)
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
                + call(ann, "PUT", "/api/providers/" + id, Map.of("name", "renamed", "baseUrl", AI.baseUrl()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
                + call(ann, "POST", "/api/providers/" + id + "/test", null)
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(all)
                .doesNotContain(KEY)
                .doesNotContainIgnoringCase("encrypted")
                .doesNotContain("apiKey");

        String stored = jdbc.queryForObject(
                "select encrypted_key from ai_provider where id = ?", String.class, UUID.fromString(id));
        assertThat(stored).isNotBlank().doesNotContain(KEY);
        // Omitting apiKey on update keeps the stored key; the key still decrypts for resolve.
        assertThat(body(call(ann, "GET", "/api/providers/" + id, null), 200)
                        .get("keySet")
                        .asBoolean())
                .isTrue();
        // An empty apiKey clears it.
        assertThat(body(
                                call(
                                        ann,
                                        "PUT",
                                        "/api/providers/" + id,
                                        Map.of("name", "n", "baseUrl", AI.baseUrl(), "apiKey", "")),
                                200)
                        .get("keySet")
                        .asBoolean())
                .isFalse();
    }

    @Test
    void testReportsMessageWithoutSecret() throws Exception {
        String id = body(
                        call(
                                ann,
                                "POST",
                                "/api/providers",
                                Map.of("name", "mine", "baseUrl", AI.baseUrl(), "apiKey", KEY)),
                        201)
                .get("id")
                .asText();
        JsonNode ok = body(call(ann, "POST", "/api/providers/" + id + "/test", null), 200);
        assertThat(ok.get("ok").asBoolean()).isTrue();

        AI.requireKey("a-different-key");
        JsonNode bad = body(call(ann, "POST", "/api/providers/" + id + "/test", null), 200);
        assertThat(bad.get("ok").asBoolean()).isFalse();
        assertThat(bad.get("message").asText()).isNotBlank().doesNotContain(KEY).doesNotContain("Bearer");

        // The stored result is the same short message with a timestamp, and never the key.
        JsonNode view = body(call(ann, "GET", "/api/providers/" + id, null), 200);
        assertThat(view.at("/lastTest/ok").asBoolean()).isFalse();
        assertThat(view.at("/lastTest/message").asText()).doesNotContain(KEY);
        assertThat(view.at("/lastTest/at").asText()).isNotBlank();
    }

    @Test
    void discoverStoresModelsAndAllowsEditingCapabilities() throws Exception {
        String id = body(
                        call(
                                ann,
                                "POST",
                                "/api/providers",
                                Map.of("name", "mine", "baseUrl", AI.baseUrl(), "apiKey", KEY)),
                        201)
                .get("id")
                .asText();
        JsonNode models = body(call(ann, "POST", "/api/providers/" + id + "/discover", null), 200);
        assertThat(models).hasSize(3);
        JsonNode whisper = null;
        for (JsonNode m : models) {
            if (m.get("modelIdentifier").asText().equals("whisper-1")) {
                whisper = m;
            }
        }
        assertThat(whisper.get("capabilities").get(0).asText()).isEqualTo("transcription");

        // Re-discovery does not duplicate or overwrite hand edits.
        String modelId = whisper.get("id").asText();
        body(
                call(
                        ann,
                        "PUT",
                        "/api/model/" + modelId,
                        Map.of("capabilities", java.util.List.of("transcription", "json"), "enabled", false)),
                200);
        JsonNode again = body(call(ann, "POST", "/api/providers/" + id + "/discover", null), 200);
        assertThat(again).hasSize(3);
        JsonNode listed = body(call(ann, "GET", "/api/providers/" + id + "/model", null), 200);
        for (JsonNode m : listed) {
            if (m.get("id").asText().equals(modelId)) {
                assertThat(m.get("capabilities")).hasSize(2);
                assertThat(m.get("enabled").asBoolean()).isFalse();
            }
        }
        call(bob, "PUT", "/api/model/" + modelId, Map.of("capabilities", java.util.List.of("chat"), "enabled", true))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsBadBaseUrls() throws Exception {
        for (String url : new String[] {"ftp://host/x", "http://user:pw@host/x", "not a url", "http://"}) {
            call(ann, "POST", "/api/providers", Map.of("name", "n", "baseUrl", url))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void isLocalFollowsHost() {
        assertThat(ProviderService.isLocal("http://127.0.0.1:11434")).isTrue();
        assertThat(ProviderService.isLocal("http://localhost:1234")).isTrue();
        assertThat(ProviderService.isLocal("http://192.168.1.5")).isTrue();
        assertThat(ProviderService.isLocal("http://10.0.0.2")).isTrue();
        assertThat(ProviderService.isLocal("http://169.254.1.1")).isTrue();
        assertThat(ProviderService.isLocal("http://[::1]:8080")).isTrue();
        assertThat(ProviderService.isLocal("http://nas.local")).isTrue();
        assertThat(ProviderService.isLocal("http://gpu.internal:8000")).isTrue();
        assertThat(ProviderService.isLocal("http://ollama:11434")).isTrue();
        assertThat(ProviderService.isLocal("https://api.openai.com")).isFalse();
        assertThat(ProviderService.isLocal("http://8.8.8.8")).isFalse();
    }
}
