package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 图片归一化:格式重写与输入体积上限校验")
class ConsoleAiImageNormalizerTest {
  private final ConsoleAiProperties.Image limits = new ConsoleAiProperties.Image();

  @Test
  @DisplayName("PNG 归一化:输出像素图,并返回尺寸与媒体类型")
  void shouldRewritePng_whenInputIsPng() throws IOException {
    byte[] input = image("png");
    ConsoleAiImageNormalizer.NormalizedImage normalized =
        ConsoleAiImageNormalizer.normalize(input, limits);

    assertThat(normalized.mediaType()).isEqualTo("image/png");
    assertThat(normalized.width()).isEqualTo(16);
    assertThat(normalized.height()).isEqualTo(16);
    assertThat(ImageIO.read(new ByteArrayInputStream(normalized.bytes()))).isNotNull();
  }

  @Test
  @DisplayName("JPEG 归一化:重新编码,输出字节与原始输入不同")
  void shouldReencodeJpeg_whenInputIsJpeg() throws IOException {
    byte[] input = image("jpeg");
    ConsoleAiImageNormalizer.NormalizedImage normalized =
        ConsoleAiImageNormalizer.normalize(input, limits);

    assertThat(normalized.mediaType()).isEqualTo("image/jpeg");
    assertThat(normalized.bytes()).isNotEqualTo(input);
  }

  @Test
  @DisplayName("格式未知或超出体积上限:在解码之前即拒绝")
  void shouldRejectInput_whenFormatUnknownOrTooLarge() {
    assertThatThrownBy(() -> ConsoleAiImageNormalizer.normalize(new byte[] {1, 2, 3}, limits))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() ->
            ConsoleAiImageNormalizer.normalize(new byte[limits.getMaxFileBytes() + 1], limits))
        .isInstanceOf(BizException.class);
  }

  private static byte[] image(String format) throws IOException {
    BufferedImage source = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++)
        source.setRGB(x, y, x < 8 ? Color.RED.getRGB() : Color.GREEN.getRGB());
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(source, format, output);
    return output.toByteArray();
  }
}
