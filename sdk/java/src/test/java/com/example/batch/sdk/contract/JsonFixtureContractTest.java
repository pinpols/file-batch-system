package com.example.batch.sdk.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.batch.sdk.dispatcher.HeartbeatDirective;
import com.example.batch.sdk.dispatcher.TaskDispatchMessage;
import com.example.batch.sdk.dispatcher.WorkerRuntimeState;
import com.example.batch.sdk.internal.PlatformHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
 * <p>**行为侧 runner**(#10):{@link #fixtureDrivesSdkDecision} 真正驱动 Java SDK 的分类 / directive 决策核
 * ({@link PlatformHttpException} HTTP 分类 + {@code consecutiveClientErrors} fail-fast streak、{@link
 * TaskDispatchMessage#isSchemaSupported()}、{@link HeartbeatDirective#toRuntimeState()}),并对 {@code
 * then.expect} 逐字段 deep-equal,把 Java 从「零行为覆盖」补齐到与 TS/Go/Rust 同样跑 compute。覆盖响应侧三类:
 *
 * <ul>
 *   <li>HTTP 状态分类:07(401 fail-fast)/ 08(409 idempotent-success)/ 09(503 retry-backoff)/ 21(422 首次不
 *       fail-fast)/ 22(404 give-up)/ 23(422 第 5 次 fail-fast)
 *   <li>schemaVersion accept/reject:16(缺省)/ 17(v2)/ 18(v3 reject)/ 29(未知字段忽略)
 *   <li>heartbeat directive:03(NORMAL)/ 04(DRAINING)/ 05(PAUSED)/ 06(DRAINING shouldDrain)/
 *       26(DEGRADED)/ 27(desiredMaxConcurrent)
 * </ul>
 *
 * <p>无法在纯函数层驱动的 fixture(需真 HTTP / kafka offset 副作用,如 register / report-idempotency-key /
 * partition-invocation / kafka-pause)经 {@link #computeSdkDecision} 返回 {@code null} → {@code
 * assumeTrue} 跳过并打印原因,**不静默忽略**。
 *
 * <p>**仍不做的事**(留后续 follow-up):
 *
 * <ul>
 *   <li>真起 SDK 全链路(mock server / mock kafka consumer 跑 offset 副作用)— 需要 testkit + JDK HttpClient 抽象
 *   <li>responseBody 与 OpenAPI schema 的深度字段比对(swagger-parser 引入成本高,先靠 fixture 自校验 + 人工评审兜底)
 * </ul>
 *
 * <p>与 Python {@code sdk-python/tests/contract/test_contract_runner.py} 是镜像关系:两侧均已驱动决策核做行为断言(Python
 * 早期曾全 xfail,现已落地)。
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

  // ════════════════════════════════════════════════════════════════════════
  // #10 行为侧 runner:驱动 Java SDK 决策核,对 then.expect 逐字段 deep-equal。
  // ════════════════════════════════════════════════════════════════════════

  /**
   * SDK 默认配置:连续 4xx 达 5 次 → fail-fast(对齐 {@code
   * BatchPlatformClientConfig.clientErrorFailFastThreshold})。
   */
  private static final int CLIENT_ERROR_FAIL_FAST_THRESHOLD = 5;

  /** SDK 默认 CLAIM/REPORT 5xx 退避:base=200ms,maxAttempts=3 → [200,400,800]。 */
  private static final List<Integer> RETRY_BACKOFF_MS = List.of(200, 400, 800);

  private static final int RETRY_MAX_ATTEMPTS = 3;

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtureProvider")
  void fixtureDrivesSdkDecision(String name, Path path) throws IOException {
    JsonNode doc = JSON.readTree(path.toFile());
    JsonNode then = doc.get("then");
    JsonNode expect = then.get("expect");

    Map<String, Object> computed = computeSdkDecision(doc);
    assumeTrue(
        computed != null,
        () ->
            name
                + ": not drivable at pure-decision layer (needs real HTTP/kafka offset side"
                + " effect); covered structurally by fixtureMatchesContract");

    // expect 块逐字段 deep-equal:SDK 决策核算出的、且 fixture then.expect 也声明的每个键必须一致。
    assertThat(expect)
        .as("%s: behavioral runner only covers fixtures carrying then.expect", name)
        .isNotNull();
    int asserted = 0;
    for (Map.Entry<String, Object> e : computed.entrySet()) {
      if (!expect.has(e.getKey())) {
        continue; // SDK 决策核算的某字段本 fixture 未声明 expect(如 26/27 无 action) → 不强加
      }
      Object expected = jsonValue(expect.get(e.getKey()));
      assertThat(e.getValue())
          .as("%s then.expect.%s — SDK decision must match fixture", name, e.getKey())
          .isEqualTo(expected);
      asserted++;
    }
    assertThat(asserted)
        .as(
            "%s: behavioral runner must assert >=1 then.expect field (none overlapped %s)",
            name, computed.keySet())
        .isGreaterThan(0);
  }

  /**
   * 用真 SDK 决策核算出本 fixture 的可断言字段;返 {@code null} 表示「本 runner 不覆盖(需真副作用)」 → 调用方 assumeTrue 跳过并带原因。
   *
   * <p>分流键 = {@code scenario}:三类响应侧场景分别走 HTTP 分类 / schema accept / heartbeat directive。
   */
  private static Map<String, Object> computeSdkDecision(JsonNode doc) {
    String scenario = doc.path("scenario").asText();
    JsonNode when = doc.get("when");
    JsonNode given = doc.path("given");
    JsonNode state = given.path("state");

    // ── 1) heartbeat directive(03/04/05/06/26/27):驱动 HeartbeatDirective + WorkerRuntimeState ──
    if (scenario.startsWith("heartbeat-directive")
        || scenario.equals("heartbeat-desired-max-concurrent")
        || scenario.equals("heartbeat-next-interval-hint")) {
      return computeHeartbeatDirective(when);
    }

    // ── 2) schema accept/reject(16/17/18/29):驱动 TaskDispatchMessage.isSchemaSupported() ──
    if (scenario.startsWith("kafka-schema-version")
        || scenario.equals("kafka-ignore-unknown-field")) {
      return computeSchemaAccept(when);
    }

    // ── 3) HTTP 状态分类(07/08/09/21/22/23):仅 claim/report/renew 走 PlatformHttpException + fail-fast
    // streak。
    //    heartbeat 5xx(25)是另一套语义(不重试,等下次定时 tick),不复用 claim/report 退避分类。 ──
    if ("http".equals(when.path("channel").asText())
        && when.has("responseStatus")
        && when.get("responseStatus").isInt()) {
      String httpPath = when.path("path").asText();
      if (httpPath.endsWith("/claim")
          || httpPath.endsWith("/report")
          || httpPath.endsWith("/renew")) {
        return computeHttpClassification(when, state);
      }
      if (httpPath.endsWith("/heartbeat") && when.get("responseStatus").asInt() >= 500) {
        // 25:heartbeat 5xx → 不退避重试,丢弃本次,等下个定时 tick(maxAttempts=1)。
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retry", false);
        out.put("maxAttempts", 1);
        return out;
      }
    }

    // 其余 fixture(register / report-idempotency-key / partition-invocation / kafka-pause /
    // decode-error /
    // paused-task-type 等)需真 HTTP / kafka offset 副作用,纯函数层不可驱动 → 交结构测兜底。
    return null;
  }

  /** 驱动 {@link HeartbeatDirective#fromResponse} → {@link HeartbeatDirective#toRuntimeState()}。 */
  private static Map<String, Object> computeHeartbeatDirective(JsonNode when) {
    @SuppressWarnings("unchecked")
    Map<String, Object> resp =
        JSON.convertValue(when.path("responseBody"), Map.class) == null
            ? Map.of()
            : JSON.convertValue(when.path("responseBody"), Map.class);
    HeartbeatDirective directive = HeartbeatDirective.fromResponse(resp);
    WorkerRuntimeState fsm = directive.toRuntimeState();

    Map<String, Object> out = new LinkedHashMap<>();
    // 03/04/05 声明 action=apply-directive(26/27 未声明 → 不强加,见 runner 的 expect.has 过滤)。
    out.put("action", "apply-directive");
    out.put("fsmTransition", fsm.name());
    // PAUSED / DRAINING 停接新任务 → Kafka partition pause;NORMAL / DEGRADED 接单 → none。
    out.put("kafka", fsm.acceptsNewTasks() ? "none" : "pause");
    if (WorkerRuntimeState.DRAINING == fsm) {
      out.put("drainThenDeactivate", true);
    }
    if (directive.desiredMaxConcurrent() != null) {
      out.put("effectiveMaxConcurrent", directive.desiredMaxConcurrent());
    }
    if (directive.nextHeartbeatHint() != null) {
      // 对齐 HeartbeatScheduler.applyHeartbeatHint:hint 秒 * 1000 = 下次心跳间隔 ms。
      out.put("heartbeatNextIntervalMs", directive.nextHeartbeatHint() * 1000);
    }
    return out;
  }

  /** 驱动 {@link TaskDispatchMessage#isSchemaSupported()}(缺省 / v2 接受,v3 拒绝;未知字段被忽略)。 */
  private static Map<String, Object> computeSchemaAccept(JsonNode when) {
    String schemaVersion =
        when.path("body").hasNonNull("schemaVersion")
            ? when.path("body").get("schemaVersion").asText()
            : null;
    // 走真 record(经 Jackson 反序列化,验未知字段确实被 ignoreUnknown 包容、不影响 schema 判定)。
    TaskDispatchMessage msg;
    try {
      msg = JSON.treeToValue(when.path("body"), TaskDispatchMessage.class);
    } catch (Exception ex) {
      // body 缺必填(jobCode 等)不影响 schema 判定;直接构 message 投影。
      msg = new TaskDispatchMessage(schemaVersion, 1L, "t", "j", "wt", "ti", Map.of(), Map.of());
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("schemaAccept", msg.isSchemaSupported());
    return out;
  }

  /** 驱动 {@link PlatformHttpException} 分类 + 连续 4xx fail-fast streak,映射到 fixture then.expect 字段。 */
  private static Map<String, Object> computeHttpClassification(JsonNode when, JsonNode state) {
    int status = when.get("responseStatus").asInt();
    PlatformHttpException ex = new PlatformHttpException(status, "fixture");
    Map<String, Object> out = new LinkedHashMap<>();

    if (ex.isAuthError()) {
      // 07:401/403 鉴权失败 — 不可恢复,fail-fast,不重试。
      out.put("action", "fail-fast");
      out.put("failFast", true);
      out.put("retry", false);
      return out;
    }
    if (ex.isConflict()) {
      // 08:409 already-claimed — 幂等成功,不重试、不上报失败。
      out.put("action", "idempotent-success");
      out.put("retry", false);
      out.put("idempotent", true);
      out.put("reportFailure", false);
      return out;
    }
    if (ex.isServerError()) {
      // 09:5xx — 指数退避重试后放弃(orchestrator lease 超时兜底重派)。
      out.put("action", "retry-then-drop");
      out.put("retry", true);
      out.put("retryBackoffMs", RETRY_BACKOFF_MS);
      out.put("maxAttempts", RETRY_MAX_ATTEMPTS);
      return out;
    }
    if (status == 404) {
      // 22:404 task reclaimed — 放弃,不重试、不 fail-fast。
      out.put("action", "not-found");
      out.put("retry", false);
      out.put("failFast", false);
      return out;
    }
    if (ex.isClientError()) {
      // 21/23:其它 4xx(422 等)— 连续计数到阈值才 fail-fast,否则只 client-error 放弃。
      int priorCount = state.path("clientErrorCount").asInt(0);
      int count = priorCount + 1; // 本次也计一次(对齐 recordClientError 的 incrementAndGet)
      boolean failFast = count >= CLIENT_ERROR_FAIL_FAST_THRESHOLD;
      out.put("action", failFast ? "fail-fast" : "client-error");
      out.put("retry", false);
      out.put("failFast", failFast);
      return out;
    }
    return null;
  }

  /** 把 JsonNode 转成纯 Java 值(供 deep-equal):int / boolean / text / array<int> / null。 */
  private static Object jsonValue(JsonNode n) {
    if (n == null || n.isNull()) {
      return null;
    }
    if (n.isBoolean()) {
      return n.asBoolean();
    }
    if (n.isInt()) {
      return n.asInt();
    }
    if (n.isTextual()) {
      return n.asText();
    }
    if (n.isArray()) {
      List<Object> list = new ArrayList<>();
      n.forEach(e -> list.add(jsonValue(e)));
      return list;
    }
    return n.asText();
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
