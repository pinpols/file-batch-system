package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.utils.Texts;
import java.util.Map;

/**
 * ADR-041 Phase1.5:投递后回读校验的纯函数辅助。读 opt-in 开关 + 从 file_record 取期望大小,实际回读与比对在 {@code
 * DeliverDispatchStep} 经 {@link DispatchChannelGateway#readbackSize} 完成。默认关闭。
 */
public final class DispatchReadbackVerifier {

  private DispatchReadbackVerifier() {}

  /** {@code readback_verify_enabled=true} 时启用投递后回读对账。 */
  public static boolean enabled(Map<String, Object> channelConfig) {
    Object raw = channelConfig == null ? null : channelConfig.get("readback_verify_enabled");
    return raw != null && Boolean.parseBoolean(String.valueOf(raw).trim());
  }

  /** 从 file_record 取期望字节数(file_size_bytes / fileSizeBytes);缺省 / 非数字返回 null(不参与回读)。 */
  public static Long expectedSizeBytes(Map<String, Object> fileRecord) {
    if (fileRecord == null) {
      return null;
    }
    Object raw = fileRecord.get("file_size_bytes");
    if (raw == null) {
      raw = fileRecord.get("fileSizeBytes");
    }
    if (raw instanceof Number number) {
      return number.longValue();
    }
    if (raw == null || !Texts.hasText(String.valueOf(raw))) {
      return null;
    }
    try {
      return Long.parseLong(String.valueOf(raw).trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
