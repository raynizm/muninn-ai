package se.tcmt.muninn.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import se.tcmt.muninn.service.SetupService;
import se.tcmt.muninn.support.AbstractPostgresIT;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SetupServiceIT extends AbstractPostgresIT {

  private static final Pattern TOKEN_LINE = Pattern.compile("SETUP TOKEN: (\\S+)");

  @Autowired SetupService setup;

  @Autowired JdbcTemplate jdbc;

  @Autowired MockMvc mvc;

  @BeforeEach
  void reset() {
    jdbc.update("delete from app_setting");
  }

  private static List<String> loggedTokens(CapturedOutput out) {
    Matcher m = TOKEN_LINE.matcher(out.getAll());
    return m.results().map(r -> r.group(1)).toList();
  }

  private String issueAndCapture(CapturedOutput out) {
    int before = loggedTokens(out).size();
    setup.issueTokenIfNeeded();
    List<String> tokens = loggedTokens(out);
    assertThat(tokens).hasSize(before + 1);
    return tokens.get(before);
  }

  @Test
  void issuesTokenOnceAndStoresHash(CapturedOutput out) throws Exception {
    int before = loggedTokens(out).size();
    setup.issueTokenIfNeeded();
    setup.issueTokenIfNeeded();

    List<String> tokens = loggedTokens(out);
    assertThat(tokens).hasSize(before + 1);
    String raw = tokens.get(before);
    assertThat(raw).matches("[A-Za-z0-9_-]{43}");

    String expectedHash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    List<String> values = jdbc.queryForList("select value from app_setting", String.class);
    assertThat(values).contains(expectedHash);
    assertThat(values).doesNotContain(raw);
    assertThat(setup.isComplete()).isFalse();
  }

  @Test
  void verifyAcceptsRawRejectsWrong(CapturedOutput out) {
    String raw = issueAndCapture(out);

    assertThat(setup.verifyToken(raw)).isTrue();
    assertThat(setup.verifyToken(raw + "x")).isFalse();
    assertThat(setup.verifyToken("")).isFalse();
    assertThat(setup.verifyToken(null)).isFalse();
  }

  @Test
  void afterCompleteStatusIsTrueAndTokenRejected(CapturedOutput out) {
    String raw = issueAndCapture(out);
    assertThat(setup.verifyToken(raw)).isTrue();

    int before = loggedTokens(out).size();
    setup.markComplete();

    assertThat(setup.isComplete()).isTrue();
    assertThat(setup.verifyToken(raw)).isFalse();
    setup.issueTokenIfNeeded();
    assertThat(loggedTokens(out)).hasSize(before);
  }

  @Test
  void setupEndpointsReturn404WhenComplete(CapturedOutput out) throws Exception {
    String raw = issueAndCapture(out);

    mvc.perform(get("/api/setup/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(false));
    mvc.perform(
            post("/api/setup/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + raw + "\"}"))
        .andExpect(status().isOk());

    setup.markComplete();

    mvc.perform(get("/api/setup/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(true));
    mvc.perform(
            post("/api/setup/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + raw + "\"}"))
        .andExpect(status().isNotFound());
  }
}
