package com.coffee.app.readonly.fixture;

import org.springframework.jdbc.core.JdbcTemplate;

public final class DirectWriter {
  private final JdbcTemplate jdbc;

  public DirectWriter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void entry() {
    jdbc.update("update x set y=1");
  }
}
