package io.github.pinpols.batch.console.application.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.DictEnum;
import java.lang.reflect.Method;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

@DisplayName("字典枚举 label 本地化: 消息 key 为空回退硬编码 label，非空走 MessageSource")
class ConsoleMetaLabelLocalizationTest {

  private static String invokeLocalizedLabel(
      DictEnum item, MessageSource messageSource, Locale locale)
      throws ReflectiveOperationException {
    Method method = ConsoleMetaQueryService.class.getDeclaredMethod(
        "localizedLabel", DictEnum.class, MessageSource.class, Locale.class);
    method.setAccessible(true);
    return (String) method.invoke(null, item, messageSource, locale);
  }

  private static DictEnum item(String messageKey, String label) {
    return new DictEnum() {
      @Override
      public String code() {
        return "CODE";
      }

      @Override
      public String label() {
        return label;
      }

      @Override
      public String messageKey() {
        return messageKey;
      }
    };
  }

  @Test
  @DisplayName("消息 key 为 null 时直接返回硬编码 label，不查询 MessageSource")
  void returns_fallback_label_when_message_key_is_null() throws ReflectiveOperationException {
    MessageSource messageSource = mock(MessageSource.class);

    String label = invokeLocalizedLabel(item(null, "中文标签"), messageSource, Locale.ENGLISH);

    assertThat(label).isEqualTo("中文标签");
    verifyNoInteractions(messageSource);
  }

  @Test
  @DisplayName("消息 key 非空时按 Locale 取翻译值，缺失时由 MessageSource 回退到硬编码 label")
  void resolves_translated_label_when_message_key_present() throws ReflectiveOperationException {
    MessageSource messageSource = mock(MessageSource.class);
    when(messageSource.getMessage("dict.X.Y", null, "中文标签", Locale.ENGLISH))
        .thenReturn("English label");

    String label = invokeLocalizedLabel(item("dict.X.Y", "中文标签"), messageSource, Locale.ENGLISH);

    assertThat(label).isEqualTo("English label");
    verify(messageSource).getMessage("dict.X.Y", null, "中文标签", Locale.ENGLISH);
    verify(messageSource, never()).getMessage("dict.X.Y", null, Locale.ENGLISH);
  }
}
