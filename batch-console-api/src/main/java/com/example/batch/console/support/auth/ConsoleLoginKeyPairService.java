package com.example.batch.console.support.auth;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.config.ConsoleSecurityProperties;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 控制台登录 RSA 密钥对管理 + AES-GCM 解密。
 *
 * <p>密钥优先级:
 *
 * <ol>
 *   <li>配置注入（{@code batch.console.security.login-encryption.private-key-pem} / {@code
 *       public-key-pem}） —— prod / staging / 多副本场景必走这条,helm Secret 提供
 *   <li>启动期生成 —— 单实例 dev / local 默认;helm 多副本时不能用（每个 pod 自己一对,FE 取到的公钥可能不匹配本次解密的私钥）
 * </ol>
 *
 * <p>RSA-2048 + OAEP-SHA256 包装 AES-256-GCM key。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleLoginKeyPairService {

  private static final String RSA_TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
  private static final String AES_TRANSFORM = "AES/GCM/NoPadding";
  private static final int GCM_TAG_BITS = 128;

  private final ConsoleSecurityProperties properties;

  private RSAPublicKey publicKey;
  private RSAPrivateKey privateKey;
  private String publicKeyPem;
  private String fingerprint;

  @PostConstruct
  void init() {
    if (!properties.getLoginEncryption().isEnabled()) {
      log.info("console login encryption disabled (login-encryption.enabled=false)");
      return;
    }
    String configuredPriv = properties.getLoginEncryption().getPrivateKeyPem();
    String configuredPub = properties.getLoginEncryption().getPublicKeyPem();
    if (!configuredPriv.isBlank() && !configuredPub.isBlank()) {
      this.privateKey = parsePrivateKey(configuredPriv);
      this.publicKey = parsePublicKey(configuredPub);
      this.publicKeyPem = normalizePem(configuredPub, "PUBLIC KEY");
      this.fingerprint = computeFingerprint(publicKey);
      log.info("console login RSA keypair loaded from configuration (fp={})", fingerprint);
      return;
    }
    KeyPair pair = generateKeyPair();
    this.publicKey = (RSAPublicKey) pair.getPublic();
    this.privateKey = (RSAPrivateKey) pair.getPrivate();
    this.publicKeyPem = encodePublicKey(publicKey);
    this.fingerprint = computeFingerprint(publicKey);
    log.warn(
        "console login RSA keypair generated at startup (fp={}); for multi-replica prod, set"
            + " batch.console.security.login-encryption.{private,public}-key-pem from Helm Secret",
        fingerprint);
  }

  /** 返回 PEM 编码的公钥;FE 取这个加密 login body。enabled=false 时返回 null,controller 给 404。 */
  public String publicKeyPem() {
    return publicKeyPem;
  }

  /** 公钥指纹（SHA-256 前 8 字节 hex）,用于 FE 缓存 / key 轮换识别。 */
  public String fingerprint() {
    return fingerprint;
  }

  /**
   * 解密前端发来的混合密文,返回原始 body 明文 JSON 字符串。
   *
   * @param encryptedAesKeyBase64 RSA-OAEP(aesKey) base64
   * @param ivBase64 AES-GCM 12 字节 IV base64
   * @param ciphertextBase64 AES-GCM(plaintext) base64
   */
  public String decrypt(String encryptedAesKeyBase64, String ivBase64, String ciphertextBase64) {
    if (privateKey == null) {
      throw BizException.of(ResultCode.UNAUTHORIZED, "error.auth.encryption_unavailable");
    }
    try {
      byte[] wrappedKey = Base64.getDecoder().decode(encryptedAesKeyBase64);
      byte[] iv = Base64.getDecoder().decode(ivBase64);
      byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);

      Cipher rsa = Cipher.getInstance(RSA_TRANSFORM);
      rsa.init(Cipher.DECRYPT_MODE, privateKey);
      byte[] aesKeyBytes = rsa.doFinal(wrappedKey);

      Cipher aes = Cipher.getInstance(AES_TRANSFORM);
      aes.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(aesKeyBytes, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, iv));
      byte[] plaintext = aes.doFinal(ciphertext);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw BizException.of(ResultCode.UNAUTHORIZED, "error.auth.encryption_failed");
    }
  }

  private KeyPair generateKeyPair() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      return gen.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA not available", e);
    }
  }

  private RSAPublicKey parsePublicKey(String pem) {
    try {
      byte[] der = Base64.getDecoder().decode(stripPem(pem, "PUBLIC KEY"));
      KeyFactory kf = KeyFactory.getInstance("RSA");
      return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(der));
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to parse batch.console.security.login-encryption.public-key-pem", e);
    }
  }

  private RSAPrivateKey parsePrivateKey(String pem) {
    try {
      byte[] der = Base64.getDecoder().decode(stripPem(pem, "PRIVATE KEY"));
      KeyFactory kf = KeyFactory.getInstance("RSA");
      return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to parse batch.console.security.login-encryption.private-key-pem", e);
    }
  }

  private String stripPem(String pem, String label) {
    return pem.replace("-----BEGIN " + label + "-----", "")
        .replace("-----END " + label + "-----", "")
        .replaceAll("\\s", "");
  }

  private String normalizePem(String pem, String label) {
    String body = stripPem(pem, label);
    StringBuilder sb = new StringBuilder();
    sb.append("-----BEGIN ").append(label).append("-----\n");
    for (int i = 0; i < body.length(); i += 64) {
      sb.append(body, i, Math.min(i + 64, body.length())).append('\n');
    }
    sb.append("-----END ").append(label).append("-----\n");
    return sb.toString();
  }

  private String encodePublicKey(PublicKey pk) {
    String base64 = Base64.getEncoder().encodeToString(pk.getEncoded());
    return normalizePem(base64, "PUBLIC KEY");
  }

  private String computeFingerprint(PublicKey pk) {
    try {
      byte[] sha = MessageDigest.getInstance("SHA-256").digest(pk.getEncoded());
      byte[] head = new byte[8];
      System.arraycopy(sha, 0, head, 0, 8);
      return HexFormat.of().formatHex(head);
    } catch (NoSuchAlgorithmException e) {
      return "unknown";
    }
  }
}
