package com.example.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.application.service.version.ResultVersionQueryService;
import com.example.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver.ResolutionResult;
import com.example.batch.orchestrator.domain.entity.ResultVersionEntity;
import com.example.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CrossDayDependencyResolverTest {

  private ResultVersionQueryService queryService;
  private ResultVersionMapper mapper;
  private CrossDayDependencyResolver resolver;

  @BeforeEach
  void setUp() {
    queryService = mock(ResultVersionQueryService.class);
    mapper = mock(ResultVersionMapper.class);
    resolver = new CrossDayDependencyResolver(new BizDateArithmetic(), queryService, mapper);
  }

  @Test
  void emptyDependenciesIsResolved() {
    var result = resolver.resolve("t1", LocalDate.of(2026, 5, 4), null);
    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).isEmpty();
  }

  @Test
  void offsetEffectiveHitInjectsPayload() {
    String json =
        "[{\"alias\":\"t_minus_1\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
            + "\"scope\":\"REQUIRED\",\"consumeVersionStrategy\":\"EFFECTIVE_ONLY\"}]";
    ResultVersionEntity hit =
        ResultVersionEntity.builder()
            .versionNo(2)
            .status("EFFECTIVE")
            .payloadStorage("INLINE_JSON")
            .payloadJson("{\"recordCount\":42}")
            .jobInstanceId(100L)
            .businessKey("job:DAILY_PNL:2026-05-03")
            .build();
    when(queryService.findEffectiveByJob("t1", "DAILY_PNL", LocalDate.of(2026, 5, 3)))
        .thenReturn(Optional.of(hit));

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, 5, 4), json);

    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).containsKey("t_minus_1");
    @SuppressWarnings("unchecked")
    Map<String, Object> entry = (Map<String, Object>) result.getResolved().get("t_minus_1");
    assertThat(entry.get("versionNo")).isEqualTo(2);
    assertThat(entry.get("status")).isEqualTo("EFFECTIVE");
  }

  @Test
  void requiredMissingPutsResolutionInWaitingState() {
    String json =
        "[{\"alias\":\"t_minus_1\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
            + "\"scope\":\"REQUIRED\"}]";
    when(queryService.findEffectiveByJob("t1", "DAILY_PNL", LocalDate.of(2026, 5, 3)))
        .thenReturn(Optional.empty());

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, 5, 4), json);

    assertThat(result.isWaiting()).isTrue();
    assertThat(result.getWaitingReasons())
        .singleElement()
        .asString()
        .contains("MISSING")
        .contains("DAILY_PNL")
        .contains("2026-05-03");
  }

  @Test
  void optionalMissingStillResolves() {
    String json =
        "[{\"alias\":\"market_data\",\"jobCode\":\"MARKET_DATA\",\"bizDateOffset\":-1,"
            + "\"scope\":\"OPTIONAL\"}]";
    when(queryService.findEffectiveByJob("t1", "MARKET_DATA", LocalDate.of(2026, 5, 3)))
        .thenReturn(Optional.empty());

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, 5, 4), json);

    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).doesNotContainKey("market_data");
  }

  @Test
  void rangeAggregatesMultipleHits() {
    String json =
        "[{\"alias\":\"prev_5\",\"jobCode\":\"DAILY_PNL\",\"bizDateRange\":\"PREV_5_BIZ_DAYS\","
            + "\"scope\":\"REQUIRED\"}]";
    ResultVersionEntity hit =
        ResultVersionEntity.builder()
            .versionNo(1)
            .status("EFFECTIVE")
            .payloadStorage("INLINE_JSON")
            .payloadJson("{}")
            .build();
    when(queryService.findEffectiveByJob(eq("t1"), eq("DAILY_PNL"), any(LocalDate.class)))
        .thenReturn(Optional.of(hit));

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, 5, 4), json);

    assertThat(result.isResolved()).isTrue();
    assertThat(result.getResolved()).containsKey("prev_5");
    @SuppressWarnings("unchecked")
    Map<String, Object> entry = (Map<String, Object>) result.getResolved().get("prev_5");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> outputs = (List<Map<String, Object>>) entry.get("outputs");
    assertThat(outputs).hasSize(5);
  }

  @Test
  void invalidJsonPutsResolutionInFailedState() {
    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, 5, 4), "not-json{[");
    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEPS_PARSE_FAILED");
  }

  @Test
  void specWithoutJobCodeFails() {
    String json = "[{\"alias\":\"x\",\"bizDateOffset\":-1}]";
    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, 5, 4), json);
    assertThat(result.isFailed()).isTrue();
    assertThat(result.getFailureCode()).isEqualTo("CROSS_DAY_DEP_INVALID_SPEC");
  }

  @Test
  void unknownStrategyTreatsAsMissing() {
    String json =
        "[{\"alias\":\"t1\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
            + "\"scope\":\"REQUIRED\",\"consumeVersionStrategy\":\"BOGUS\"}]";

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, 5, 4), json);

    assertThat(result.isWaiting()).isTrue();
  }

  @Test
  void specificVersionStrategyFiltersByVersionNo() {
    String json =
        "[{\"alias\":\"v1\",\"jobCode\":\"DAILY_PNL\",\"bizDateOffset\":-1,"
            + "\"scope\":\"REQUIRED\",\"consumeVersionStrategy\":\"SPECIFIC_VERSION\","
            + "\"specificVersionNo\":1}]";
    ResultVersionEntity v2 = ResultVersionEntity.builder().versionNo(2).build();
    ResultVersionEntity v1 = ResultVersionEntity.builder().versionNo(1).build();
    when(queryService.listVersions("t1", "job:DAILY_PNL:2026-05-03", 50))
        .thenReturn(List.of(v2, v1));

    ResolutionResult result = resolver.resolve("t1", LocalDate.of(2026, 5, 4), json);

    assertThat(result.isResolved()).isTrue();
  }
}
