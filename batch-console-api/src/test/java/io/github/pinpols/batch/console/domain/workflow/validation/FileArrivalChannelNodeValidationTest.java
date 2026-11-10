package io.github.pinpols.batch.console.domain.workflow.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件等待渠道输入校验")
class FileArrivalChannelNodeValidationTest {
  @Test
  @DisplayName("渠道可省略或填写有效字符串，非字符串及超长值拒绝")
  void shouldValidateOptionalChannel() {
    assertThatCode(() -> FileArrivalChannelNodeValidation.validate(
            "WAIT", "{\"sensor_type\":\"FILE_ARRIVAL\",\"sensor_spec\":{}}"))
        .doesNotThrowAnyException();
    assertThatCode(() -> FileArrivalChannelNodeValidation.validate(
            "WAIT",
            "{\"sensor_type\":\"FILE_ARRIVAL\",\"sensor_spec\":{\"channelCode\":\"bank_in\"}}"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> FileArrivalChannelNodeValidation.validate(
            "WAIT", "{\"sensor_type\":\"FILE_ARRIVAL\",\"sensor_spec\":{\"channelCode\":42}}"))
        .isInstanceOf(BizException.class);
    String invalidParams = "{\"sensor_type\":\"FILE_ARRIVAL\",\"sensor_spec\":{\"channelCode\":\""
        + "a".repeat(129) + "\"}}";
    assertThatThrownBy(() -> FileArrivalChannelNodeValidation.validate("WAIT", invalidParams))
        .isInstanceOf(BizException.class);
  }
}
