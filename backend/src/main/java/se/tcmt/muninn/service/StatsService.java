package se.tcmt.muninn.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Simple statistics over one user's recordings. */
@Service
public class StatsService {

  /** Months shown in the per-month series, counting the current one. */
  static final int MONTHS = 12;

  private final JdbcTemplate jdbc;
  private final Clock clock;

  /** Creates the service on the system clock. */
  @Autowired
  public StatsService(JdbcTemplate jdbc) {
    this(jdbc, Clock.systemUTC());
  }

  /** Creates the service on the given clock (for tests). */
  public StatsService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** One month of the series; {@code month} is {@code YYYY-MM}. */
  public record MonthStat(String month, long count, long durationMs) {}

  /**
   * Totals plus the last twelve months. Hours and the average only cover recordings that have a
   * known duration; {@code averageDurationMs} is null when there are none.
   */
  public record Stats(
      long totalRecordings,
      long recordingsWithDuration,
      long totalDurationMs,
      Long averageDurationMs,
      long thisMonthRecordings,
      List<MonthStat> byMonth) {}

  /** Computes the statistics of the user's recordings, grouping months in the given time zone. */
  public Stats compute(UUID userId, ZoneId zone) {
    Boolean known =
        jdbc.queryForObject(
            "select exists(select 1 from pg_timezone_names where name = ?)",
            Boolean.class,
            zone.getId());
    if (!Boolean.TRUE.equals(known)) {
      // Java knows this zone but the database does not, so it cannot group by it.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown time zone");
    }
    YearMonth current = YearMonth.from(clock.instant().atZone(zone));
    YearMonth oldest = current.minusMonths(MONTHS - 1);

    long[] totals =
        jdbc.queryForObject(
            "select count(*), count(duration_ms), coalesce(sum(duration_ms), 0) "
                + "from recording where user_id = ?",
            (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)},
            userId);

    Map<String, long[]> perMonth = new HashMap<>();
    jdbc.query(
        "select to_char(created_at at time zone ?, 'YYYY-MM') as month, count(*) as n, "
            + "coalesce(sum(duration_ms), 0) as ms "
            + "from recording where user_id = ? and created_at >= ? group by 1",
        rs -> {
          perMonth.put(rs.getString("month"), new long[] {rs.getLong("n"), rs.getLong("ms")});
        },
        zone.getId(),
        userId,
        Timestamp.from(oldest.atDay(1).atStartOfDay(zone).toInstant()));

    List<MonthStat> series = new ArrayList<>();
    for (int i = 0; i < MONTHS; i++) {
      String month = oldest.plusMonths(i).toString();
      long[] values = perMonth.getOrDefault(month, new long[] {0, 0});
      series.add(new MonthStat(month, values[0], values[1]));
    }

    Long average = totals[1] == 0 ? null : totals[2] / totals[1];
    return new Stats(
        totals[0], totals[1], totals[2], average, series.get(MONTHS - 1).count(), series);
  }
}
