package com.example.batch.sdk.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lane P (drift guard): Java side of the language-agnostic contract-fixture runner.
 *
 * <p>每个 {@code docs/api/sdk-contract-fixtures/*.json} 跑一个 parameterized 用例,**只验静态契约**:
 *
 * <ul>
 *   <li>fixture 结构(三段 scenario / given / when / then)
 *   <li>HTTP fixture 的 {@code (method, path)} 必须在 {@code orchestrator-internal.openapi.yaml} 已声明
 *       (路径模板 {@code /workers/{workerCode}} 等做 regex 匹配)
 *   <li>{@code sdkExpectedAction} 非空 + 字符集合法
 * </ul>
 *
 * <p>**不做的事**(留后续 P5+ follow-up):
 *
 * <ul>
 *   <li>真起 SDK 跑业务逻辑(mock server / mock kafka consumer)— 需要 testkit + JDK HttpClient 抽象,本 lane 不展开
 *   <li>responseBody 与 OpenAPI schema 的深度字段比对(swagger-parser 引入成本高,先靠 fixture 自校验 + 人工评审兜底)
 * </ul>
 *
 * <p>与 Python {@code sdk-python/tests/contract/test_contract_runner.py} 是镜像关系: Python 当前全
 * xfail,Java 当前应全 PASS(因为只验描述性契约,不执行行为)。
 */
class JsonFixtureContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final YAMLMapper YAML = new YAMLMapper();

  /** 允许的 sdkExpectedAction 关键字(动词级,人类可读描述包含其一即合法)。 */
  private static final Set<String> ACTION_KEYWORDS =
      Set.of(
          "transition",
          "start",
          "stop",
          "pause",
          "resume",
          "drain",
          "deactivate",
          "fail-fast",
          "retry",
          "backoff",
          "cancel",
          "log",
          "schedule",
          "subscribe",
          "process",
          "commit",
          "call",
          "maintain",
          "update",
          "adjust",
          "restore",
          "no-op");

  /** 从测试 cwd(module 目录)向上找含 {@code docs/api} 的仓库根;与 module 路径深度无关(模块在 sdk/java)。 */
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

  private static Path openapiPath() {
    return repoRoot().resolve("docs/api/orchestrator-internal.openapi.yaml");
  }

  static Stream<Arguments> fixtureProvider() throws IOException {
    Path dir = fixturesDir();
    assertThat(dir).as("Lane N fixtures directory must exist (was it merged?)").isDirectory();
    List<Arguments> out = new ArrayList<>();
    try (var stream = Files.list(dir)) {
      stream
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .filter(p -> !p.getFileName().toString().equals("fixture-schema.json"))
          .sorted()
          .forEach(p -> out.add(Arguments.of(p.getFileName().toString(), p)));
    }
    assertThat(out).as("expect >= 12 fixtures published by Lane N").hasSizeGreaterThanOrEqualTo(12);
    return out.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtureProvider")
  void fixtureMatchesContract(String name, Path path) throws IOException {
    JsonNode doc = JSON.readTree(path.toFile());

    // ── given / when / then 三段必备 ──────────────────────────────────────────
    assertThat(doc.has("scenario")).as("%s missing scenario", name).isTrue();
    assertThat(doc.has("description")).as("%s missing description", name).isTrue();
    assertThat(doc.has("given")).as("%s missing given", name).isTrue();
    assertThat(doc.has("when")).as("%s missing when", name).isTrue();
    assertThat(doc.has("then")).as("%s missing then", name).isTrue();

    JsonNode when = doc.get("when");
    String channel = when.path("channel").asText();
    assertThat(channel).as("%s when.channel", name).isIn("http", "kafka");
    String method = when.path("method").asText();
    assertThat(method)
        .as("%s when.method", name)
        .isIn("GET", "POST", "PUT", "DELETE", "PATCH", "RECEIVE");

    JsonNode then = doc.get("then");
    String action = then.path("sdkExpectedAction").asText();
    assertThat(action).as("%s then.sdkExpectedAction empty", name).isNotBlank();
    String actionLower = action.toLowerCase(Locale.ROOT);
    boolean matchesKeyword = ACTION_KEYWORDS.stream().anyMatch(actionLower::contains);
    assertThat(matchesKeyword)
        .as(
            "%s then.sdkExpectedAction='%s' must mention at least one allowed verb %s",
            name, action, ACTION_KEYWORDS)
        .isTrue();

    // ── HTTP fixture:(method, path) 必须在 OpenAPI 已声明 ──────────────────
    if ("http".equals(channel)) {
      String fixturePath = when.path("path").asText();
      assertThat(fixturePath)
          .as("%s when.path must be /internal/...", name)
          .startsWith("/internal/");
      assertOpenApiDeclares(name, method, fixturePath);

      JsonNode status = when.get("responseStatus");
      assertThat(status != null && status.isInt()).as("%s responseStatus int", name).isTrue();
      int sc = status.asInt();
      assertThat(sc).as("%s responseStatus in 1xx..5xx", name).isBetween(100, 599);
    }
  }

  /**
   * 把 fixture 的具体 path(如 {@code /internal/workers/w-1/heartbeat})对照 OpenAPI 的路径模板 ({@code
   * /internal/workers/{workerCode}/heartbeat})做 regex 匹配。
   */
  private static void assertOpenApiDeclares(String name, String method, String fixturePath)
      throws IOException {
    JsonNode root = YAML.readTree(openapiPath().toFile());
    JsonNode paths = root.get("paths");
    assertThat(paths).as("openapi paths").isNotNull();

    String methodLower = method.toLowerCase(Locale.ROOT);
    Iterator<Map.Entry<String, JsonNode>> it = paths.fields();
    boolean matched = false;
    String matchedTemplate = null;
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> entry = it.next();
      String template = entry.getKey();
      if (!templateMatches(template, fixturePath)) {
        continue;
      }
      if (entry.getValue().has(methodLower)) {
        matched = true;
        matchedTemplate = template;
        break;
      }
    }
    assertThat(matched)
        .as(
            "%s: OpenAPI must declare %s %s (no path template matched, last try=%s)",
            name, method, fixturePath, matchedTemplate)
        .isTrue();
  }

  /** Turn {@code /internal/workers/{workerCode}/heartbeat} into a regex and match concrete path. */
  private static boolean templateMatches(String template, String concrete) {
    String regex = template.replaceAll("\\{[^/]+}", "[^/]+");
    return Pattern.matches(regex, concrete);
  }
}
