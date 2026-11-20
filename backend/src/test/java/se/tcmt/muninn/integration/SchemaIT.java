package se.tcmt.muninn.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import se.tcmt.muninn.support.AbstractPostgresIT;

@SpringBootTest
class SchemaIT extends AbstractPostgresIT {

  @Autowired JdbcTemplate jdbc;

  private static final List<String> TABLES =
      List.of(
          "app_user",
          "app_setting",
          "ai_provider",
          "ai_model",
          "recording",
          "transcript",
          "summary",
          "processing_job",
          "refresh_token");

  private static final List<String> USER_OWNED =
      List.of("recording", "transcript", "summary", "refresh_token");

  @Test
  void migrationCreatesAllTables() {
    for (String table : TABLES) {
      Integer n =
          jdbc.queryForObject(
              "select count(*) from information_schema.tables where table_schema='public' and table_name=?",
              Integer.class,
              table);
      assertThat(n).as("table %s exists", table).isEqualTo(1);
    }
    for (String table : USER_OWNED) {
      Integer col =
          jdbc.queryForObject(
              "select count(*) from information_schema.columns where table_name=? and column_name='user_id'",
              Integer.class,
              table);
      assertThat(col).as("%s.user_id", table).isEqualTo(1);
      Integer idx =
          jdbc.queryForObject(
              "select count(*) from pg_indexes where tablename=? and indexdef like '%(user_id)%'",
              Integer.class, table);
      assertThat(idx).as("index on %s.user_id", table).isGreaterThanOrEqualTo(1);
    }
  }
}
