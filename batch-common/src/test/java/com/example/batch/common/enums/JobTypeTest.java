package com.example.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JobTypeTest {

  @Test
  void bundleTypesProjectToTheirBatchTypeBucket() {
    assertThat(JobType.BUNDLE_IMPORT.batchType()).isEqualTo(BatchType.IMPORT);
    assertThat(JobType.BUNDLE_EXPORT.batchType()).isEqualTo(BatchType.EXPORT);
    assertThat(JobType.BUNDLE_DISPATCH.batchType()).isEqualTo(BatchType.DISPATCH);
  }

  @ParameterizedTest
  @EnumSource(JobType.class)
  void everyJobTypeHasNonNullBatchType(JobType type) {
    // batchType() 的 switch 必须穷尽——新增枚举忘补映射会在此 NPE/编译失败兜底。
    assertThat(type.batchType()).isNotNull();
  }

  @Test
  void isBundleTrueOnlyForBundleTypes() {
    assertThat(JobType.BUNDLE_IMPORT.isBundle()).isTrue();
    assertThat(JobType.BUNDLE_EXPORT.isBundle()).isTrue();
    assertThat(JobType.BUNDLE_DISPATCH.isBundle()).isTrue();
    assertThat(JobType.IMPORT.isBundle()).isFalse();
    assertThat(JobType.EXPORT.isBundle()).isFalse();
    assertThat(JobType.DISPATCH.isBundle()).isFalse();
    assertThat(JobType.GENERAL.isBundle()).isFalse();
  }

  @Test
  void isBundleCodeMatchesCodesAndRejectsUnknownOrNull() {
    assertThat(JobType.isBundleCode("BUNDLE_IMPORT")).isTrue();
    assertThat(JobType.isBundleCode("BUNDLE_EXPORT")).isTrue();
    assertThat(JobType.isBundleCode("BUNDLE_DISPATCH")).isTrue();
    assertThat(JobType.isBundleCode("IMPORT")).isFalse();
    assertThat(JobType.isBundleCode("UNKNOWN")).isFalse();
    assertThat(JobType.isBundleCode(null)).isFalse();
  }
}
