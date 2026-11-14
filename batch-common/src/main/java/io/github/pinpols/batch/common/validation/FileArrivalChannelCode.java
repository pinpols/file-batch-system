package io.github.pinpols.batch.common.validation;

import io.github.pinpols.batch.common.utils.EmptyChecks;

/** 文件到达传感器的可选渠道编码格式。 */
public final class FileArrivalChannelCode {

  /**
   * 渠道编码长度上限，对应 file_channel_config.channel_code VARCHAR(128)
   * （V6__create_file_tables.sql）。
   */
  private static final int CHANNEL_CODE_MAX_LENGTH = 128;

  private FileArrivalChannelCode() {}

  public static boolean isValid(Object value) {
    return EmptyChecks.isNull(value)
        || value instanceof String code && code.length() <= CHANNEL_CODE_MAX_LENGTH;
  }
}
