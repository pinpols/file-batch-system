package com.example.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.exception.BizException;
import org.junit.jupiter.api.Test;

class CodeNormalizerTest {

  @Test
  void groupCode_upperAndValidate() {
    assertThat(CodeNormalizer.normalizeGroupCode("import", "worker_group")).isEqualTo("IMPORT");
    assertThat(CodeNormalizer.normalizeGroupCode(" Import ", "worker_group")).isEqualTo("IMPORT");
    assertThat(CodeNormalizer.normalizeGroupCode("IMPORT_2", "worker_group")).isEqualTo("IMPORT_2");
  }

  @Test
  void groupCode_nullAndBlankPassThrough() {
    assertThat(CodeNormalizer.normalizeGroupCode(null, "worker_group")).isNull();
    assertThat(CodeNormalizer.normalizeGroupCode("", "worker_group")).isNull();
    assertThat(CodeNormalizer.normalizeGroupCode("   ", "worker_group")).isNull();
  }

  @Test
  void groupCode_rejectsInvalidChars() {
    // i18n 后 BizException.getMessage() 返回 messageKey,不渲染文本;断言改为基于 messageArgs(原文本)
    assertThatThrownBy(() -> CodeNormalizer.normalizeGroupCode("im-port", "worker_group"))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> {
              Object[] args = ((BizException) ex).getMessageArgs();
              assertThat(args).hasSize(1);
              assertThat(args[0].toString()).contains("worker_group").contains("im-port");
            });
    assertThatThrownBy(() -> CodeNormalizer.normalizeGroupCode("1import", "worker_group"))
        .isInstanceOf(BizException.class); // 不允许以数字开头
  }

  @Test
  void configCode_lowerAndUnderscore() {
    assertThat(CodeNormalizer.normalizeConfigCode("always-open", "window_code"))
        .isEqualTo("always_open");
    assertThat(CodeNormalizer.normalizeConfigCode("ALWAYS_OPEN", "window_code"))
        .isEqualTo("always_open");
    assertThat(CodeNormalizer.normalizeConfigCode("Night-Batch", "window_code"))
        .isEqualTo("night_batch");
    assertThat(CodeNormalizer.normalizeConfigCode("ta-biz-window", "window_code"))
        .isEqualTo("ta_biz_window");
  }

  @Test
  void configCode_nullAndBlankPassThrough() {
    assertThat(CodeNormalizer.normalizeConfigCode(null, "window_code")).isNull();
    assertThat(CodeNormalizer.normalizeConfigCode("", "window_code")).isNull();
  }

  @Test
  void configCode_rejectsSpecialChars() {
    // i18n 后 BizException.getMessage() 返回 messageKey,不渲染文本;断言改为基于 messageArgs(原文本)
    assertThatThrownBy(() -> CodeNormalizer.normalizeConfigCode("window.code", "window_code"))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> {
              Object[] args = ((BizException) ex).getMessageArgs();
              assertThat(args).hasSize(1);
              assertThat(args[0].toString()).contains("window_code");
            });
    assertThatThrownBy(() -> CodeNormalizer.normalizeConfigCode("win space", "window_code"))
        .isInstanceOf(BizException.class);
  }

  @Test
  void lenientHelpers_skipFormatCheck() {
    assertThat(CodeNormalizer.toUpperOrNull("im-port")).isEqualTo("IM-PORT");
    assertThat(CodeNormalizer.toConfigFormOrNull("Window.Code")).isEqualTo("window.code");
    assertThat(CodeNormalizer.toUpperOrNull(null)).isNull();
    assertThat(CodeNormalizer.toConfigFormOrNull(" ")).isNull();
  }
}
