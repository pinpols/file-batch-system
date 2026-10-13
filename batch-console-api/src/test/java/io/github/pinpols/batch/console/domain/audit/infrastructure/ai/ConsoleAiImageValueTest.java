package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.audit.application.ai.ConsoleAiAttachmentUseCase.ImageContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 图片值对象：字节数组值语义")
class ConsoleAiImageValueTest {

  @Test
  @DisplayName("图片内容按字节比较，并防止调用方修改内部数组")
  void shouldUseDefensiveValueSemantics_whenImageContentIsCreatedAndRead() {
    byte[] input = {1, 2, 3};
    ImageContent content = new ImageContent(input, "image/png");
    input[0] = 9;
    byte[] returned = content.bytes();
    returned[1] = 9;

    assertThat(content)
        .isEqualTo(new ImageContent(new byte[] {1, 2, 3}, "image/png"))
        .hasSameHashCodeAs(new ImageContent(new byte[] {1, 2, 3}, "image/png"));
    assertThat(content.toString()).doesNotContain("1, 2, 3");
  }

  @Test
  @DisplayName("归一化结果按字节与元数据比较，并防止调用方修改内部数组")
  void shouldUseDefensiveValueSemantics_whenNormalizedImageIsCreatedAndRead() {
    byte[] input = {4, 5, 6};
    ConsoleAiImageNormalizer.NormalizedImage image =
        new ConsoleAiImageNormalizer.NormalizedImage(input, "image/png", 1, 1);
    input[0] = 9;
    byte[] returned = image.bytes();
    returned[1] = 9;

    assertThat(image)
        .isEqualTo(
            new ConsoleAiImageNormalizer.NormalizedImage(new byte[] {4, 5, 6}, "image/png", 1, 1))
        .hasSameHashCodeAs(
            new ConsoleAiImageNormalizer.NormalizedImage(new byte[] {4, 5, 6}, "image/png", 1, 1));
    assertThat(image.toString()).doesNotContain("4, 5, 6");
  }
}
