package se.tcmt.muninn.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import se.tcmt.muninn.repository.UserRepository;
import se.tcmt.muninn.service.StatsService;
import se.tcmt.muninn.support.AbstractPostgresIT;

/** Per-user recording statistics. "Now" is 2026-11-22T12:00:00Z in every test. */
@SpringBootTest
class StatsServiceIT extends AbstractPostgresIT {

  private static final Clock NOW =
      Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);
  private static final ZoneId UTC = ZoneId.of("UTC");
  private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");

  @Autowired JdbcTemplate jdbc;

  @Autowired UserRepository users;

  private StatsService stats;
  private UUID ann;
  private UUID bob;

  @BeforeEach
  void setUp() {
    jdbc.update("delete from app_user"); // cascades to recordings
    stats = new StatsService(jdbc, NOW);
    ann = users.insertOwner("ann@example.com", null, "$2a$10$x").id();
    bob = users.insertOwner("bob@example.com", null, "$2a$10$x").id();
  }

  private void recording(UUID user, String createdAt, Long durationMs) {
    jdbc.update(
        "insert into recording(id, user_id, duration_ms, created_at) values (?, ?, ?, ?::timestamptz)",
        UUID.randomUUID(),
        user,
        durationMs,
        createdAt);
  }

  @Test
  void emptyLibraryGivesZerosAndNoAverage() {
    StatsService.Stats s = stats.compute(ann, UTC);

    assertThat(s.totalRecordings()).isZero();
    assertThat(s.recordingsWithDuration()).isZero();
    assertThat(s.totalDurationMs()).isZero();
    assertThat(s.averageDurationMs()).isNull();
    assertThat(s.thisMonthRecordings()).isZero();
    assertThat(s.byMonth()).hasSize(12).allSatisfy(m -> assertThat(m.count()).isZero());
  }

  @Test
  void totalsAndAverageUseOnlyRecordingsWithADuration() {
    recording(ann, "2026-11-01T10:00:00Z", 60_000L);
    recording(ann, "2026-11-02T10:00:00Z", 120_000L);
    recording(ann, "2026-11-03T10:00:00Z", null);

    StatsService.Stats s = stats.compute(ann, UTC);

    assertThat(s.totalRecordings()).isEqualTo(3);
    assertThat(s.recordingsWithDuration()).isEqualTo(2);
    assertThat(s.totalDurationMs()).isEqualTo(180_000);
    assertThat(s.averageDurationMs()).isEqualTo(90_000);
  }

  @Test
  void byMonthCoversTheLastTwelveMonthsOldestFirst() {
    recording(ann, "2026-11-05T10:00:00Z", 1_000L);
    recording(ann, "2026-03-05T10:00:00Z", 2_000L);

    StatsService.Stats s = stats.compute(ann, UTC);

    assertThat(s.byMonth()).hasSize(12);
    assertThat(s.byMonth().get(0).month()).isEqualTo("2025-12");
    assertThat(s.byMonth().get(11).month()).isEqualTo("2026-11");
    assertThat(s.byMonth().get(11).count()).isEqualTo(1);
    assertThat(s.byMonth().get(11).durationMs()).isEqualTo(1_000);
    assertThat(s.byMonth().get(3).month()).isEqualTo("2026-03");
    assertThat(s.byMonth().get(3).durationMs()).isEqualTo(2_000);
    assertThat(s.byMonth().get(5).count()).isZero();
  }

  @Test
  void recordingsOlderThanTwelveMonthsCountInTotalsButNotInTheChart() {
    recording(ann, "2024-05-05T10:00:00Z", 5_000L);

    StatsService.Stats s = stats.compute(ann, UTC);

    assertThat(s.totalRecordings()).isEqualTo(1);
    assertThat(s.totalDurationMs()).isEqualTo(5_000);
    assertThat(s.byMonth()).allSatisfy(m -> assertThat(m.count()).isZero());
  }

  @Test
  void monthBoundaryFollowsTheTimeZone() {
    recording(ann, "2026-10-31T23:30:00Z", 1_000L);

    StatsService.Stats utc = stats.compute(ann, UTC);
    StatsService.Stats stockholm = stats.compute(ann, STOCKHOLM);

    assertThat(utc.byMonth().get(10).month()).isEqualTo("2026-10");
    assertThat(utc.byMonth().get(10).count()).isEqualTo(1);
    assertThat(utc.thisMonthRecordings()).isZero();
    assertThat(stockholm.byMonth().get(10).count()).isZero();
    assertThat(stockholm.byMonth().get(11).count()).isEqualTo(1);
    assertThat(stockholm.thisMonthRecordings()).isEqualTo(1);
  }

  @Test
  void otherUsersRecordingsAreNeverCounted() {
    recording(bob, "2026-11-05T10:00:00Z", 9_000L);
    recording(ann, "2026-11-06T10:00:00Z", 1_000L);

    StatsService.Stats s = stats.compute(ann, UTC);

    assertThat(s.totalRecordings()).isEqualTo(1);
    assertThat(s.totalDurationMs()).isEqualTo(1_000);
    assertThat(s.byMonth().get(11).count()).isEqualTo(1);
  }

  @Test
  void thisMonthCountsOnlyTheCurrentMonth() {
    recording(ann, "2026-11-01T00:00:00Z", 1_000L);
    recording(ann, "2026-11-20T10:00:00Z", 1_000L);
    recording(ann, "2026-10-20T10:00:00Z", 1_000L);

    assertThat(stats.compute(ann, UTC).thisMonthRecordings()).isEqualTo(2);
  }
}
