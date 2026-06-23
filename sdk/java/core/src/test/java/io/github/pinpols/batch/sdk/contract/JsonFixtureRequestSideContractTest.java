package io.github.pinpols.batch.sdk.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.wire.RegisterRequest;
import io.github.pinpols.batch.sdk.wire.RenewRequest;
import io.github.pinpols.batch.sdk.wire.ReportRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lane P(drift guard)— Java 侧的**请求侧**契约断言。
 *
 * <p>{@link JsonFixtureContractTest} 只做静态结构校验;本测真去**构造 SDK 出向请求体**(用真实 wire DTO {@link
 * ReportRequest} / {@link RenewRequest} / {@link RegisterRequest} 序列化),对带 {@code
 * requestBodyIncludes} / {@code requestBodyExcludes} 的 fixture 做深含 / 排除断言。
 *
 * <p>这把 §5 请求侧字段(report 字段名红线 outputs/success:bool 非 output/errorClass/status、
 * partitionInvocationId 贯穿)从「只活在文档 / 服务端测」推广到 Java 共享-fixture 硬断言,和 TS/Go/Rust 决策核同源。
 *
 * <p>构造逻辑与 TS {@code buildRequest} / Go {@code BuildRequest} / Rust {@code build_request} 一一对应:字段名
 * + NON_NULL 省略对齐平台 wire DTO;apiKey 只进 header 不进 body(header 由 {@code PlatformHttpClient} 加,本测覆盖
 * body 侧红线,header 正则断言由决策核语言覆盖)。
 */
class JsonFixtureRequestSideContractTest {

  private static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  private static Path repoRoot() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isDirectory(p.resolve("docs/api"))) {
        return p;
      }
    }
    return Paths.get("").toAbsolutePath();
  }

  private static Path fixturesDir() {
    return repoRoot().resolve("docs/api/sdk-contract-fixtures");
  }

  /** Only fixtures whose then.expect carries a body-side request assertion. */
  static Stream<Arguments> requestSideFixtures() throws IOException {
    Path dir = fixturesDir();
    assertThat(dir).as("fixtures dir must exist").isDirectory();
    List<Arguments> out = new ArrayList<>();
    try (var stream = Files.list(dir)) {
      stream
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .filter(p -> !p.getFileName().toString().equals("fixture-schema.json"))
          .sorted()
          .forEach(
              p -> {
                JsonNode expect = readExpect(p);
                if (expect != null
                    && (expect.has("requestBodyIncludes") || expect.has("requestBodyExcludes"))) {
                  out.add(Arguments.of(p.getFileName().toString(), p));
                }
              });
    }
    assertThat(out)
        .as("expect >= 1 request-side fixture (report red-line / partitionInvocationId)")
        .isNotEmpty();
    return out.stream();
  }

  private static JsonNode readExpect(Path p) {
    try {
      return JSON.readTree(p.toFile()).path("then").path("expect");
    } catch (IOException e) {
      throw new RuntimeException("read fixture " + p, e);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("requestSideFixtures")
  void outgoingBodyMatchesContract(String name, Path path) throws IOException {
    JsonNode doc = JSON.readTree(path.toFile());
    JsonNode config = doc.path("given").path("config");
    JsonNode spec = doc.path("given").path("state").path("request");
    assertThat(spec.isObject())
        .as("%s: request-side fixture needs given.state.request", name)
        .isTrue();

    JsonNode body = JSON.valueToTree(buildBody(spec, config));
    JsonNode expect = doc.path("then").path("expect");

    JsonNode includes = expect.get("requestBodyIncludes");
    if (includes != null) {
      assertDeepIncludes(name, body, includes);
    }
    JsonNode excludes = expect.get("requestBodyExcludes");
    if (excludes != null) {
      for (JsonNode key : excludes) {
        assertThat(body.has(key.asText()))
            .as("%s: outgoing body must NOT contain key '%s' (body=%s)", name, key.asText(), body)
            .isFalse();
      }
    }
  }

  /** Build the outgoing body via the real wire DTO, mirroring the other runners. */
  private static Object buildBody(JsonNode spec, JsonNode config) {
    String tenantId = config.path("tenantId").asText(null);
    String workerCode = config.path("workerCode").asText(null);
    String kind = spec.path("kind").asText();
    String inv =
        spec.hasNonNull("partitionInvocationId")
            ? spec.get("partitionInvocationId").asText()
            : null;

    switch (kind) {
      case "register":
        return new RegisterRequest(
            tenantId,
            workerCode,
            "sdk-self-hosted",
            "RUNNING",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
      case "claim":
      case "renew":
        return new RenewRequest(tenantId, workerCode, inv);
      case "report":
        JsonNode r = spec.path("report");
        Long taskId = spec.hasNonNull("taskId") ? spec.get("taskId").asLong() : null;
        boolean success = r.path("success").asBoolean(false);
        String errorCode = r.hasNonNull("errorCode") ? r.get("errorCode").asText() : null;
        String resultSummary =
            r.hasNonNull("resultSummary") ? r.get("resultSummary").asText() : null;
        String failureClass = r.hasNonNull("failureClass") ? r.get("failureClass").asText() : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs =
            r.has("outputs") ? JSON.convertValue(r.get("outputs"), Map.class) : null;
        return new ReportRequest(
            taskId,
            tenantId,
            workerCode,
            null,
            success,
            null,
            null,
            resultSummary,
            errorCode,
            null,
            outputs,
            inv,
            failureClass,
            null);
      default:
        throw new IllegalArgumentException("unknown request kind: " + kind);
    }
  }

  /** Deep-subset assertion: every key/value in `subset` present and equal in `actual`. */
  private static void assertDeepIncludes(String name, JsonNode actual, JsonNode subset) {
    Iterator<Map.Entry<String, JsonNode>> it = subset.properties().iterator();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      JsonNode got = actual.get(e.getKey());
      assertThat(got)
          .as("%s: outgoing body missing key '%s' (body=%s)", name, e.getKey(), actual)
          .isNotNull();
      assertThat(jsonValueEquals(got, e.getValue()))
          .as("%s: body['%s'] mismatch — got %s, want %s", name, e.getKey(), got, e.getValue())
          .isTrue();
    }
  }

  /**
   * Value equality tolerant of numeric node type (IntNode vs LongNode) — the wire DTO's {@code
   * taskId} is a Long while the fixture literal parses as int.
   */
  private static boolean jsonValueEquals(JsonNode a, JsonNode b) {
    if (a.isNumber() && b.isNumber()) {
      return a.decimalValue().compareTo(b.decimalValue()) == 0;
    }
    return a.equals(b);
  }
}
