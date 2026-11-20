package se.tcmt.muninn.service;

import java.io.InputStream;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.model.ProcessingStatus;
import se.tcmt.muninn.model.Recording;
import se.tcmt.muninn.service.AudioStore.Placement;
import se.tcmt.muninn.service.AudioStore.Staged;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Recording upload, listing and deletion. Every user-facing query filters on {@code user_id}, so
 * another user, admins included, sees nothing. Upload is idempotent by the client-chosen id: the
 * file is staged and placed outside any transaction, then the recording row and its processing job
 * are inserted together, with {@code on conflict (id) do nothing} deciding races and the stored
 * hash deciding the answer.
 */
@Service
@Slf4j
public class RecordingService {

  /** Phone audio is AAC in an MP4/M4A container; these are the names clients use for it. */
  static final Set<String> ACCEPTED_MIME =
      Set.of("audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac");

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  /**
   * Same text for "other user's id" and "same id, other bytes": the answer must not reveal
   * ownership.
   */
  private static final String ID_CONFLICT = "recording id already exists with different content";

  static final int MAX_TITLE = 500;

  private static final String SELECT =
      "select r.*, case when r.processing_status = 'FAILED' then "
          + "(select j.last_error from processing_job j where j.recording_id = r.id and j.last_error is not null "
          + "order by j.run_after desc limit 1) end as last_error from recording r ";

  private static final RowMapper<Recording> MAPPER =
      (rs, i) ->
          new Recording(
              rs.getObject("id", UUID.class),
              rs.getObject("user_id", UUID.class),
              rs.getString("title"),
              instant(rs, "created_at"),
              instant(rs, "updated_at"),
              rs.getObject("duration_ms", Long.class),
              rs.getString("mime"),
              rs.getObject("size_bytes", Long.class),
              rs.getString("sha256"),
              rs.getString("upload_status"),
              ProcessingStatus.valueOf(rs.getString("processing_status")),
              rs.getString("last_error"));

  public record UploadResult(Recording recording, boolean created) {}

  public record Transcript(String text, JsonNode segments) {}

  public record Summary(String text, JsonNode actionItems) {}

  public record RecordingDetail(Recording recording, Transcript transcript, Summary summary) {}

  private record Owner(UUID userId, String sha256) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final AudioStore audio;
  private final ObjectMapper json;
  private final long maxUploadBytes;

  /** Creates the service with its collaborators and the maximum accepted upload size in bytes. */
  public RecordingService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      AudioStore audio,
      ObjectMapper json,
      @Value("${muninn.max-upload-bytes}") long maxUploadBytes) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.audio = audio;
    this.json = json;
    this.maxUploadBytes = maxUploadBytes;
  }

  /** The maximum accepted audio size in bytes. */
  public long maxUploadBytes() {
    return maxUploadBytes;
  }

  /** Stores an upload without a title and returns the recording. */
  public Recording upload(
      UUID userId,
      UUID id,
      InputStream audio,
      String claimedSha256,
      String mime,
      Instant createdAt,
      Long durationMs) {
    return upload(userId, id, audio, claimedSha256, mime, createdAt, durationMs, null).recording();
  }

  /**
   * Stores the audio and enqueues processing. Errors are {@link ResponseStatusException}s: 400 bad
   * input or empty body, 409 id taken (by other bytes or by another user, indistinguishably), 413
   * over the size cap, 415 unsupported type, 422 body does not match {@code claimedSha256}. On
   * every error no row is written and no file this call created is left behind.
   */
  public UploadResult upload(
      UUID userId,
      UUID id,
      InputStream in,
      String claimedSha256,
      String mime,
      Instant createdAt,
      Long durationMs,
      String title) {
    String type = normalizeMime(mime);
    String claimed = normalizeSha(claimedSha256);
    if (durationMs != null && durationMs < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duration must not be negative");
    }
    String cleanTitle = normalizeTitle(title);

    // Id already stored: answer without reading the body or touching the file. Only when our own
    // row's
    // file has gone missing do we fall through, so the matching re-upload restores it.
    Optional<Owner> existing = owner(id);
    if (existing.isPresent()) {
      Recording mine = existingFor(userId, id, existing.get(), claimed);
      if (Files.exists(audio.path(userId, id))) {
        return new UploadResult(mine, false);
      }
    }

    Staged staged;
    try {
      staged = audio.stage(userId, in, maxUploadBytes);
    } catch (AudioStore.TooLargeException e) {
      throw new ResponseStatusException(
          HttpStatus.CONTENT_TOO_LARGE, "audio exceeds " + maxUploadBytes + " bytes");
    }
    try (staged) {
      if (staged.size() == 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty body");
      }
      if (!staged.sha256().equals(claimed)) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_CONTENT, "X-Content-SHA256 does not match the received bytes");
      }
      Placement placement = audio.place(staged, userId, id);
      if (placement == Placement.EXISTS_DIFFERENT) {
        // Another request holds this id with other bytes (or a stale file): never overwrite it.
        throw new ResponseStatusException(HttpStatus.CONFLICT, ID_CONFLICT);
      }
      boolean createdFile = placement == Placement.CREATED;
      Optional<Recording> inserted;
      try {
        inserted = insert(userId, id, staged, type, createdAt, durationMs, cleanTitle);
      } catch (RuntimeException e) {
        if (createdFile) {
          deleteFileQuietly(userId, id, e);
        }
        throw e;
      }
      if (inserted.isPresent()) {
        return new UploadResult(inserted.get(), true);
      }
      // Lost the insert race (or the row predates a missing file): the stored hash decides.
      Optional<Owner> winner = owner(id);
      if (winner.isPresent()
          && winner.get().userId().equals(userId)
          && claimed.equals(winner.get().sha256())) {
        return new UploadResult(get(userId, id).orElseThrow(), false);
      }
      if (createdFile) {
        deleteFileQuietly(userId, id, null);
      }
      throw new ResponseStatusException(HttpStatus.CONFLICT, ID_CONFLICT);
    }
  }

  /**
   * The caller's recordings with {@code updated_at >= updatedSince} (all when null), oldest change
   * first.
   */
  public List<Recording> list(UUID userId, Instant updatedSince) {
    if (updatedSince == null) {
      return jdbc.query(SELECT + "where r.user_id = ? order by r.updated_at, r.id", MAPPER, userId);
    }
    return jdbc.query(
        SELECT + "where r.user_id = ? and r.updated_at >= ? order by r.updated_at, r.id",
        MAPPER,
        userId,
        Timestamp.from(updatedSince));
  }

  /** Finds one of the user's recordings. */
  public Optional<Recording> get(UUID userId, UUID id) {
    return jdbc.query(SELECT + "where r.id = ? and r.user_id = ?", MAPPER, id, userId).stream()
        .findFirst();
  }

  /** {@link #get} plus transcript and summary when they exist (null otherwise). */
  public Optional<RecordingDetail> detail(UUID userId, UUID id) {
    return get(userId, id)
        .map(
            r ->
                new RecordingDetail(
                    r,
                    jdbc
                        .query(
                            "select text, segments::text as segments from transcript "
                                + "where recording_id = ? and user_id = ?",
                            (rs, i) ->
                                new Transcript(
                                    rs.getString("text"), parse(rs.getString("segments"))),
                            id,
                            userId)
                        .stream()
                        .findFirst()
                        .orElse(null),
                    jdbc
                        .query(
                            "select text, action_items::text as action_items from summary "
                                + "where recording_id = ? and user_id = ?",
                            (rs, i) ->
                                new Summary(
                                    rs.getString("text"), parse(rs.getString("action_items"))),
                            id,
                            userId)
                        .stream()
                        .findFirst()
                        .orElse(null)));
  }

  /**
   * Deletes the caller's recording (transcript, summary, jobs, row) in one transaction, then its
   * file. Returns false when there is no such recording for this user. A missing file is fine.
   */
  public boolean delete(UUID userId, UUID id) {
    boolean deleted =
        Boolean.TRUE.equals(
            tx.execute(
                s -> {
                  List<UUID> locked =
                      jdbc.query(
                          "select id from recording where id = ? and user_id = ? for update",
                          (rs, i) -> rs.getObject(1, UUID.class),
                          id,
                          userId);
                  if (locked.isEmpty()) {
                    return false;
                  }
                  jdbc.update(
                      "delete from transcript where recording_id = ? and user_id = ?", id, userId);
                  jdbc.update(
                      "delete from summary where recording_id = ? and user_id = ?", id, userId);
                  jdbc.update("delete from processing_job where recording_id = ?", id);
                  jdbc.update("delete from recording where id = ? and user_id = ?", id, userId);
                  return true;
                }));
    if (deleted) {
      deleteFileQuietly(userId, id, null);
    }
    return deleted;
  }

  /**
   * Worker-facing (not user-scoped): sets the processing status and bumps {@code updated_at} so
   * incremental sync picks the change up. Returns false if the recording no longer exists.
   */
  public boolean updateProcessingStatus(UUID recordingId, ProcessingStatus status) {
    return jdbc.update(
            "update recording set processing_status = ?, updated_at = now() where id = ?",
            status.name(),
            recordingId)
        == 1;
  }

  /** Worker-facing: bumps {@code updated_at} after a change to transcript or summary rows. */
  public boolean touch(UUID recordingId) {
    return jdbc.update("update recording set updated_at = now() where id = ?", recordingId) == 1;
  }

  // ---- internals ----

  /**
   * Inserts the recording row and its processing job in one transaction; empty when the id already
   * exists.
   */
  private Optional<Recording> insert(
      UUID userId,
      UUID id,
      Staged staged,
      String mime,
      Instant createdAt,
      Long durationMs,
      String title) {
    return tx.execute(
        s -> {
          int rows =
              jdbc.update(
                  "insert into recording(id, user_id, sha256, mime, size_bytes, duration_ms, "
                      + "upload_status, processing_status, title, created_at, updated_at) "
                      + "values (?, ?, ?, ?, ?, ?, 'UPLOADED', 'PENDING', ?, coalesce(?, now()), now()) "
                      + "on conflict (id) do nothing",
                  id,
                  userId,
                  staged.sha256(),
                  mime,
                  staged.size(),
                  durationMs,
                  title,
                  createdAt == null ? null : Timestamp.from(createdAt));
          if (rows == 0) {
            return Optional.<Recording>empty();
          }
          jdbc.update(
              "insert into processing_job(id, recording_id, state, run_after) "
                  + "values (?, ?, 'PENDING', now())",
              UUID.randomUUID(),
              id);
          return get(userId, id);
        });
  }

  /** Looks up the owner and stored hash of a recording id, whoever owns it. */
  private Optional<Owner> owner(UUID id) {
    return jdbc
        .query(
            "select user_id, sha256 from recording where id = ?",
            (rs, i) -> new Owner(rs.getObject("user_id", UUID.class), rs.getString("sha256")),
            id)
        .stream()
        .findFirst();
  }

  /**
   * Returns the existing recording for an identical repeat; 409 for another owner or different
   * bytes.
   */
  private Recording existingFor(UUID userId, UUID id, Owner owner, String claimed) {
    if (!owner.userId().equals(userId) || !claimed.equals(owner.sha256())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ID_CONFLICT);
    }
    return get(userId, id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, ID_CONFLICT));
  }

  /** Removes a placed audio file, attaching any failure to the cause as suppressed. */
  private void deleteFileQuietly(UUID userId, UUID id, Exception cause) {
    try {
      audio.delete(userId, id);
    } catch (RuntimeException e) {
      if (cause != null) {
        cause.addSuppressed(e);
      } else {
        log.warn("Could not delete audio file for recording {}: {}", id, e.toString());
      }
    }
  }

  /** Returns the lower-cased audio type without parameters; 415 when missing or not accepted. */
  public static String normalizeMime(String mime) {
    if (mime == null || mime.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type is required");
    }
    String type;
    try {
      MediaType m = MediaType.parseMediaType(mime);
      type = (m.getType() + "/" + m.getSubtype()).toLowerCase(Locale.ROOT);
    } catch (InvalidMediaTypeException e) {
      throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "invalid Content-Type");
    }
    if (!ACCEPTED_MIME.contains(type)) {
      throw new ResponseStatusException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "Content-Type must be one of " + ACCEPTED_MIME.stream().sorted().toList());
    }
    return type;
  }

  /** Returns the lower-cased hex SHA-256; 400 when malformed. */
  private static String normalizeSha(String sha) {
    String s = sha == null ? "" : sha.trim().toLowerCase(Locale.ROOT);
    if (!SHA256_HEX.matcher(s).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "X-Content-SHA256 must be 64 hex characters");
    }
    return s;
  }

  /** Trims the title; null for blank and 400 when too long. */
  private static String normalizeTitle(String title) {
    if (title == null || title.isBlank()) {
      return null;
    }
    String t = title.strip();
    if (t.length() > MAX_TITLE) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title longer than " + MAX_TITLE);
    }
    return t;
  }

  /** Parses stored JSON text. */
  private JsonNode parse(String text) {
    try {
      return json.readTree(text);
    } catch (JacksonException e) {
      throw new IllegalStateException("stored json is invalid", e);
    }
  }

  /** Reads a nullable timestamp column as an Instant. */
  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
