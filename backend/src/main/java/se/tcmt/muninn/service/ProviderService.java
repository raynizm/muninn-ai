package se.tcmt.muninn.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.crypto.SecretCipher;
import se.tcmt.muninn.dto.ProviderRequest;
import se.tcmt.muninn.model.AiModel;
import se.tcmt.muninn.model.AiProvider;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.model.EffectiveProvider;
import se.tcmt.muninn.model.TaskType;
import se.tcmt.muninn.provider.adapter.ConnectionTestResult;
import se.tcmt.muninn.provider.adapter.DiscoveredModel;
import se.tcmt.muninn.provider.adapter.ModelDiscoveryAdapter;
import se.tcmt.muninn.provider.adapter.ProviderConnection;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Provider and model registry plus per-task resolution. API keys are stored AES-GCM encrypted and
 * only decrypted into a {@link ProviderConnection} when an adapter needs them. Scope GLOBAL is the
 * system provider (admin only); USER providers belong to {@code owner_user_id}.
 */
@Service
@RequiredArgsConstructor
public class ProviderService {

  static final String SETTING_OVERRIDE = "settings.userOverrideEnabled";
  static final String SETTING_DEFAULT_CHAT = "settings.defaultChatModelId";
  static final String SETTING_DEFAULT_TRANSCRIPTION = "settings.defaultTranscriptionModelId";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

  private static final RowMapper<AiProvider> PROVIDER =
      (rs, i) ->
          new AiProvider(
              rs.getObject("id", UUID.class),
              rs.getString("scope"),
              rs.getObject("owner_user_id", UUID.class),
              rs.getString("name"),
              rs.getString("base_url"),
              rs.getString("encrypted_key"),
              rs.getBoolean("enabled"),
              rs.getTimestamp("created_at").toInstant(),
              (Boolean) rs.getObject("last_test_ok"),
              rs.getString("last_test_message"),
              Optional.ofNullable(rs.getTimestamp("last_tested_at"))
                  .map(Timestamp::toInstant)
                  .orElse(null));

  private static final RowMapper<AiModel> MODEL =
      (rs, i) ->
          new AiModel(
              rs.getObject("id", UUID.class),
              rs.getObject("provider_id", UUID.class),
              rs.getString("model_identifier"),
              readCapabilities(rs.getString("capabilities")),
              rs.getBoolean("enabled"));

  private final JdbcTemplate jdbc;
  private final SecretCipher cipher;
  private final ModelDiscoveryAdapter discovery;

  /**
   * {@code ownerOrNull == null} creates the SYSTEM (GLOBAL) provider; the caller must have checked
   * admin.
   */
  public AiProvider create(UUID ownerOrNull, ProviderRequest r) {
    String name = requireName(r.name());
    String baseUrl = validateBaseUrl(r.baseUrl());
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into ai_provider(id, scope, owner_user_id, name, base_url, encrypted_key, enabled) "
            + "values (?, ?, ?, ?, ?, ?, ?)",
        id,
        ownerOrNull == null ? "GLOBAL" : "USER",
        ownerOrNull,
        name,
        baseUrl,
        encryptKey(r.apiKey()),
        r.enabled() == null || r.enabled());
    return get(id).orElseThrow();
  }

  /** Updates name, URL, key and enabled flag; a null key or flag keeps the current value. */
  public AiProvider update(UUID id, ProviderRequest r) {
    AiProvider cur = get(id).orElseThrow(ProviderService::notFound);
    String key = r.apiKey() == null ? cur.encryptedKey() : encryptKey(r.apiKey());
    jdbc.update(
        "update ai_provider set name = ?, base_url = ?, encrypted_key = ?, enabled = ? where id = ?",
        requireName(r.name()),
        validateBaseUrl(r.baseUrl()),
        key,
        r.enabled() == null ? cur.enabled() : r.enabled(),
        id);
    return get(id).orElseThrow();
  }

  /** Deletes a provider and, by cascade, its models. */
  public void delete(UUID id) {
    jdbc.update("delete from ai_provider where id = ?", id);
  }

  /** Finds a provider by id. */
  public Optional<AiProvider> get(UUID id) {
    return jdbc.query("select * from ai_provider where id = ?", PROVIDER, id).stream().findFirst();
  }

  /** Providers the actor manages: their own, plus the system one for admins. */
  public List<AiProvider> listFor(AppUser actor) {
    String where =
        actor.role() == AppUser.Role.ADMIN
            ? "owner_user_id = ? or scope = 'GLOBAL'"
            : "owner_user_id = ?";
    return jdbc.query(
        "select * from ai_provider where " + where + " order by created_at, id",
        PROVIDER,
        actor.id());
  }

  /** The provider if the actor owns it (or is admin and it is the system one); 404 otherwise. */
  public AiProvider requireManageable(AppUser actor, UUID id) {
    return get(id).filter(p -> canManage(actor, p)).orElseThrow(ProviderService::notFound);
  }

  /** Returns the model if the actor may manage its provider; otherwise 404. */
  public AiModel requireManageableModel(AppUser actor, UUID modelId) {
    AiModel m =
        jdbc.query("select * from ai_model where id = ?", MODEL, modelId).stream()
            .findFirst()
            .orElseThrow(ProviderService::notFound);
    requireManageable(actor, m.providerId());
    return m;
  }

  /** Lists the models of a provider ordered by identifier. */
  public List<AiModel> models(UUID providerId) {
    return jdbc.query(
        "select * from ai_model where provider_id = ? order by model_identifier",
        MODEL,
        providerId);
  }

  /** Updates a model's capabilities and enabled flag; null keeps the current value. */
  public AiModel updateModel(UUID modelId, List<String> capabilities, Boolean enabled) {
    AiModel cur =
        jdbc.query("select * from ai_model where id = ?", MODEL, modelId).stream()
            .findFirst()
            .orElseThrow(ProviderService::notFound);
    jdbc.update(
        "update ai_model set capabilities = ?::jsonb, enabled = ? where id = ?",
        writeCapabilities(capabilities == null ? cur.capabilities() : capabilities),
        enabled == null ? cur.enabled() : enabled,
        modelId);
    return jdbc.query("select * from ai_model where id = ?", MODEL, modelId).get(0);
  }

  /** Tests the provider connection and stores the outcome. */
  public ConnectionTestResult test(UUID providerId) {
    AiProvider p = get(providerId).orElseThrow(ProviderService::notFound);
    ConnectionTestResult result = discovery.test(connection(p));
    jdbc.update(
        "update ai_provider set last_test_ok = ?, last_test_message = ?, last_tested_at = now() where id = ?",
        result.ok(),
        result.message(),
        providerId);
    return result;
  }

  /**
   * Lists the provider's model and adds new ones; existing model keep their hand-edited settings.
   */
  public List<AiModel> discover(UUID providerId) {
    AiProvider p = get(providerId).orElseThrow(ProviderService::notFound);
    for (DiscoveredModel m : discovery.discover(connection(p))) {
      jdbc.update(
          "insert into ai_model(id, provider_id, model_identifier, capabilities) "
              + "values (?, ?, ?, ?::jsonb) on conflict (provider_id, model_identifier) do nothing",
          UUID.randomUUID(),
          providerId,
          m.id(),
          writeCapabilities(m.capabilities()));
    }
    return models(providerId);
  }

  /**
   * Picks the provider and model for a task: the user's own when overrides are allowed, else the
   * system default.
   */
  public Optional<EffectiveProvider> resolve(UUID userId, TaskType task) {
    String capability = task == TaskType.CHAT ? "chat" : "transcription";
    if (overrideEnabled()) {
      List<UUID> own =
          jdbc.query(
              "select m.id from ai_provider p join ai_model m on m.provider_id = p.id "
                  + "where p.scope = 'USER' and p.owner_user_id = ? and p.enabled and m.enabled "
                  + "and m.capabilities @> ?::jsonb order by p.created_at, p.id, m.model_identifier limit 1",
              (rs, i) -> rs.getObject(1, UUID.class),
              userId,
              writeCapabilities(List.of(capability)));
      if (!own.isEmpty()) {
        return effective(own.get(0), true);
      }
    }
    String key = task == TaskType.CHAT ? SETTING_DEFAULT_CHAT : SETTING_DEFAULT_TRANSCRIPTION;
    return setting(key)
        .flatMap(this::parseUuid)
        .flatMap(
            modelId -> {
              List<UUID> ok =
                  jdbc.query(
                      "select m.id from ai_provider p join ai_model m on m.provider_id = p.id "
                          + "where m.id = ? and p.scope = 'GLOBAL' and p.enabled and m.enabled "
                          + "and m.capabilities @> ?::jsonb",
                      (rs, i) -> rs.getObject(1, UUID.class),
                      modelId,
                      writeCapabilities(List.of(capability)));
              return ok.isEmpty() ? Optional.empty() : effective(ok.get(0), false);
            });
  }

  /** True when at least one SYSTEM (GLOBAL) provider exists. */
  public boolean hasSystemProvider() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from ai_provider where scope = 'GLOBAL')", Boolean.class));
  }

  /**
   * True when {@code modelId} is an enabled model of an enabled SYSTEM provider with the task's
   * capability, i.e. exactly what {@link #resolve} would accept as a default.
   */
  public boolean isUsableSystemModel(UUID modelId, TaskType task) {
    if (modelId == null) {
      return false;
    }
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from ai_provider p "
                + "join ai_model m on m.provider_id = p.id where m.id = ? and p.scope = 'GLOBAL' "
                + "and p.enabled and m.enabled and m.capabilities @> ?::jsonb)",
            Boolean.class,
            modelId,
            writeCapabilities(List.of(capability(task)))));
  }

  /** The stored default model id for the task, if any and well-formed (not re-validated here). */
  public Optional<UUID> defaultModelId(TaskType task) {
    return setting(defaultKey(task)).flatMap(this::parseUuid);
  }

  /** Stores the default; the caller must have checked {@link #isUsableSystemModel}. */
  public void setDefaultModel(TaskType task, UUID modelId) {
    putSetting(defaultKey(task), modelId.toString());
  }

  /** Allows or forbids users to use their own providers. */
  public void setOverrideEnabled(boolean enabled) {
    putSetting(SETTING_OVERRIDE, Boolean.toString(enabled));
  }

  /**
   * True when the URL's host is loopback, private, link-local, .local/.internal or a bare
   * single-label name.
   */
  public static boolean isLocal(String baseUrl) {
    String host = hostOf(baseUrl);
    if (host == null) {
      return false;
    }
    host = host.toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    if (host.contains(":") || IPV4.matcher(host).matches()) {
      try {
        InetAddress a = InetAddress.getByName(host); // literal address: no DNS lookup
        byte[] raw = a.getAddress();
        boolean uniqueLocal = raw.length == 16 && (raw[0] & 0xFE) == 0xFC;
        return a.isLoopbackAddress()
            || a.isSiteLocalAddress()
            || a.isLinkLocalAddress()
            || a.isAnyLocalAddress()
            || uniqueLocal;
      } catch (java.net.UnknownHostException e) {
        return false;
      }
    }
    return host.equals("localhost")
        || host.endsWith(".localhost")
        || host.endsWith(".local")
        || host.endsWith(".internal")
        || !host.contains(".");
  }

  /** Builds the effective provider for a model id. */
  private Optional<EffectiveProvider> effective(UUID modelId, boolean userOwned) {
    AiModel m = jdbc.query("select * from ai_model where id = ?", MODEL, modelId).get(0);
    AiProvider p = get(m.providerId()).orElseThrow();
    return Optional.of(
        new EffectiveProvider(p.id(), m.id(), m.modelIdentifier(), connection(p), userOwned));
  }

  /** Builds the connection details, decrypting the API key when one is set. */
  private ProviderConnection connection(AiProvider p) {
    return new ProviderConnection(
        p.baseUrl(), p.keySet() ? cipher.decrypt(p.encryptedKey()) : null);
  }

  /** Whether users may use their own providers. */
  public boolean overrideEnabled() {
    return setting(SETTING_OVERRIDE).map("true"::equalsIgnoreCase).orElse(false);
  }

  /** Maps a task to its model capability name. */
  private static String capability(TaskType task) {
    return task == TaskType.CHAT ? "chat" : "transcription";
  }

  /** Maps a task to its default-model setting key. */
  private static String defaultKey(TaskType task) {
    return task == TaskType.CHAT ? SETTING_DEFAULT_CHAT : SETTING_DEFAULT_TRANSCRIPTION;
  }

  /** Inserts or overwrites a setting. */
  private void putSetting(String key, String value) {
    jdbc.update(
        "insert into app_setting(key, value) values (?, ?) "
            + "on conflict (key) do update set value = excluded.value",
        key,
        value);
  }

  /** Reads a setting value. */
  private Optional<String> setting(String key) {
    return jdbc
        .query("select value from app_setting where key = ?", (rs, i) -> rs.getString(1), key)
        .stream()
        .findFirst();
  }

  /** Parses a UUID, or empty when malformed. */
  private Optional<UUID> parseUuid(String s) {
    try {
      return Optional.of(UUID.fromString(s.trim()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Encrypts an API key; null for none. */
  private String encryptKey(String apiKey) {
    return apiKey == null || apiKey.isEmpty() ? null : cipher.encrypt(apiKey);
  }

  /** Admins manage system providers; users manage their own. */
  private static boolean canManage(AppUser actor, AiProvider p) {
    return p.isSystem() ? actor.role() == AppUser.Role.ADMIN : actor.id().equals(p.ownerUserId());
  }

  /** Returns the trimmed name; 400 when blank. */
  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
    }
    return name.trim();
  }

  /** http/https only, a host, no userinfo; private and loopback targets are allowed on purpose. */
  static String validateBaseUrl(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl is required");
    }
    String trimmed = baseUrl.trim();
    try {
      URI uri = new URI(trimmed);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if (!scheme.equals("http") && !scheme.equals("https")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl must be http or https");
      }
      if (uri.getHost() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl needs a host");
      }
      if (uri.getRawUserInfo() != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "baseUrl must not contain credentials");
      }
    } catch (URISyntaxException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl is not a valid URL");
    }
    return trimmed;
  }

  /** Returns the URL host, or null when unparsable. */
  private static String hostOf(String baseUrl) {
    try {
      return new URI(baseUrl).getHost();
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /** Builds the 404 for missing or foreign providers. */
  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
  }

  /** Parses the stored JSON capability array; empty when malformed. */
  private static List<String> readCapabilities(String jsonArray) {
    try {
      return JSON.readValue(jsonArray, new TypeReference<List<String>>() {});
    } catch (JacksonException e) {
      return List.of();
    }
  }

  /** Serializes capabilities to a JSON array. */
  private static String writeCapabilities(List<String> capabilities) {
    try {
      return JSON.writeValueAsString(new ArrayList<>(capabilities));
    } catch (JacksonException e) {
      throw new IllegalStateException(e);
    }
  }
}
