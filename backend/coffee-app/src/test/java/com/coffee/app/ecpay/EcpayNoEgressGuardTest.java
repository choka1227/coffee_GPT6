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

  private static final Set<String> INVOICE_PRODUCTION_ALLOWLIST = Set.of(
      "EinvoiceStageGuardTest.java", "FakeEinvoiceServer.java", "EcpayNoEgressGuardTest.java");

  @Test
  void invoiceProductionLiteralOnlyAppearsInExplicitGuardTests() throws IOException {
    try (var paths = javaTests()) {
      assertThat(unsafeInvoiceLiterals(paths.map(p -> new Source(p.getFileName().toString(), read(p)))))
          .isEmpty();
    }
  }

  @Test
  void productionInvoiceAndPaymentHostsNeverAppearInMainSources() throws IOException {
    Path backend = Path.of("..");
    if (!Files.isDirectory(backend.resolve("coffee-app"))) backend = Path.of("backend");
    try (var paths = Files.walk(backend)) {
      assertThat(unsafeMainLiterals(paths.filter(Files::isRegularFile)
          .filter(p -> p.toString().contains("src/main"))
          .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".yml"))
          .map(p -> new Source(p.toString(), read(p))))).isEmpty();
    }
  }

  @Test
  void stageProfileTestsAlsoOverrideInvoiceBaseUrl() throws IOException {
    try (var paths = javaTests()) {
      assertThat(unsafeInvoiceProfiles(paths.map(p -> new Source(p.toString(), read(p)))))
          .isEmpty();
    }
  }

  @Test
  void invoiceRulesRejectEmbeddedUnsafeSamples() {
    assertThat(unsafeInvoiceLiterals(Stream.of(new Source("Unsafe.java", "einvoice.ecpay.com.tw"))))
        .containsExactly("Unsafe.java");
    assertThat(unsafeMainLiterals(Stream.of(new Source("application.yml", "einvoice.ecpay.com.tw"))))
        .containsExactly("application.yml");
    assertThat(unsafeMainLiterals(Stream.of(new Source("Unsafe.java", "payment.ecpay.com.tw"))))
        .containsExactly("Unsafe.java");
    assertThat(unsafeInvoiceProfiles(Stream.of(new Source("Unsafe.java",
        "@ActiveProfiles(\"ecpay-stage\") ecpay.query-url")))).containsExactly("Unsafe.java");
  }

  private static List<String> unsafeInvoiceLiterals(Stream<Source> sources) {
    try (sources) {
      return sources.filter(s -> !INVOICE_PRODUCTION_ALLOWLIST.contains(s.name()))
          .filter(s -> s.content().contains("einvoice.ecpay.com.tw")).map(Source::name).toList();
    }
  }

  private static List<String> unsafeMainLiterals(Stream<Source> sources) {
    try (sources) {
      return sources.filter(s -> s.content().contains("einvoice.ecpay.com.tw")
          || s.content().contains("payment.ecpay.com.tw")).map(Source::name).toList();
    }
  }

  private static List<String> unsafeInvoiceProfiles(Stream<Source> sources) {
    try (sources) {
      return sources.filter(s -> s.content().contains("ecpay-stage"))
          .filter(s -> !s.content().contains("ecpay.invoice.base-url")).map(Source::name).toList();
    }
  }
}
