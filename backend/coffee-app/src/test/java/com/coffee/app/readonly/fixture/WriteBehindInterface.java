package com.coffee.app.readonly.fixture;

import org.springframework.jdbc.core.JdbcTemplate;

public final class WriteBehindInterface {
  private final Sink sink;

  public WriteBehindInterface(Sink sink) {
    this.sink = sink;
  }

  public void entry() {
    sink.write();
  }

  public interface Sink {
    void write();
  }

  public static final class SinkImpl implements Sink {
    private final JdbcTemplate jdbc;

    public SinkImpl(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public void write() {
      jdbc.update("update x set y=1");
    }
  }
}
