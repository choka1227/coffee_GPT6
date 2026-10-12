package com.coffee.app.ecpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EcpayNoEgressGuardTest {
  private static final Set<String> PRODUCTION_LITERAL_ALLOWLIST =
      Set.of(
          "EcpayStageGuardTest.java",
          "FakeEcpayServer.java",
          "EcpayStageEndToEndTest.java",
          "EcpayNoEgressGuardTest.java");

  @Test
  void testsUsingEcpayStageProfileMustOverrideQueryUrl() throws IOException {
    assertThat(unsafeProfilePaths(javaTests())).isEmpty();
  }

  @Test
  void productionHostLiteralIsRestrictedToExplicitGuardTests() throws IOException {
    try (var files = javaTests()) {
      assertThat(
              files
                  .filter(path -> !PRODUCTION_LITERAL_ALLOWLIST.contains(path.getFileName().toString()))
                  .filter(path -> read(path).contains("payment.ecpay.com.tw"))
                  .toList())
          .isEmpty();
    }
  }

  @Test
  void ruleDetectsEmbeddedUnsafeSample() {
    assertThat(unsafeProfileUsers(Stream.of(new Source("Unsafe.java", "@ActiveProfiles(\"ecpay-stage\")"))))
        .containsExactly("Unsafe.java");
  }

  private static List<String> unsafeProfilePaths(Stream<Path> paths) {
    try (paths) {
      return unsafeProfileUsers(paths.map(path -> new Source(path.toString(), read(path))));
    }
  }

  private static List<String> unsafeProfileUsers(Stream<Source> sources) {
    try (sources) {
      return sources
          .filter(source -> source.content().contains("ecpay-stage"))
          .filter(source -> !source.content().contains("ecpay.query-url"))
          .map(Source::name)
          .toList();
    }
  }

  private static Stream<Path> javaTests() throws IOException {
    Path backend = Path.of("..");
    if (!Files.isDirectory(backend.resolve("coffee-app"))) backend = Path.of("backend");
    return Files.walk(backend)
        .filter(path -> path.toString().contains("src/test"))
        .filter(path -> path.toString().endsWith(".java"));
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private record Source(String name, String content) {}
}
