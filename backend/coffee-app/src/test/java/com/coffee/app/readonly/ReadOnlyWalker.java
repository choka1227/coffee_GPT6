package com.coffee.app.readonly;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReadOnlyWalker {
  static final Set<String> READ_METHODS =
      Set.of(
          "query",
          "queryForList",
          "queryForObject",
          "queryForMap",
          "queryForStream",
          "queryForRowSet");

  private static final Set<String> JDBC_TEMPLATES =
      Set.of(
          "org.springframework.jdbc.core.JdbcTemplate",
          "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate");

  private static final Set<String> RAW_JDBC_TYPES =
      Set.of(
          "javax.sql.DataSource",
          "java.sql.Connection",
          "java.sql.Statement",
          "java.sql.PreparedStatement",
          "java.sql.CallableStatement");

  private ReadOnlyWalker() {}

  public static List<String> violations(JavaClasses classes, Collection<JavaMethod> entryPoints) {
    var queue = new ArrayDeque<Path>();
    entryPoints.forEach(method -> queue.add(new Path(method, List.of(method.getFullName()))));

    var visited = new HashSet<String>();
    var violations = new ArrayList<String>();
    while (!queue.isEmpty()) {
      var path = queue.remove();
      if (!visited.add(path.method().getFullName())) continue;

      for (JavaMethodCall call : path.method().getMethodCallsFromSelf()) {
        var target = call.getTarget();
        var owner = target.getOwner();
        var nextChain = append(path.chain(), target.getFullName());

        if (JDBC_TEMPLATES.contains(owner.getName())) {
          if (!READ_METHODS.contains(target.getName())) violations.add(format(nextChain));
          continue;
        }
        if (RAW_JDBC_TYPES.contains(owner.getName())) {
          violations.add(format(nextChain));
          continue;
        }
        if (!owner.getPackageName().startsWith("com.coffee")) continue;

        if (owner.isInterface()) {
          classes.stream()
              .filter(candidate -> !candidate.isInterface())
              .filter(candidate -> candidate.isAssignableTo(owner.getName()))
              .flatMap(candidate -> candidate.getMethods().stream())
              .filter(
                  method ->
                      matches(method, target.getName(), target.getRawParameterTypes().getNames()))
              .forEach(
                  method ->
                      queue.add(new Path(method, append(path.chain(), method.getFullName()))));
        } else {
          target.resolveMember().stream().forEach(method -> queue.add(new Path(method, nextChain)));
        }
      }
    }
    return List.copyOf(violations);
  }

  private static boolean matches(JavaMethod method, String name, List<String> parameterTypes) {
    return method.getName().equals(name)
        && method.getRawParameterTypes().getNames().equals(parameterTypes);
  }

  private static List<String> append(List<String> chain, String element) {
    var result = new ArrayList<>(chain);
    result.add(element);
    return List.copyOf(result);
  }

  private static String format(List<String> chain) {
    return "唯讀路徑出現寫入：" + String.join("\n  → ", chain);
  }

  private record Path(JavaMethod method, List<String> chain) {}
}
