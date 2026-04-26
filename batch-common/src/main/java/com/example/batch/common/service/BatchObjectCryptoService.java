package com.example.batch.common.service;

import com.example.batch.common.config.BatchKmsProperties;
import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.utils.Texts;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 批处理对象加解密服务，基于 AES/GCM/NoPadding 算法对文件内容进行加密保护。 加密产物携带魔数（{@code BATCHENC}）、版本号、keyRef 和随机 IV，
 * {@code decryptIfNeeded} 通过检测魔数自动判断是否需要解密，对未加密内容透传。 密钥材料通过 {@link
 * com.example.batch.common.config.BatchKmsProperties} 以 Base64 形式配置， 当 {@code
 * BatchSecurityProperties.isBypassMode()} 为 {@code true} 时禁用加密（仅限测试环境）。
 */
public class BatchObjectCryptoService {

  private static final byte[] MAGIC = "BATCHENC".getBytes(StandardCharsets.US_ASCII);
  private static final byte VERSION = 1;
  private static final int GCM_TAG_BITS = 128;
  private static final int GCM_IV_BYTES = 12;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final BatchSecurityProperties securityProperties;
  private final BatchKmsProperties kmsProperties;

  public BatchObjectCryptoService(
      BatchSecurityProperties securityProperties, BatchKmsProperties kmsProperties) {
    this.securityProperties = securityProperties;
    this.kmsProperties = kmsProperties;
  }

  public boolean isBypassMode() {
    return securityProperties.isBypassMode();
  }

  public boolean shouldEncrypt(Map<String, Object> security) {
    return !securityProperties.isBypassMode()
        && truthy(security == null ? null : security.get("content_encryption_enabled"));
  }

  public String resolveKeyRef(Map<String, Object> security) {
    if (security != null) {
      Object keyRef = security.get("encryption_key_ref");
      if (keyRef != null && Texts.hasText(String.valueOf(keyRef))) {
        return String.valueOf(keyRef);
      }
    }
    return kmsProperties.getDefaultKeyRef();
  }

  public byte[] encrypt(byte[] plaintext, String keyRef) {
    if (plaintext == null) {
      return null;
    }
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      encrypt(new ByteArrayInputStream(plaintext), outputStream, keyRef);
      return outputStream.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("failed to encrypt bytes", exception);
    }
  }

  public byte[] decrypt(byte[] content) {
    if (content == null || content.length == 0 || !isEncrypted(content)) {
      return content;
    }
    try (InputStream inputStream = decryptIfNeeded(new ByteArrayInputStream(content))) {
      return inputStream.readAllBytes();
    } catch (IOException exception) {
      throw new IllegalStateException("failed to decrypt bytes", exception);
    }
  }

  /**
   * 加密产物的线格式（字节序）：
   *
   * <pre>
   *   MAGIC(8B) | VERSION(1B) | keyRef(UTF-8 长度前缀) | ivLen(1B) | IV(12B) | ciphertext+GCM_TAG
   * </pre>
   *
   * keyRef 写入归一化值（trim 或回落 defaultKeyRef），解密时用于从 KMS 查找对应密钥， 支持轮转：旧文件携带旧 keyRef，新文件携带新
   * keyRef，两套密钥可并存。
   */
  public void encrypt(InputStream plainInput, OutputStream encryptedOutput, String keyRef) {
    if (plainInput == null || encryptedOutput == null) {
      throw new IllegalArgumentException("plainInput and encryptedOutput are required");
    }
    try {
      byte[] iv = new byte[GCM_IV_BYTES];
      SECURE_RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(resolveKeyBytes(keyRef), "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, iv));
      DataOutputStream dataOutput = new DataOutputStream(encryptedOutput);
      dataOutput.write(MAGIC);
      dataOutput.writeByte(VERSION);
      dataOutput.writeUTF(normalizedKeyRef(keyRef));
      dataOutput.writeByte(iv.length);
      dataOutput.write(iv);
      dataOutput.flush();
      try (CipherOutputStream cipherOutput = new CipherOutputStream(encryptedOutput, cipher)) {
        plainInput.transferTo(cipherOutput);
      }
    } catch (Exception exception) {
      throw new IllegalStateException("failed to encrypt content", exception);
    }
  }

  public Path encrypt(Path source, Path target, String keyRef) {
    if (source == null || target == null) {
      throw new IllegalArgumentException("source and target are required");
    }
    try (InputStream inputStream = Files.newInputStream(source);
        OutputStream outputStream = Files.newOutputStream(target)) {
      encrypt(inputStream, outputStream, keyRef);
      return target;
    } catch (IOException exception) {
      throw new IllegalStateException("failed to encrypt file", exception);
    }
  }

  /**
   * 流式解密：若流前缀匹配魔数则返回 {@link CipherInputStream}，否则透传明文。
   *
   * <p><b>S-1.1 调用契约</b>：返回的流<b>必须被完整读取后 close</b>（或用 try-with-resources），close 时 GCM 底层会校验尾部 16B
   * tag，不匹配则抛异常。若调用方仅读部分数据后丢弃、未 close，则 tag 验证不触发，完整性保证失效。<b>生产环境严禁裸用该方法的返回值不 close</b>。
   *
   * <p>推荐模式：
   *
   * <pre>{@code
   * try (InputStream in = cryptoService.decryptIfNeeded(raw)) {
   *   consume(in);  // 读完后 try-with-resources 自动 close 触发 tag 校验
   * }
   * }</pre>
   *
   * <p>本方法已保证<b>setup 异常路径</b>必关闭底层 {@code inputStream}（之前存在 Cipher 初始化异常 但 inputStream 泄漏的窗口）。
   */
  public InputStream decryptIfNeeded(InputStream inputStream) {
    if (inputStream == null) {
      return InputStream.nullInputStream();
    }
    // S-1.1: setup 过程中任何异常都要关闭底层流，避免 MinIO 连接 / 文件句柄泄漏。
    // 返回成功后的 close 由调用方负责（CipherInputStream.close 会级联 close 底层流 + 校验 GCM tag）。
    boolean handedOff = false;
    try {
      // 用 PushbackInputStream 偷看头部字节，若不是魔数则 unread 还原，对调用方透明；
      // 缓冲 64B 远大于魔数 8B，预留版本头扩展空间
      PushbackInputStream pushbackInputStream = new PushbackInputStream(inputStream, 64);
      byte[] magic = pushbackInputStream.readNBytes(MAGIC.length);
      if (magic.length < MAGIC.length || !Arrays.equals(magic, MAGIC)) {
        if (magic.length > 0) {
          pushbackInputStream.unread(magic);
        }
        handedOff = true;
        return pushbackInputStream;
      }
      int version = pushbackInputStream.read();
      if (version != VERSION) {
        throw new IllegalStateException("unsupported encrypted object version: " + version);
      }
      DataInputStream dataInputStream = new DataInputStream(pushbackInputStream);
      String keyRef = dataInputStream.readUTF();
      int ivLength = dataInputStream.readUnsignedByte();
      byte[] iv = dataInputStream.readNBytes(ivLength);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(resolveKeyBytes(keyRef), "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, iv));
      CipherInputStream cipherInputStream = new CipherInputStream(pushbackInputStream, cipher);
      handedOff = true;
      return cipherInputStream;
    } catch (Exception exception) {
      throw new IllegalStateException("failed to open decrypted stream", exception);
    } finally {
      if (!handedOff) {
        try {
          inputStream.close();
        } catch (Exception ignored) {
          // best-effort: 下游已经抛出 IllegalStateException，关闭失败不再叠加
        }
      }
    }
  }

  private byte[] resolveKeyBytes(String keyRef) {
    String resolvedKeyRef = normalizedKeyRef(keyRef);
    String base64 = kmsProperties.getKeys().get(resolvedKeyRef);
    if (!Texts.hasText(base64)) {
      throw new IllegalStateException("missing kms key material for keyRef=" + resolvedKeyRef);
    }
    return Base64.getDecoder().decode(base64);
  }

  private String normalizedKeyRef(String keyRef) {
    return Texts.hasText(keyRef) ? keyRef.trim() : kmsProperties.getDefaultKeyRef();
  }

  private boolean truthy(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return value != null && "true".equalsIgnoreCase(String.valueOf(value));
  }

  private boolean isEncrypted(byte[] content) {
    if (content.length < MAGIC.length) {
      return false;
    }
    for (int index = 0; index < MAGIC.length; index++) {
      if (content[index] != MAGIC[index]) {
        return false;
      }
    }
    return true;
  }
}
