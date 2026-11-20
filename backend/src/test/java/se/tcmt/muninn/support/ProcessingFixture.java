package se.tcmt.muninn.support;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import se.tcmt.muninn.dto.ProviderRequest;
import se.tcmt.muninn.model.AiProvider;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.repository.UserRepository;
import se.tcmt.muninn.service.JobWorker;
import se.tcmt.muninn.service.ProcessingPipeline;
import se.tcmt.muninn.service.ProviderService;
import se.tcmt.muninn.service.RecordingService;

/**
 * Users, providers, uploaded recordings and job-row access shared by the processing integration
 * tests.
 */
public abstract class ProcessingFixture extends AbstractPostgresIT {

  protected static final FakeOpenAi AI = new FakeOpenAi();
  protected static final String KEY = "sk-provider-key-0000";
  protected static final String TRANSCRIBE = "/v1/audio/transcriptions";
  protected static final String CHAT = "/v1/chat/completions";
  protected static final String VALID_SUMMARY =
      """
            {"summary": "Talked about the launch.", "actionItems": ["Email Bob", "Book the room"]}""";

  @Autowired protected JdbcTemplate jdbc;

  @Autowired protected UserRepository users;

  @Autowired protected ProviderService providers;

  @Autowired protected RecordingService recordings;

  @Autowired protected JobWorker worker;

  @Autowired protected ProcessingPipeline pipeline;

  protected AppUser ann;
  protected AppUser bob;

  @AfterAll
  public static void stopAi() {
    AI.reset();
  }

  @BeforeEach
  public void resetData() {
    // Cascades to recordings, jobs, transcripts and summaries, so no other test's jobs can be
    // claimed here.
    jdbc.update("delete from ai_provider");
    jdbc.update("delete from app_user");
    jdbc.update("delete from app_setting");
    AI.reset();
    AI.chatContent(VALID_SUMMARY);
    ann = user("ann@acme.com");
    bob = user("bob@acme.com");
  }

  public AppUser user(String email) {
    users.insertIfAbsent(email, email, AppUser.Role.USER, "https://idp.test", "sub-" + email);
    return users.findByEmail(email).orElseThrow();
  }

  /** System provider on the fake server with default chat and transcription model. */
  public AiProvider systemProvider(String chatCapabilities) {
    AiProvider sys = providers.create(null, new ProviderRequest("system", AI.baseUrl(), KEY, true));
    setting(
        "settings.defaultChatModelId", model(sys.id(), "gpt-4o-mini", chatCapabilities).toString());
    setting(
        "settings.defaultTranscriptionModelId",
        model(sys.id(), "whisper-1", "[\"transcription\"]").toString());
    return sys;
  }

  public AiProvider systemProvider() {
    return systemProvider("[\"chat\", \"json\"]");
  }

  public UUID model(UUID providerId, String identifier, String capabilitiesJson) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model(id, provider_id, model_identifier, capabilities) values (?, ?, ?, ?::jsonb)",
        id,
        providerId,
        identifier,
        capabilitiesJson);
    return id;
  }

  public void setting(String key, String value) {
    jdbc.update(
        "insert into app_setting(key, value) values (?, ?) on conflict (key) do update set value = ?",
        key,
        value,
        value);
  }

  /** Uploads a small recording for the user, which enqueues its processing job. */
  public UUID upload(AppUser owner) throws Exception {
    UUID id = UUID.randomUUID();
    byte[] data = new byte[256];
    new java.util.Random(id.getMostSignificantBits()).nextBytes(data);
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    recordings.upload(owner.id(), id, new ByteArrayInputStream(data), sha, "audio/mp4", null, null);
    return id;
  }

  public Map<String, Object> job(UUID recordingId) {
    return jdbc.queryForMap(
        "select j.*, extract(epoch from (j.run_after - now())) as due_in_seconds "
            + "from processing_job j where recording_id = ?",
        recordingId);
  }

  public String processingStatus(UUID recordingId) {
    return jdbc.queryForObject(
        "select processing_status from recording where id = ?", String.class, recordingId);
  }

  public void makeDue(UUID recordingId) {
    jdbc.update(
        "update processing_job set run_after = now() - interval '1 second' where recording_id = ?",
        recordingId);
  }

  public int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }
}
