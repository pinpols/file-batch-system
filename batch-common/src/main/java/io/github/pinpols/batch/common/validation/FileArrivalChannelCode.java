package io.github.pinpols.batch.common.validation;

/** 文件到达传感器的可选渠道编码格式。 */
public final class FileArrivalChannelCode {
  private FileArrivalChannelCode() {}

  public static boolean isValid(Object value) {
    return value == null || value instanceof String code && code.length() <= 128;
  }
}
