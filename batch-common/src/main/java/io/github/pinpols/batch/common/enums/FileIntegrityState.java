package io.github.pinpols.batch.common.enums;

/** 文件原始字节完整性校验的已验证状态；仅在内容与 sidecar 清单匹配后写入文件元数据。 */
public enum FileIntegrityState {
  VERIFIED;

  public String code() {
    return name();
  }
}
