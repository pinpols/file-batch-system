package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    var result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), null);
    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).isEmpty();
  }

  @Test
  @DisplayName("按偏移日命中生效版本时注入上游产出, 并带出版本号与状态")
  void shouldInjectPayload_whenOffsetDependencyEffective() {
    String json = "[{\"alias\":\"t_minus_1\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
        + "\"scope\":\"REQUIRED\",\"consumeVersionStrategy\":\"EFFECTIVE_ONLY\"}]";
    ResultVersionEntity hit = ResultVersionEntity.builder()
        .versionNo(2)
        .status("EFFECTIVE")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{\"recordCount\":42}")
        .jobInstanceId(100L)
        .businessKey("job:DAILY_PNL:2026-05-03")
        .build();
    when(queryService.findEffectiveByJob("t1", "DAILY_PNL", LocalDate.of(2026, Month.MAY, 3)))
        .thenReturn(Optional.of(hit));

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json);

    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).containsKey("t_minus_1");
    @SuppressWarnings("unchecked")
    Map<String, Object> entry = (Map<String, Object>) result.getResolved().get("t_minus_1");
    assertThat(entry).containsEntry("versionNo", 2);
    assertThat(entry).containsEntry("status", "EFFECTIVE");
  }

  @Test
  @DisplayName("必需依赖缺失时进入等待状态并给出等待原因")
  void shouldWait_whenRequiredDependencyMissing() {
    String json = "[{\"alias\":\"t_minus_1\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
        + "\"scope\":\"REQUIRED\"}]";
    when(queryService.findEffectiveByJob("t1", "DAILY_PNL", LocalDate.of(2026, Month.MAY, 3)))
        .thenReturn(Optional.empty());

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json);

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
    when(queryService.findEffectiveByJob("t1", "MARKET_DATA", LocalDate.of(2026, Month.MAY, 3)))
        .thenReturn(Optional.empty());

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json);

    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).doesNotContainKey("market_data");
  }

  @Test
  @DisplayName("按区间依赖解析时聚合多日命中结果, 逐日输出上游产出")
  void shouldAggregateHits_whenDependencySpansRange() {
    String json =
        "[{\"alias\":\"prev_5\",\"jobCode\":\"DAILY_PNL\",\"bizDateRange\":\"PREV_5_BIZ_DAYS\","
            + "\"scope\":\"REQUIRED\"}]";
    ResultVersionEntity hit = ResultVersionEntity.builder()
        .versionNo(1)
        .status("EFFECTIVE")
        .payloadStorage("INLINE_JSON")
        .payloadJson("{}")
        .build();
    when(queryService.findEffectiveByJob(eq("t1"), eq("DAILY_PNL"), any(LocalDate.class)))
        .thenReturn(Optional.of(hit));

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json);

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
        resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), "not-json{[");
    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEPS_PARSE_FAILED");
  }

  @Test
  @DisplayName("依赖项未给出任务编码时进入失败状态并给出配置非法原因")
  void shouldFail_whenSpecMissesJobCode() {
    String json = "[{\"alias\":\"x\",\"bizDateOffset\":-1}]";
    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json);
    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEP_INVALID_SPEC");
  }

  @Test
  @DisplayName("消费版本策略无法识别时按必需依赖缺失处理, 进入等待状态")
  void shouldWait_whenConsumeStrategyUnknown() {
    String json = "[{\"alias\":\"t1\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
        + "\"scope\":\"REQUIRED\",\"consumeVersionStrategy\":\"BOGUS\"}]";

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json);

    assertThat(result.isWaiting()).isTrue();
  }

  @Test
  @DisplayName("按指定版本号消费时过滤出对应版本并判定为已解析")
  void shouldResolve_whenSpecifyingVersionNo() {
    String json = "[{\"alias\":\"v1\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
        + "\"scope\":\"REQUIRED\",\"consumeVersionStrategy\":\"SPECIFIC_VERSION\","
        + "\"specificVersionNo\":1}]";
    ResultVersionEntity v2 = ResultVersionEntity.builder().versionNo(2).build();
    ResultVersionEntity v1 = ResultVersionEntity.builder().versionNo(1).build();
    when(queryService.listVersions("t1", "job:DAILY_PNL:2026-05-03", 50))
        .thenReturn(List.of(v2, v1));

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, Month.MAY, 4), json);

    assertThat(result.isResolved()).isTrue();
  }
}
