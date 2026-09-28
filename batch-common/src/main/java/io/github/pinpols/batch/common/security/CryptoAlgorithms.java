package io.github.pinpols.batch.common.security;

/** JCA/JCE 算法名称常量，避免同一算法名在签名、摘要、加密路径中散落。 */
public final class CryptoAlgorithms {

  public static final String SHA_256 = "SHA-256";
  public static final String SHA_256_COMPACT = "SHA256";
  public static final String HMAC_SHA256 = "HmacSHA256";
  public static final String AES = "AES";
  public static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";

  private CryptoAlgorithms() {}
}
