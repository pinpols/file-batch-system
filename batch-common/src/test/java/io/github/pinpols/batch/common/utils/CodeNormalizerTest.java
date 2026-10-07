package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("编码归一化工具: 组编码与配置编码的大小写, 分隔符归一及非法格式拒绝")
class CodeNormalizerTest {

  @Test
  @DisplayName("组编码: 去除首尾空格并转为大写, 合法的下划线数字组合原样保留")
  void groupCode_upperAndValidate() {
    assertThat(CodeNormalizer.normalizeGroupCode("import", "worker_group")).isEqualTo("IMPORT");
    assertThat(CodeNormalizer.normalizeGroupCode(" Import ", "worker_group")).isEqualTo("IMPORT");
    assertThat(CodeNormalizer.normalizeGroupCode("IMPORT_2", "worker_group")).isEqualTo("IMPORT_2");
  }

  @Test
  @DisplayName("组编码: 空值与纯空白输入统一返回空")
  void groupCode_nullAndBlankPassThrough() {
    assertThat(CodeNormalizer.normalizeGroupCode(null, "worker_group")).isNull();
    assertThat(CodeNormalizer.normalizeGroupCode("", "worker_group")).isNull();
    assertThat(CodeNormalizer.normalizeGroupCode("   ", "worker_group")).isNull();
  }

  @Test
  @DisplayName("组编码: 含连字符或以数字开头时抛业务异常, 异常参数保留原始编码与组类型")
  void groupCode_rejectsInvalidChars() {
    // i18n 后 BizException.getMessage() 返回 messageKey,不渲染文本;断言改为基于 messageArgs(原文本)
    assertThatThrownBy(() -> CodeNormalizer.normalizeGroupCode("im-port", "worker_group"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> {
          Object[] args = ((BizException) ex).getMessageArgs();
          assertThat(args).hasSize(1);
          assertThat(args[0].toString()).contains("worker_group").contains("im-port");
        });
    assertThatThrownBy(() -> CodeNormalizer.normalizeGroupCode("1import", "worker_group"))
        .isInstanceOf(BizException.class); // 不允许以数字开头
  }

  @Test
  @DisplayName("配置编码: 连字符与大小写差异统一归一为小写下划线形式")
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
  @DisplayName("配置编码: 空值与空串输入返回空")
  void configCode_nullAndBlankPassThrough() {
    assertThat(CodeNormalizer.normalizeConfigCode(null, "window_code")).isNull();
    assertThat(CodeNormalizer.normalizeConfigCode("", "window_code")).isNull();
  }

  @Test
  @DisplayName("配置编码: 含点号或空格时抛业务异常, 异常参数保留编码类型")
  void configCode_rejectsSpecialChars() {
    // i18n 后 BizException.getMessage() 返回 messageKey,不渲染文本;断言改为基于 messageArgs(原文本)
    assertThatThrownBy(() -> CodeNormalizer.normalizeConfigCode("window.code", "window_code"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> {
          Object[] args = ((BizException) ex).getMessageArgs();
          assertThat(args).hasSize(1);
          assertThat(args[0].toString()).contains("window_code");
        });
    assertThatThrownBy(() -> CodeNormalizer.normalizeConfigCode("win space", "window_code"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("宽松辅助方法: 不做格式校验, 仅做字符转换, 空白输入返回空")
  void lenientHelpers_skipFormatCheck() {
    assertThat(CodeNormalizer.toUpperOrNull("im-port")).isEqualTo("IM-PORT");
    assertThat(CodeNormalizer.toConfigFormOrNull("Window.Code")).isEqualTo("window.code");
    assertThat(CodeNormalizer.toUpperOrNull(null)).isNull();
    assertThat(CodeNormalizer.toConfigFormOrNull(" ")).isNull();
  }
}
