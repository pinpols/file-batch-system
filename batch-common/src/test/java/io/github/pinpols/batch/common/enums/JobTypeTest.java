package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("JobType 作业类型: 批次类型投影,束作业判定与交付执行类型映射")
class JobTypeTest {

  @Test
  @DisplayName("三类束作业分别投影到导入 / 导出 / 派发 批次类型")
  void shouldProjectBundleTypesToTheirBatchTypeBucket_whenMapping() {
    assertThat(JobType.BUNDLE_IMPORT.batchType()).isEqualTo(BatchType.IMPORT);
    assertThat(JobType.BUNDLE_EXPORT.batchType()).isEqualTo(BatchType.EXPORT);
    assertThat(JobType.BUNDLE_DISPATCH.batchType()).isEqualTo(BatchType.DISPATCH);
  }

  @ParameterizedTest
  @DisplayName("每个作业类型都能得到非空批次类型,映射不留缺口")
  @EnumSource(JobType.class)
  void shouldProvideNonNullBatchType_whenEnumeratingEveryJobType(JobType type) {
    // batchType() 的 switch 必须穷尽——新增枚举忘补映射会在此 NPE/编译失败回退。
    assertThat(type.batchType()).isNotNull();
  }

  @Test
  @DisplayName("仅三类束作业判定为束,其余常规作业类型均判定为非束")
  void shouldReportBundleOnlyForBundleTypes_whenJudging() {
    assertThat(JobType.BUNDLE_IMPORT.isBundle()).isTrue();
    assertThat(JobType.BUNDLE_EXPORT.isBundle()).isTrue();
    assertThat(JobType.BUNDLE_DISPATCH.isBundle()).isTrue();
    assertThat(JobType.IMPORT.isBundle()).isFalse();
    assertThat(JobType.EXPORT.isBundle()).isFalse();
    assertThat(JobType.DISPATCH.isBundle()).isFalse();
    assertThat(JobType.GENERAL.isBundle()).isFalse();
  }

  @Test
  @DisplayName("束作业映射到对应交付执行类型,非束作业沿用自身编码")
  void shouldProjectBundleTypesToDeliveryWorkerTypeAndKeepOthers_whenResolvingWorkerType() {
    // 束作业投射到交付 worker 类型(否则 task_type=BUNDLE_* 违反 ck_job_task_type 且无 worker 认领)。
    assertThat(JobType.BUNDLE_IMPORT.workerTypeCode()).isEqualTo("IMPORT");
    assertThat(JobType.BUNDLE_EXPORT.workerTypeCode()).isEqualTo("EXPORT");
    assertThat(JobType.BUNDLE_DISPATCH.workerTypeCode()).isEqualTo("DISPATCH");
    // 非束作业 = 自身 code,行为不变。
    assertThat(JobType.IMPORT.workerTypeCode()).isEqualTo("IMPORT");
    assertThat(JobType.EXPORT.workerTypeCode()).isEqualTo("EXPORT");
    assertThat(JobType.ATOMIC.workerTypeCode()).isEqualTo("ATOMIC");
    assertThat(JobType.GENERAL.workerTypeCode()).isEqualTo("GENERAL");
  }

  @Test
  @DisplayName("束作业编码判定只认三种束编码,未知编码与空值一律为假")
  void shouldRejectUnknownOrNullBundleCode_whenJudging() {
    assertThat(JobType.isBundleCode("BUNDLE_IMPORT")).isTrue();
    assertThat(JobType.isBundleCode("BUNDLE_EXPORT")).isTrue();
    assertThat(JobType.isBundleCode("BUNDLE_DISPATCH")).isTrue();
    assertThat(JobType.isBundleCode("IMPORT")).isFalse();
    assertThat(JobType.isBundleCode("UNKNOWN")).isFalse();
    assertThat(JobType.isBundleCode(null)).isFalse();
  }
}
