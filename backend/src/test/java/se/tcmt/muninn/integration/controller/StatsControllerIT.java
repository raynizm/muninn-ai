package se.tcmt.muninn.integration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.repository.UserRepository;
import se.tcmt.muninn.service.TokenService;
import se.tcmt.muninn.support.AbstractPostgresIT;

/** HTTP surface of GET /api/stats. */
@SpringBootTest
@AutoConfigureMockMvc
class StatsControllerIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;

  @Autowired JdbcTemplate jdbc;

  @Autowired UserRepository users;

  @Autowired TokenService tokens;

  private String access;

  @BeforeEach
  void setUp() {
    jdbc.update("delete from app_user");
    AppUser ann = users.insertOwner("ann@example.com", null, "$2a$10$x");
    access = tokens.issue(ann).access();
    jdbc.update(
        "insert into recording(id, user_id, duration_ms) values (?, ?, 61000)",
        UUID.randomUUID(),
        ann.id());
  }

  @Test
  void returnsStatsForTheSignedInUser() throws Exception {
    mvc.perform(
            get("/api/stats")
                .param("tz", "Europe/Stockholm")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalRecordings").value(1))
        .andExpect(jsonPath("$.totalDurationMs").value(61000))
        .andExpect(jsonPath("$.averageDurationMs").value(61000))
        .andExpect(jsonPath("$.byMonth.length()").value(12));
  }

  @Test
  void timeZoneDefaultsToUtc() throws Exception {
    mvc.perform(get("/api/stats").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
        .andExpect(status().isOk());
  }

  @Test
  void invalidTimeZoneIs400() throws Exception {
    mvc.perform(
            get("/api/stats")
                .param("tz", "Not/AZone")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
        .andExpect(status().isBadRequest());
  }

  @Test
  void onlyRegionTimeZonesAreAccepted() throws Exception {
    // Numeric offsets mean different things to Java and Postgres, so they are refused.
    for (String tz : new String[] {"+05:00", "UTC+05:00", "GMT-3", "EST5EDT-garbage"}) {
      mvc.perform(
              get("/api/stats")
                  .param("tz", tz)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
          .andExpect(status().isBadRequest());
    }
    for (String tz : new String[] {"UTC", "Europe/Stockholm", "America/New_York"}) {
      mvc.perform(
              get("/api/stats")
                  .param("tz", tz)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
          .andExpect(status().isOk());
    }
  }

  @Test
  void needsAToken() throws Exception {
    mvc.perform(get("/api/stats")).andExpect(status().isUnauthorized());
  }
}
