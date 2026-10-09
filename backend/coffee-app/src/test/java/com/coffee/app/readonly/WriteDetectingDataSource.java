package com.coffee.app.readonly;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Test-only data source that records SQL writes issued while the current thread is armed. */
public final class WriteDetectingDataSource extends DelegatingDataSource {
  private static final Set<String> WRITE_VERBS = Set.of(
      "insert", "update", "delete", "merge", "truncate", "alter", "create", "drop",
      "grant", "revoke", "replace");
  private static final Set<String> CTE_WRITE_VERBS = Set.of("insert", "update", "delete", "merge");
  private static final ThreadLocal<List<String>> WRITES = new ThreadLocal<>();

  public WriteDetectingDataSource(DataSource target) {
    super(target);
  }

  public static void arm() {
    WRITES.set(new ArrayList<>());
  }

  public static List<String> disarm() {
    List<String> writes = WRITES.get();
    WRITES.remove();
    return writes == null ? List.of() : List.copyOf(writes);
  }

  public static <T> Captured<T> around(Supplier<T> call) {
    arm();
    try {
      T value = call.get();
      return new Captured<>(value, disarm());
    } finally {
      WRITES.remove();
    }
  }

  public record Captured<T>(T value, List<String> writes) {}

  @Override
  public Connection getConnection() throws SQLException {
    return connection(super.getConnection());
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return connection(super.getConnection(username, password));
  }

  public static boolean isWrite(String sql) {
    if (sql == null) return false;
    String candidate = stripLeadingNoise(sql);
    if (candidate.isBlank()) return false;

    String first = candidate.split("[\\s()]", 2)[0].toLowerCase(Locale.ROOT);
    if (WRITE_VERBS.contains(first)) return true;

    String sanitized = stripQuotedTextAndComments(candidate).toLowerCase(Locale.ROOT);
    if (first.equals("with")) {
      for (String token : sanitized.split("[^a-z0-9_]+")) {
        if (CTE_WRITE_VERBS.contains(token)) return true;
      }
    }

    String normalized = sanitized.replaceAll("\\s+", " ");
    return normalized.contains(" for update")
        || normalized.contains(" for no key update")
        || normalized.contains(" for share");
  }

  private static Connection connection(Connection target) {
    return (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(),
        new Class<?>[] {Connection.class},
        (proxy, method, args) -> {
          String name = method.getName();
          if ((name.equals("prepareStatement") || name.equals("prepareCall")
                  || name.equals("nativeSQL"))
              && args != null && args.length > 0 && args[0] instanceof String sql) {
            record(sql);
          }
          Object result = invoke(target, method, args);
          if (result instanceof Statement statement
              && (name.equals("prepareStatement") || name.equals("prepareCall")
                  || name.equals("createStatement"))) {
            return statement(statement);
          }
          return result;
        });
  }

  private static Statement statement(Statement target) {
    Class<?> statementType = target instanceof CallableStatement
        ? CallableStatement.class
        : target instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
    return (Statement) Proxy.newProxyInstance(
        Statement.class.getClassLoader(),
        new Class<?>[] {statementType},
        (proxy, method, args) -> {
          String name = method.getName();
          if ((name.equals("execute") || name.equals("executeQuery")
                  || name.equals("executeUpdate") || name.equals("executeLargeUpdate")
                  || name.equals("addBatch"))
              && args != null && args.length > 0 && args[0] instanceof String sql) {
            record(sql);
          }
          return invoke(target, method, args);
        });
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static void record(String sql) {
    List<String> writes = WRITES.get();
    if (writes != null && isWrite(sql)) writes.add(sql);
  }

  private static String stripLeadingNoise(String sql) {
    String value = sql.trim();
    boolean changed;
    do {
      changed = false;
      if (value.startsWith("/*")) {
        int end = value.indexOf("*/", 2);
        if (end < 0) return "";
        value = value.substring(end + 2).trim();
        changed = true;
      } else if (value.startsWith("--")) {
        int end = value.indexOf('\n', 2);
        if (end < 0) return "";
        value = value.substring(end + 1).trim();
        changed = true;
      }
      while (value.startsWith("(")) {
        value = value.substring(1).trim();
        changed = true;
      }
    } while (changed);
    return value;
  }

  private static String stripQuotedTextAndComments(String sql) {
    StringBuilder clean = new StringBuilder(sql.length());
    for (int i = 0; i < sql.length();) {
      char current = sql.charAt(i);
      if (current == '\'' || current == '"') {
        char quote = current;
        clean.append(' ');
        i++;
        while (i < sql.length()) {
          char c = sql.charAt(i++);
          if (c == quote) {
            if (i < sql.length() && sql.charAt(i) == quote) {
              i++;
            } else {
              break;
            }
          }
        }
      } else if (current == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
        int end = sql.indexOf("*/", i + 2);
        i = end < 0 ? sql.length() : end + 2;
        clean.append(' ');
      } else if (current == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
        int end = sql.indexOf('\n', i + 2);
        i = end < 0 ? sql.length() : end + 1;
        clean.append(' ');
      } else {
        clean.append(current);
        i++;
      }
    }
    return clean.toString();
  }
}
