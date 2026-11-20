package com.muninn.processing;

import com.muninn.AbstractPostgresIT;
import com.muninn.auth.AppUser;
import com.muninn.auth.UserRepository;
import com.muninn.provider.AiProvider;
import com.muninn.provider.FakeOpenAi;
import com.muninn.provider.ProviderRequest;
import com.muninn.provider.ProviderService;
import com.muninn.recording.RecordingService;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Users, providers, uploaded recordings and job-row access shared by the processing integration tests. */
abstract class ProcessingFixture extends AbstractPostgresIT {

    static final FakeOpenAi AI = new FakeOpenAi();
    static final String KEY = "sk-provider-key-0000";
    static final String TRANSCRIBE = "/v1/audio/transcriptions";
    static final String CHAT = "/v1/chat/completions";
    static final String VALID_SUMMARY =
            """
            {"summary": "Talked about the launch.", "actionItems": ["Email Bob", "Book the room"]}""";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    ProviderService providers;

    @Autowired
    RecordingService recordings;

    @Autowired
    JobWorker worker;

    @Autowired
    ProcessingPipeline pipeline;

    AppUser ann;
    AppUser bob;

    @AfterAll
    static void stopAi() {
        AI.reset();
    }

    @BeforeEach
    void resetData() {
        // Cascades to recordings, jobs, transcripts and summaries, so no other test's jobs can be claimed here.
        jdbc.update("delete from ai_provider");
        jdbc.update("delete from app_user");
        jdbc.update("delete from app_setting");
        AI.reset();
        AI.chatContent(VALID_SUMMARY);
        ann = user("ann@acme.com");
        bob = user("bob@acme.com");
    }

    AppUser user(String email) {
        users.insertIfAbsent(email, email, AppUser.Role.USER, "https://idp.test", "sub-" + email);
        return users.findByEmail(email).orElseThrow();
    }

    /** System provider on the fake server with default chat and transcription models. */
    AiProvider systemProvider(String chatCapabilities) {
        AiProvider sys = providers.create(null, new ProviderRequest("system", AI.baseUrl(), KEY, true));
        setting(
                "settings.defaultChatModelId",
                model(sys.id(), "gpt-4o-mini", chatCapabilities).toString());
        setting(
                "settings.defaultTranscriptionModelId",
                model(sys.id(), "whisper-1", "[\"transcription\"]").toString());
        return sys;
    }

    AiProvider systemProvider() {
        return systemProvider("[\"chat\", \"json\"]");
    }

    UUID model(UUID providerId, String identifier, String capabilitiesJson) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into ai_model(id, provider_id, model_identifier, capabilities) values (?, ?, ?, ?::jsonb)",
                id,
                providerId,
                identifier,
                capabilitiesJson);
        return id;
    }

    void setting(String key, String value) {
        jdbc.update(
                "insert into app_setting(key, value) values (?, ?) on conflict (key) do update set value = ?",
                key,
                value,
                value);
    }

    /** Uploads a small recording for the user, which enqueues its processing job. */
    UUID upload(AppUser owner) throws Exception {
        UUID id = UUID.randomUUID();
        byte[] data = new byte[256];
        new java.util.Random(id.getMostSignificantBits()).nextBytes(data);
        String sha =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        recordings.upload(owner.id(), id, new ByteArrayInputStream(data), sha, "audio/mp4", null, null);
        return id;
    }

    Map<String, Object> job(UUID recordingId) {
        return jdbc.queryForMap(
                "select j.*, extract(epoch from (j.run_after - now())) as due_in_seconds "
                        + "from processing_job j where recording_id = ?",
                recordingId);
    }

    String processingStatus(UUID recordingId) {
        return jdbc.queryForObject("select processing_status from recording where id = ?", String.class, recordingId);
    }

    void makeDue(UUID recordingId) {
        jdbc.update(
                "update processing_job set run_after = now() - interval '1 second' where recording_id = ?",
                recordingId);
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
