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
import org.junit.jupiter.api.Test;

class ConsoleAiImageNormalizerTest {
  private final ConsoleAiProperties.Image limits = new ConsoleAiProperties.Image();

  @Test
  void rewritesPngToPixelOnlyImage() throws IOException {
    byte[] input = image("png");
    ConsoleAiImageNormalizer.NormalizedImage normalized =
        ConsoleAiImageNormalizer.normalize(input, limits);

    assertThat(normalized.mediaType()).isEqualTo("image/png");
    assertThat(normalized.width()).isEqualTo(16);
    assertThat(normalized.height()).isEqualTo(16);
    assertThat(ImageIO.read(new ByteArrayInputStream(normalized.bytes()))).isNotNull();
  }

  @Test
  void rewritesJpegWithoutPreservingSourceBytes() throws IOException {
    byte[] input = image("jpeg");
    ConsoleAiImageNormalizer.NormalizedImage normalized =
        ConsoleAiImageNormalizer.normalize(input, limits);

    assertThat(normalized.mediaType()).isEqualTo("image/jpeg");
    assertThat(normalized.bytes()).isNotEqualTo(input);
  }

  @Test
  void rejectsUnknownOrOversizedInputBeforeDecode() {
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
