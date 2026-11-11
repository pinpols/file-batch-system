package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.query.PartitionStatusRef;
import io.github.pinpols.batch.orchestrator.domain.view.PartitionStatusSummary;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("分区结果计数：两种计数来源、完成判定与终态摘要校验")
class PartitionOutcomeCountsTest {

  @Test
  @DisplayName("按分区状态行分别统计各终态，未终态分区不计入完成数")
  void fromStatusRefs_countsEachTerminalStatus() {
    List<PartitionStatusRef> refs = List.of(
        new PartitionStatusRef(1L, PartitionStatus.SUCCESS.code()),
        new PartitionStatusRef(2L, PartitionStatus.SUCCESS.code()),
        new PartitionStatusRef(3L, PartitionStatus.FAILED.code()),
        new PartitionStatusRef(4L, PartitionStatus.CANCELLED.code()),
        new PartitionStatusRef(5L, PartitionStatus.TERMINATED.code()),
        new PartitionStatusRef(6L, PartitionStatus.RUNNING.code()));

    PartitionOutcomeCounts counts = PartitionOutcomeCounts.fromStatusRefs(refs);

    assertThat(counts.total()).isEqualTo(6);
    assertThat(counts.success()).isEqualTo(2);
    assertThat(counts.failed()).isEqualTo(1);
    assertThat(counts.cancelled()).isEqualTo(1);
    assertThat(counts.terminated()).isEqualTo(1);
    assertThat(counts.broadFailed()).isEqualTo(3);
    assertThat(counts.finished()).isEqualTo(5);
    assertThat(counts.allFinished()).isFalse();
  }

  @Test
  @DisplayName("实例计数器为空时各项按 0 处理且不视为全部完成")
  void fromInstanceCounters_treatsNullCountersAsZero() {
    PartitionOutcomeCounts counts =
        PartitionOutcomeCounts.fromInstanceCounters(new JobInstanceEntity());

    assertThat(counts).isEqualTo(new PartitionOutcomeCounts(0, 0, 0, 0, 0));
    assertThat(counts.broadFailed()).isZero();
    assertThat(counts.finished()).isZero();
    assertThat(counts.allFinished()).isFalse();
  }

  @Test
  @DisplayName("全部分区进入终态时判定为全部完成")
  void allFinished_whenEveryPartitionTerminal() {
    assertThat(new PartitionOutcomeCounts(3, 2, 1, 0, 0).allFinished()).isTrue();
  }

  @Test
  @DisplayName("终态摘要缺失时判定为不一致")
  void matches_falseWhenSummaryNull() {
    assertThat(new PartitionOutcomeCounts(3, 2, 1, 0, 0).matches(null)).isFalse();
  }

  @ParameterizedTest
  @MethodSource("summariesWithOneFieldChanged")
  @DisplayName("终态摘要任一计数不同即判定为不一致")
  void matches_falseWhenAnyFieldDiffers(PartitionStatusSummary summary) {
    assertThat(new PartitionOutcomeCounts(10, 4, 3, 2, 1).matches(summary)).isFalse();
  }

  @Test
  @DisplayName("终态摘要六项计数全部相同时判定为一致")
  void matches_trueWhenAllSixEqual() {
    PartitionStatusSummary summary = new PartitionStatusSummary(10L, 4L, 3L, 2L, 1L, 6L);

    assertThat(new PartitionOutcomeCounts(10, 4, 3, 2, 1).matches(summary)).isTrue();
  }

  static Stream<Arguments> summariesWithOneFieldChanged() {
    return Stream.of(
        Arguments.of(new PartitionStatusSummary(11L, 4L, 3L, 2L, 1L, 6L)),
        Arguments.of(new PartitionStatusSummary(10L, 5L, 3L, 2L, 1L, 6L)),
        Arguments.of(new PartitionStatusSummary(10L, 4L, 4L, 2L, 1L, 6L)),
        Arguments.of(new PartitionStatusSummary(10L, 4L, 3L, 3L, 1L, 6L)),
        Arguments.of(new PartitionStatusSummary(10L, 4L, 3L, 2L, 2L, 6L)),
        Arguments.of(new PartitionStatusSummary(10L, 4L, 3L, 2L, 1L, 7L)));
  }
}
