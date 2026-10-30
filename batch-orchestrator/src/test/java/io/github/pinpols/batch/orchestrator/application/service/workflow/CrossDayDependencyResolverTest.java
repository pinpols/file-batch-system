package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver.ResolutionResult;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

@DisplayName("跨天依赖解析器: 生效版本注入, 依赖缺失等待与失败态口径")
class CrossDayDependencyResolverTest {

  private ResultVersionQueryService queryService;
  private CrossDayDependencyResolver resolver;

  @BeforeEach
  void setUp() {
    queryService = mock(ResultVersionQueryService.class);
    resolver = new CrossDayDependencyResolver(new BizDateArithmetic(), queryService);
  }

  @Test
  @DisplayName("未配置跨天依赖时判定为已解析, 且不注入任何上游产出")
  void shouldResolve_whenDependencyConfigEmpty() {
    var result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), null, false);
    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("有跨日依赖时缺少租户或业务日，普通和冻结解析均失败关闭")
  void shouldFailMissingContext_whenDependencyConfigured(boolean missingTenant) {
    String tenant = missingTenant ? " " : "t1";
    LocalDate bizDate = missingTenant ? LocalDate.of(2026, 5, 4) : null;
    String json = "[{\"alias\":\"previous\",\"jobCode\":\"DAILY\",\"bizDateOffset\":-1}]";

    ResolutionResult normal = resolver.resolve(tenant, bizDate, json, false);
    ResolutionResult pinned = resolver.resolvePinned(tenant, bizDate, json, false, Map.of());

    assertThat(normal.isFailed()).isTrue();
    assertThat(normal.getFailureCode()).isEqualTo("CROSS_DAY_DEP_INVALID_CONTEXT");
    assertThat(pinned.isFailed()).isTrue();
    assertThat(pinned.getFailureCode()).isEqualTo("CROSS_DAY_DEP_INVALID_CONTEXT");
    verifyNoInteractions(queryService);
  }

  @Test
  @DisplayName("空跨日依赖数组不需要租户和业务日")
  void shouldResolveEmptyArrayWithoutContext() {
    ResolutionResult result = resolver.resolve(null, null, "[]", false);
    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).isEmpty();
    verifyNoInteractions(queryService);
  }

  @Test
  @DisplayName("按偏移日命中生效版本时注入上游产出, 并带出版本号与状态")
  void shouldInjectPayload_whenOffsetDependencyEffective() {
    String json = "[{\"alias\":\"t_minus_1\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
        + "\"scope\":\"REQUIRED\",\"consumeVersionStrategy\":\"EFFECTIVE_ONLY\"}]";
    ResultVersionEntity hit = ResultVersionEntity.builder()
        .id(77L)
        .versionNo(2)
        .status("EFFECTIVE")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"recordCount\":42}")
        .jobInstanceId(100L)
        .businessKey("job:DAILY_PNL:2026-05-03")
        .build();
    when(queryService.findLatestCrossDayDependencyVersion(
            "t1", "DAILY_PNL", LocalDate.of(2026, Month.MAY, 3), false))
        .thenReturn(Optional.of(hit));

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json, false);

    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).containsKey("t_minus_1");
    @SuppressWarnings("unchecked")
    Map<String, Object> entry = (Map<String, Object>) result.getResolved().get("t_minus_1");
    assertThat(entry).containsEntry("versionNo", 2);
    assertThat(entry).containsEntry("status", "EFFECTIVE");
    assertThat(entry).containsEntry("resultVersionId", 77L);
    assertThat(result.getResultVersionIdsByAlias()).containsEntry("t_minus_1", List.of(77L));
    assertThat(entry.get("output")).isEqualTo(Map.of("recordCount", 42));
    assertThat(entry).doesNotContainKey("payloadJson").doesNotContainKey("payloadRef");
  }

  @Test
  @DisplayName("必需依赖缺失时进入等待状态并给出等待原因")
  void shouldWait_whenRequiredDependencyMissing() {
    String json = "[{\"alias\":\"t_minus_1\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
        + "\"scope\":\"REQUIRED\"}]";
    when(queryService.findLatestCrossDayDependencyVersion(
            "t1", "DAILY_PNL", LocalDate.of(2026, Month.MAY, 3), false))
        .thenReturn(Optional.empty());

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json, false);

    assertThat(result.isWaiting()).isTrue();
    assertThat(result.getWaitingReasons())
        .singleElement()
        .asString()
        .contains("MISSING")
        .contains("DAILY_PNL")
        .contains("2026-05-03");
  }

  @Test
  @DisplayName("可选依赖缺失时仍判定为已解析, 不注入对应上游产出")
  void shouldResolve_whenOptionalDependencyMissing() {
    String json = "[{\"alias\":\"market_data\",\"jobCode\":\"MARKET_DATA\",\"bizDateOffset\":-1,"
        + "\"scope\":\"OPTIONAL\"}]";
    when(queryService.findLatestCrossDayDependencyVersion(
            "t1", "MARKET_DATA", LocalDate.of(2026, Month.MAY, 3), false))
        .thenReturn(Optional.empty());

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json, false);

    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).doesNotContainKey("market_data");
    assertThat(result.getResultVersionIdsByAlias()).containsEntry("market_data", List.of());
  }

  @Test
  @DisplayName("整图重放只读取预览固定的结果版本 id, 不重新查询 latest")
  void shouldResolvePinnedResultVersionWithoutRequeryingLatest() {
    String json = "[{\"alias\":\"t_minus_1\",\"jobCode\":\"DAILY_PNL\","
        + "\"bizDateOffset\":-1,\"scope\":\"REQUIRED\"}]";
    ResultVersionEntity pinned = ResultVersionEntity.builder()
        .id(77L)
        .versionNo(2)
        .status("SUPERSEDED")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"recordCount\":42}")
        .jobInstanceId(100L)
        .businessKey("job:DAILY_PNL:2026-05-03")
        .build();
    when(queryService.findById("t1", 77L)).thenReturn(Optional.of(pinned));

    ResolutionResult result = resolver.resolvePinned(
        "t1", LocalDate.of(2026, Month.MAY, 4), json, false, Map.of("t_minus_1", List.of(77L)));

    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResultVersionIdsByAlias()).containsEntry("t_minus_1", List.of(77L));
    @SuppressWarnings("unchecked")
    Map<String, Object> entry = (Map<String, Object>) result.getResolved().get("t_minus_1");
    assertThat(entry).containsEntry("resultVersionId", 77L);
    assertThat(entry.get("output")).isEqualTo(Map.of("recordCount", 42));
    Mockito.verify(queryService, Mockito.never())
        .findLatestCrossDayDependencyVersion(any(), any(), any(), ArgumentMatchers.anyBoolean());
  }

  @Test
  @DisplayName("整图重放的固定结果版本已过期或业务键不符时失败关闭")
  void shouldFailPinnedResolutionWhenSelectedResultNoLongerMatchesDependency() {
    String json = "[{\"alias\":\"t_minus_1\",\"jobCode\":\"DAILY_PNL\","
        + "\"bizDateOffset\":-1,\"scope\":\"REQUIRED\"}]";
    ResultVersionEntity wrongBusinessDate = ResultVersionEntity.builder()
        .id(77L)
        .versionNo(2)
        .status("SUPERSEDED")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"recordCount\":42}")
        .jobInstanceId(100L)
        .businessKey("job:DAILY_PNL:2026-05-02")
        .build();
    when(queryService.findById("t1", 77L)).thenReturn(Optional.of(wrongBusinessDate));

    ResolutionResult result = resolver.resolvePinned(
        "t1", LocalDate.of(2026, Month.MAY, 4), json, false, Map.of("t_minus_1", List.of(77L)));

    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEP_SNAPSHOT_INVALID");
  }

  @Test
  @DisplayName("按区间依赖解析时聚合多日命中结果, 逐日输出上游产出")
  void shouldAggregateHits_whenDependencySpansRange() {
    String json =
        "[{\"alias\":\"prev_5\",\"jobCode\":\"DAILY_PNL\",\"bizDateRange\":\"PREV_5_BIZ_DAYS\","
            + "\"scope\":\"REQUIRED\"}]";
    ResultVersionEntity hit = ResultVersionEntity.builder()
        .id(77L)
        .versionNo(1)
        .status("EFFECTIVE")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{}")
        .build();
    when(queryService.findLatestCrossDayDependencyVersion(
            eq("t1"), eq("DAILY_PNL"), any(LocalDate.class), eq(false)))
        .thenReturn(Optional.of(hit));

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json, false);

    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).containsKey("prev_5");
    @SuppressWarnings("unchecked")
    Map<String, Object> entry = (Map<String, Object>) result.getResolved().get("prev_5");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> outputs = (List<Map<String, Object>>) entry.get("outputs");
    assertThat(outputs).hasSize(5);
  }

  @Test
  @DisplayName("跨天依赖配置格式非法时进入失败状态并给出解析失败原因")
  void shouldFail_whenDependencyConfigMalformed() {
    ResolutionResult result =
        resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), "not-json{[", false);
    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEPS_PARSE_FAILED");
  }

  @Test
  @DisplayName("依赖项未给出任务编码时进入失败状态并给出配置非法原因")
  void shouldFail_whenSpecMissesJobCode() {
    String json = "[{\"alias\":\"x\",\"bizDateOffset\":-1}]";
    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json, false);
    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEP_INVALID_SPEC");
  }

  @Test
  @DisplayName("消费版本策略无法识别时按配置错误失败, 不允许进入等待")
  void shouldFail_whenConsumeStrategyUnknown() {
    String json = "[{\"alias\":\"t1\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
        + "\"scope\":\"REQUIRED\",\"consumeVersionStrategy\":\"BOGUS\"}]";

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json, false);

    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEP_INVALID_STRATEGY");
  }

  @Test
  @DisplayName("指定版本策略缺少正版本号时按配置错误失败")
  void shouldFail_whenSpecificVersionSelectorIsInvalid() {
    String json = "[{\"alias\":\"v0\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
        + "\"consumeVersionStrategy\":\"SPECIFIC_VERSION\",\"specificVersionNo\":0}]";

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json, false);

    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEP_INVALID_STRATEGY");
  }

  @Test
  @DisplayName("未知依赖 scope 按配置错误失败")
  void shouldFail_whenScopeUnknown() {
    String json = "[{\"alias\":\"x\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
        + "\"scope\":\"MAYBE\"}]";

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json, false);

    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEP_INVALID_SCOPE");
  }

  @ParameterizedTest
  @ValueSource(strings = {"EXTERNAL_REF", "FILE_RECORD"})
  @DisplayName("暂不支持的结果引用存储类型失败关闭")
  void shouldFail_whenPayloadStorageIsNotInline(String payloadStorage) {
    String json = "[{\"alias\":\"upstream\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1}]";
    ResultVersionEntity hit = ResultVersionEntity.builder()
        .id(456L)
        .versionNo(2)
        .status("EFFECTIVE")
        .payloadStorage(payloadStorage)
        .payloadRef("file_record:123")
        .build();
    when(queryService.findLatestCrossDayDependencyVersion(
            "t1", "DAILY_PNL", LocalDate.of(2026, Month.MAY, 3), false))
        .thenReturn(Optional.of(hit));

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json, false);

    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEP_PAYLOAD_UNSUPPORTED");
  }

  @Test
  @DisplayName("inline 结果载荷缺失或不是 JSON 对象时失败关闭")
  void shouldFail_whenInlinePayloadIsInvalid() {
    String json = "[{\"alias\":\"upstream\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1}]";
    ResultVersionEntity hit = ResultVersionEntity.builder()
        .id(457L)
        .versionNo(2)
        .status("EFFECTIVE")
        .payloadStorage("INLINE_JSON")
        .payloadJson("[1,2]")
        .build();
    when(queryService.findLatestCrossDayDependencyVersion(
            "t1", "DAILY_PNL", LocalDate.of(2026, Month.MAY, 3), false))
        .thenReturn(Optional.of(hit));

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json, false);

    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEP_PAYLOAD_UNSUPPORTED");
  }

  @Test
  @DisplayName("按指定版本号消费时过滤出对应版本并判定为已解析")
  void shouldResolve_whenSpecifyingVersionNo() {
    String json = "[{\"alias\":\"v1\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
        + "\"scope\":\"REQUIRED\",\"consumeVersionStrategy\":\"SPECIFIC_VERSION\","
        + "\"specificVersionNo\":1}]";
    ResultVersionEntity v1 = ResultVersionEntity.builder()
        .id(123L)
        .versionNo(1)
        .status("SUPERSEDED")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"recordCount\":12}")
        .build();
    when(queryService.findCrossDayDependencyVersion(
            "t1", "DAILY_PNL", LocalDate.of(2026, Month.MAY, 3), 1, false))
        .thenReturn(Optional.of(v1));

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json, false);

    assertThat(result.isResolved()).isTrue();
    @SuppressWarnings("unchecked")
    Map<String, Object> entry = (Map<String, Object>) result.getResolved().get("v1");
    assertThat(entry).containsEntry("resultVersionId", 123L).containsEntry("versionNo", 1);
    assertThat(entry.get("output")).isEqualTo(Map.of("recordCount", 12));
  }

  @Test
  @DisplayName("dry-run 只从 DRY_RUN 版本链读取跨日依赖")
  void shouldReadDryRunDependencyVersionOnly_whenDryRun() {
    String json = "[{\"alias\":\"upstream\",\"jobCode\":\"DAILY_PNL\","
        + "\"bizDateOffset\":-1,\"scope\":\"REQUIRED\","
        + "\"consumeVersionStrategy\":\"LATEST_INCLUDING_PENDING\"}]";
    ResultVersionEntity dryRunVersion = ResultVersionEntity.builder()
        .id(901L)
        .versionNo(3)
        .status("DRY_RUN")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"dryRun\":true}")
        .build();
    when(queryService.findLatestCrossDayDependencyVersion(
            "t1", "DAILY_PNL", LocalDate.of(2026, Month.MAY, 3), true))
        .thenReturn(Optional.of(dryRunVersion));

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json, true);

    assertThat(result.isResolved()).isTrue();
    @SuppressWarnings("unchecked")
    Map<String, Object> entry = (Map<String, Object>) result.getResolved().get("upstream");
    assertThat(entry).containsEntry("status", "DRY_RUN");
    assertThat(entry.get("output")).isEqualTo(Map.of("dryRun", true));
  }
}
