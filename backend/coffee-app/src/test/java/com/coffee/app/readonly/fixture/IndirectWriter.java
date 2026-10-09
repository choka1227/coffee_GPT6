package com.coffee.app.readonly.fixture;

import org.springframework.jdbc.core.JdbcTemplate;

public final class IndirectWriter {
  private final JdbcTemplate jdbc;

  public IndirectWriter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void entry() {
    middle();
  }

  private void middle() {
    tail();
  }

  private void tail() {
    jdbc.execute("update x set y=1");
  }
}
