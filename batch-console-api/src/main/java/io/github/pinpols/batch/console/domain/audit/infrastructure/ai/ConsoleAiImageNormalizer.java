package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** 解码受限图片并写入仅含像素的新副本，不保留 EXIF 等元数据。 */
public final class ConsoleAiImageNormalizer {
  private ConsoleAiImageNormalizer() {}

  public static NormalizedImage normalize(byte[] input, ConsoleAiProperties.Image limits) {
    if (EmptyChecks.isNull(input) || input.length < 1) {
      throw invalidImage();
    }
    boolean inputExceedsByteLimit = input.length > limits.getMaxFileBytes();
    if (inputExceedsByteLimit) {
      throw invalidImage();
    }
    String detected = detect(input);
    try (ImageInputStream stream =
        ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw invalidImage();
      ImageReader reader = readers.next();
      try {
        reader.setInput(stream, false, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width < 1 || height < 1) {
          throw invalidImage();
        }
        if (width > limits.getMaxSide() || height > limits.getMaxSide()) {
          throw invalidImage();
        }
        boolean pixelCountExceedsLimit = (long) width * height > limits.getMaxPixels();
        if (pixelCountExceedsLimit) {
          throw invalidImage();
        }
        boolean hasUnexpectedImageCount = reader.getNumImages(true) != 1;
        if (hasUnexpectedImageCount) {
          throw invalidImage();
        }
        BufferedImage source = reader.read(0);
        boolean jpeg = "image/jpeg".equals(detected);
        BufferedImage clean = new BufferedImage(
            width, height, jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = clean.createGraphics();
        try {
          graphics.drawImage(source, 0, 0, null);
        } finally {
          graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String format = jpeg ? "jpeg" : "png";
        boolean imageWritten = ImageIO.write(clean, format, output);
        if (!imageWritten || output.size() > limits.getMaxFileBytes()) {
          throw invalidImage();
        }
        return new NormalizedImage(
            output.toByteArray(), jpeg ? "image/jpeg" : "image/png", width, height);
      } finally {
        reader.dispose();
      }
    } catch (IOException | IllegalArgumentException exception) {
      throw invalidImage();
    }
  }

  private static String detect(byte[] bytes) {
    if (bytes.length > 8
        && bytes[0] == (byte) 0x89
        && bytes[1] == 0x50
        && bytes[2] == 0x4e
        && bytes[3] == 0x47) return "image/png";
    if (bytes.length > 3
        && bytes[0] == (byte) 0xff
        && bytes[1] == (byte) 0xd8
        && bytes[2] == (byte) 0xff) return "image/jpeg";
    if (bytes.length > 12
        && bytes[0] == 'R'
        && bytes[1] == 'I'
        && bytes[2] == 'F'
        && bytes[3] == 'F'
        && bytes[8] == 'W'
        && bytes[9] == 'E'
        && bytes[10] == 'B'
        && bytes[11] == 'P') return "image/webp";
    throw invalidImage();
  }

  private static BizException invalidImage() {
    return BizException.of(ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey());
  }

  public record NormalizedImage(byte[] bytes, String mediaType, int width, int height) {
    public NormalizedImage {
      bytes = EmptyChecks.isNull(bytes) ? null : bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return EmptyChecks.isNull(bytes) ? null : bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      return other
              instanceof
              NormalizedImage(
                  byte[] otherBytes,
                  String otherMediaType,
                  int otherWidth,
                  int otherHeight)
          && Arrays.equals(bytes, otherBytes)
          && Objects.equals(mediaType, otherMediaType)
          && width == otherWidth
          && height == otherHeight;
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(bytes), mediaType, width, height);
    }

    @Override
    public String toString() {
      return "NormalizedImage[byteCount=" + (EmptyChecks.isNull(bytes) ? "null" : bytes.length)
          + ", mediaType="
          + mediaType + ", width=" + width + ", height=" + height + "]";
    }
  }
}
