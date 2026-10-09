package com.coffee.app.readonly.fixture;

import org.springframework.jdbc.core.JdbcTemplate;

public final class CleanReader {
  private final JdbcTemplate jdbc;

  public CleanReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void entry() {
    jdbc.query("select 1", (rs, rowNum) -> rs.getInt(1));
    jdbc.queryForObject("select 1", Integer.class);
  }
}
