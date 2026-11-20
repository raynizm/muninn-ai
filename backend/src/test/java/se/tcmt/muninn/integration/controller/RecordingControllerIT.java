package se.tcmt.muninn.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static se.tcmt.muninn.support.TestData.bytes;
import static se.tcmt.muninn.support.TestData.sha;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.repository.UserRepository;
import se.tcmt.muninn.service.AudioStore;
import se.tcmt.muninn.service.TokenService;
import se.tcmt.muninn.support.AbstractPostgresIT;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP contract of /api/recordings: status codes, ownership (404 / 409 without leak), streaming
 * audio.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecordingControllerIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;

  @Autowired UserRepository users;

  @Autowired TokenService tokens;

  @Autowired JdbcTemplate jdbc;

  @Autowired ObjectMapper json;

  @Autowired AudioStore audio;

  AppUser admin;
  AppUser ann;
  AppUser bob;

  @BeforeEach
  void reset() {
    jdbc.update("delete from app_user");
    admin = user("admin@acme.com", AppUser.Role.ADMIN);
    ann = user("ann@acme.com", AppUser.Role.USER);
    bob = user("bob@acme.com", AppUser.Role.USER);
  }

  private AppUser user(String email, AppUser.Role role) {
    users.insertIfAbsent(email, email, role, "https://idp.test", "sub-" + email);
    return users.findByEmail(email).orElseThrow();
  }

  private MockHttpServletRequestBuilder as(AppUser u, MockHttpServletRequestBuilder b) {
    return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(u).access());
  }

  private MockHttpServletRequestBuilder putAudio(
      AppUser u, Object id, byte[] data, String claimedSha) {
    return as(u, put("/api/recordings/" + id))
        .contentType("audio/mp4")
        .content(data)
        .header("X-Content-SHA256", claimedSha);
  }

  private MvcResult upload(AppUser u, UUID id, byte[] data, int expected) throws Exception {
    return mvc.perform(putAudio(u, id, data, sha(data)))
        .andExpect(status().is(expected))
        .andReturn();
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString());
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private List<Path> filesOf(AppUser u) throws IOException {
    Path dir = DATA_DIR.resolve("audio").resolve(u.id().toString());
    if (!Files.exists(dir)) {
      return List.of();
    }
    try (Stream<Path> s = Files.walk(dir)) {
      return s.filter(Files::isRegularFile).toList();
    }
  }

  @Test
  void uploadTwiceSameBytesReturnsSameRecordingAndOneJob() throws Exception {
    UUID id = UUID.randomUUID();
    byte[] data = bytes(4096, 21);
    MvcResult first =
        mvc.perform(
                putAudio(ann, id, data, sha(data))
                    .header("X-Recording-Created-At", "2026-11-19T08:30:00Z")
                    .header("X-Recording-Duration-Ms", "61000")
                    .header("X-Recording-Title", "Standup"))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode v = body(first);
    assertThat(v.get("id").asText()).isEqualTo(id.toString());
    assertThat(v.get("title").asText()).isEqualTo("Standup");
    assertThat(v.get("createdAt").asText()).isEqualTo("2026-11-19T08:30:00Z");
    assertThat(v.get("durationMs").asLong()).isEqualTo(61000);
    assertThat(v.get("mime").asText()).isEqualTo("audio/mp4");
    assertThat(v.get("sizeBytes").asLong()).isEqualTo(4096);
    assertThat(v.get("uploadStatus").asText()).isEqualTo("UPLOADED");
    assertThat(v.get("processingStatus").asText()).isEqualTo("PENDING");
    assertThat(v.has("updatedAt")).isTrue();

    JsonNode again = body(upload(ann, id, data, 200));
    assertThat(again.get("id").asText()).isEqualTo(id.toString());
    assertThat(again.get("createdAt").asText()).isEqualTo("2026-11-19T08:30:00Z");
    assertThat(count("select count(*) from processing_job where recording_id = ?", id))
        .isEqualTo(1);
    assertThat(filesOf(ann)).hasSize(1);
  }

  @Test
  void uploadSameIdDifferentBytesReturns409AndKeepsOriginal() throws Exception {
    UUID id = UUID.randomUUID();
    byte[] original = bytes(2048, 22);
    upload(ann, id, original, 201);
    upload(ann, id, bytes(2048, 23), 409);
    assertThat(audio.path(ann.id(), id)).hasBinaryContent(original);
    assertThat(filesOf(ann)).hasSize(1);
  }

  @Test
  void hashMismatchRejectedAndNoFileLeft() throws Exception {
    UUID id = UUID.randomUUID();
    mvc.perform(putAudio(ann, id, bytes(2048, 24), sha(bytes(2048, 25))))
        .andExpect(status().isUnprocessableEntity());
    assertThat(filesOf(ann)).isEmpty();
    assertThat(count("select count(*) from recording where id = ?", id)).isZero();
  }

  @Test
  void emptyBodyRejected400() throws Exception {
    UUID id = UUID.randomUUID();
    mvc.perform(putAudio(ann, id, new byte[0], sha(new byte[0])))
        .andExpect(status().isBadRequest());
    assertThat(count("select count(*) from recording where id = ?", id)).isZero();
    assertThat(filesOf(ann)).isEmpty();
  }

  @Test
  void badRequestsAreRejected400() throws Exception {
    byte[] data = bytes(100, 26);
    mvc.perform(putAudio(ann, "not-a-uuid", data, sha(data))).andExpect(status().isBadRequest());
    mvc.perform(
            as(ann, put("/api/recordings/" + UUID.randomUUID()))
                .contentType("audio/mp4")
                .content(data))
        .andExpect(status().isBadRequest());
    mvc.perform(putAudio(ann, UUID.randomUUID(), data, "xyz")).andExpect(status().isBadRequest());
    mvc.perform(
            putAudio(ann, UUID.randomUUID(), data, sha(data))
                .header("X-Recording-Created-At", "yesterday"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            putAudio(ann, UUID.randomUUID(), data, sha(data))
                .header("X-Recording-Duration-Ms", "-5"))
        .andExpect(status().isBadRequest());
    mvc.perform(as(ann, get("/api/recordings/not-a-uuid"))).andExpect(status().isBadRequest());
    assertThat(filesOf(ann)).isEmpty();
  }

  @Test
  void uploadOverLimitReturns413AndLeavesNoFile() throws Exception {
    UUID id = UUID.randomUUID();
    byte[] data = bytes((int) MAX_UPLOAD_BYTES + 1, 27);
    upload(ann, id, data, 413);
    assertThat(filesOf(ann)).isEmpty();
    assertThat(count("select count(*) from recording where id = ?", id)).isZero();

    byte[] exactly = bytes((int) MAX_UPLOAD_BYTES, 28);
    upload(ann, UUID.randomUUID(), exactly, 201);
  }

  @Test
  void unsupportedContentTypeReturns415() throws Exception {
    UUID id = UUID.randomUUID();
    byte[] data = bytes(100, 29);
    mvc.perform(
            as(ann, put("/api/recordings/" + id))
                .contentType("audio/mpeg")
                .content(data)
                .header("X-Content-SHA256", sha(data)))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(
            as(ann, put("/api/recordings/" + id))
                .content(data)
                .header("X-Content-SHA256", sha(data)))
        .andExpect(status().isUnsupportedMediaType());
    for (String accepted :
        List.of("audio/m4a", "audio/x-m4a", "audio/aac", "Audio/MP4; codecs=mp4a.40.2")) {
      mvc.perform(
              as(ann, put("/api/recordings/" + UUID.randomUUID()))
                  .contentType(accepted)
                  .content(data)
                  .header("X-Content-SHA256", sha(data)))
          .andExpect(status().isCreated());
    }
    assertThat(count("select count(*) from recording where id = ?", id)).isZero();
  }

  @Test
  void otherUserGets404OnGetAudioDelete() throws Exception {
    UUID id = UUID.randomUUID();
    upload(ann, id, bytes(100, 30), 201);

    mvc.perform(as(bob, get("/api/recordings/" + id))).andExpect(status().isNotFound());
    mvc.perform(as(bob, get("/api/recordings/" + id + "/audio"))).andExpect(status().isNotFound());
    mvc.perform(as(bob, delete("/api/recordings/" + id))).andExpect(status().isNotFound());
    assertThat(
            body(
                mvc.perform(as(bob, get("/api/recordings")))
                    .andExpect(status().isOk())
                    .andReturn()))
        .isEmpty();
    assertThat(audio.path(ann.id(), id)).exists();
    mvc.perform(as(ann, get("/api/recordings/" + id))).andExpect(status().isOk());
  }

  @Test
  void adminGets404ForOtherUsersRecording() throws Exception {
    UUID id = UUID.randomUUID();
    upload(ann, id, bytes(100, 31), 201);

    mvc.perform(as(admin, get("/api/recordings/" + id))).andExpect(status().isNotFound());
    mvc.perform(as(admin, get("/api/recordings/" + id + "/audio")))
        .andExpect(status().isNotFound());
    mvc.perform(as(admin, delete("/api/recordings/" + id))).andExpect(status().isNotFound());
    assertThat(
            body(
                mvc.perform(as(admin, get("/api/recordings")))
                    .andExpect(status().isOk())
                    .andReturn()))
        .isEmpty();
    assertThat(count("select count(*) from recording where id = ?", id)).isEqualTo(1);
  }

  @Test
  void putOfOtherUsersIdReturns409WithoutLeak() throws Exception {
    UUID id = UUID.randomUUID();
    byte[] data = bytes(100, 32);
    upload(ann, id, data, 201);

    MvcResult same = upload(bob, id, data, 409);
    MvcResult different = upload(bob, id, bytes(100, 33), 409);
    for (MvcResult r : List.of(same, different)) {
      String text = r.getResponse().getContentAsString() + r.getResponse().getErrorMessage();
      assertThat(text).doesNotContain(ann.id().toString()).doesNotContain("ann@acme.com");
    }
    assertThat(filesOf(bob)).isEmpty();
    assertThat(audio.path(ann.id(), id)).hasBinaryContent(data);
  }

  @Test
  void getReturnsDetailAndAudioStreams() throws Exception {
    UUID id = UUID.randomUUID();
    byte[] data = bytes(5000, 34);
    upload(ann, id, data, 201);

    JsonNode d =
        body(
            mvc.perform(as(ann, get("/api/recordings/" + id)))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(d.get("transcript").isNull()).isTrue();
    assertThat(d.get("summary").isNull()).isTrue();
    assertThat(d.has("lastError")).isTrue();

    jdbc.update(
        "insert into transcript(recording_id, user_id, text, segments) values (?, ?, 'hello', "
            + "'[{\"start\":0,\"end\":1,\"text\":\"hello\"}]'::jsonb)",
        id,
        ann.id());
    jdbc.update(
        "insert into summary(recording_id, user_id, text, action_items) values (?, ?, 'greeting', "
            + "'[\"say hi\"]'::jsonb)",
        id,
        ann.id());
    d =
        body(
            mvc.perform(as(ann, get("/api/recordings/" + id)))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(d.at("/transcript/text").asText()).isEqualTo("hello");
    assertThat(d.at("/transcript/segments/0/text").asText()).isEqualTo("hello");
    assertThat(d.at("/summary/text").asText()).isEqualTo("greeting");
    assertThat(d.at("/summary/actionItems/0").asText()).isEqualTo("say hi");

    MvcResult full =
        mvc.perform(as(ann, get("/api/recordings/" + id + "/audio")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(full.getResponse().getContentType()).isEqualTo("audio/mp4");
    assertThat(full.getResponse().getContentAsByteArray()).isEqualTo(data);

    MvcResult part =
        mvc.perform(as(ann, get("/api/recordings/" + id + "/audio")).header("Range", "bytes=10-19"))
            .andExpect(status().isPartialContent())
            .andReturn();
    assertThat(part.getResponse().getContentAsByteArray())
        .isEqualTo(java.util.Arrays.copyOfRange(data, 10, 20));
  }

  @Test
  void listFiltersByUpdatedSince() throws Exception {
    UUID old = UUID.randomUUID();
    UUID recent = UUID.randomUUID();
    upload(ann, old, bytes(100, 35), 201);
    upload(ann, recent, bytes(100, 36), 201);
    jdbc.update("update recording set updated_at = '2026-01-01T00:00:00Z' where id = ?", old);
    jdbc.update("update recording set updated_at = '2026-03-01T00:00:00Z' where id = ?", recent);

    JsonNode all =
        body(mvc.perform(as(ann, get("/api/recordings"))).andExpect(status().isOk()).andReturn());
    assertThat(all)
        .extracting(n -> n.get("id").asText())
        .containsExactly(old.toString(), recent.toString());

    JsonNode since =
        body(
            mvc.perform(
                    as(ann, get("/api/recordings").param("updatedSince", "2026-02-01T00:00:00Z")))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(since).extracting(n -> n.get("id").asText()).containsExactly(recent.toString());

    mvc.perform(as(ann, get("/api/recordings").param("updatedSince", "garbage")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteRemovesFileAndRows() throws Exception {
    UUID id = UUID.randomUUID();
    upload(ann, id, bytes(100, 37), 201);
    mvc.perform(as(ann, delete("/api/recordings/" + id))).andExpect(status().isNoContent());
    assertThat(filesOf(ann)).isEmpty();
    assertThat(count("select count(*) from recording where id = ?", id)).isZero();
    assertThat(count("select count(*) from processing_job where recording_id = ?", id)).isZero();
    mvc.perform(as(ann, get("/api/recordings/" + id))).andExpect(status().isNotFound());
    mvc.perform(as(ann, delete("/api/recordings/" + id))).andExpect(status().isNotFound());
  }

  @Test
  void deleteWhileFileMissingStillSucceeds() throws Exception {
    UUID id = UUID.randomUUID();
    upload(ann, id, bytes(100, 38), 201);
    Files.delete(audio.path(ann.id(), id));
    mvc.perform(as(ann, get("/api/recordings/" + id + "/audio"))).andExpect(status().isNotFound());
    mvc.perform(as(ann, delete("/api/recordings/" + id))).andExpect(status().isNoContent());
    assertThat(count("select count(*) from recording where id = ?", id)).isZero();
  }

  @Test
  void anonymousGets401() throws Exception {
    mvc.perform(get("/api/recordings")).andExpect(status().isUnauthorized());
    mvc.perform(
            put("/api/recordings/" + UUID.randomUUID())
                .contentType("audio/mp4")
                .content(new byte[] {1}))
        .andExpect(status().isUnauthorized());
  }
}
