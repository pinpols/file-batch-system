package com.example.batch.common.storage;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.service.BatchObjectCryptoService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * 加密装饰层（Phase 2 §5）。叠在生产实现（{@link S3ObjectStore} / {@link FilesystemObjectStore}）之上：
 *
 * <ul>
 *   <li><b>put</b>：非 bypass-mode 时先用 {@link BatchObjectCryptoService#encrypt} 加密 InputStream 后再交
 *       delegate 写入；bypass-mode 直透。
 *   <li><b>get</b>：非 bypass-mode 时 {@link BatchObjectCryptoService#decryptIfNeeded}
 *       流式解密（魔数嗅探后透传明文）； bypass-mode 直透。
 *   <li><b>getFrom</b>：⚠ <b>始终抛 {@link UnsupportedOperationException}</b>。AES-GCM 是整对象加密、密文 offset
 *       ≠ 明文 offset，对加密对象做 range 读拿不到有意义的明文（设计 §5 写死的约束）。即便 bypass-mode 也不放行——避免误用。
 *   <li><b>copy / delete / statSize / exists / list / presign</b>：透传 delegate。
 * </ul>
 *
 * <p>不注册 {@code @Component}——由 {@code BatchObjectStoreAutoConfiguration} 在「加密装饰启用 + 非 bypass」时显式包裹
 * raw store。
 */
public class EncryptingObjectStore implements BatchObjectStore {

  private final BatchObjectStore delegate;
  private final BatchObjectCryptoService cryptoService;
  private final BatchSecurityProperties securityProperties;
  private final String defaultKeyRef;
  private final long maxInMemoryEncryptBytes;

  public EncryptingObjectStore(
      BatchObjectStore delegate,
      BatchObjectCryptoService cryptoService,
      BatchSecurityProperties securityProperties,
      String defaultKeyRef) {
    this(delegate, cryptoService, securityProperties, defaultKeyRef, 32L * 1024 * 1024);
  }

  public EncryptingObjectStore(
      BatchObjectStore delegate,
      BatchObjectCryptoService cryptoService,
      BatchSecurityProperties securityProperties,
      String defaultKeyRef,
      long maxInMemoryEncryptBytes) {
    this.delegate = delegate;
    this.cryptoService = cryptoService;
    this.securityProperties = securityProperties;
    this.defaultKeyRef = defaultKeyRef;
    this.maxInMemoryEncryptBytes = Math.max(1L, maxInMemoryEncryptBytes);
  }

  @Override
  public void put(String bucket, String key, InputStream in, long size, String contentType) {
    if (securityProperties.isBypassMode()) {
      delegate.put(bucket, key, in, size, contentType);
      return;
    }
    if (size < 0 || size > maxInMemoryEncryptBytes) {
      throw new ObjectStoreException(
          "encrypted object store put exceeds in-memory encryption limit: bucket=%s, key=%s, size=%d, limit=%d"
              .formatted(bucket, key, size, maxInMemoryEncryptBytes));
    }
    ExactSizeInputStream bounded =
        ExactSizeInputStream.exactAndBounded(
            in, "encrypted", bucket, key, size, maxInMemoryEncryptBytes);
    // BATCHENC 加密产物体积与明文不等长（含 magic + header + GCM tag），且 InputStream 路径不易预先知道密文长度。
    // 此处先全量加密到内存缓冲再以确切 size 写入 delegate；明文 ≤ 几十 MB 场景（export / dispatch 错误集等）足够，
    // 与现有 cryptoService.encrypt(byte[]) 范式保持一致。
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    cryptoService.encrypt(bounded, out, defaultKeyRef);
    bounded.verifyFullyRead();
    byte[] ciphertext = out.toByteArray();
    try (InputStream wrapped = new ByteArrayInputStream(ciphertext)) {
      delegate.put(bucket, key, wrapped, ciphertext.length, contentType);
    } catch (IOException ex) {
      throw new ObjectStoreException(
          "encrypting object store put failed to close ciphertext stream: bucket="
              + bucket
              + ", key="
              + key,
          ex);
    }
  }

  @Override
  public void copy(String bucket, String srcKey, String dstKey) {
    delegate.copy(bucket, srcKey, dstKey);
  }

  @Override
  public void delete(String bucket, String key) {
    delegate.delete(bucket, key);
  }

  @Override
  public InputStream get(String bucket, String key) {
    InputStream raw = delegate.get(bucket, key);
    if (securityProperties.isBypassMode()) {
      return raw;
    }
    return cryptoService.decryptIfNeeded(raw);
  }

  @Override
  public InputStream getFrom(String bucket, String key, long offset) {
    // §5 ⚠：AES-GCM 整对象加密，密文 offset ≠ 明文 offset；range 读拿不到有意义的明文。
    // 即便 bypass-mode 也不放行——避免误用（contract 一致比 bypass 透传更重要）。
    throw new UnsupportedOperationException(
        "range read (getFrom) is not supported on encrypted object store: bucket="
            + bucket
            + ", key="
            + key);
  }

  @Override
  public boolean supportsRangeRead() {
    // 与 getFrom 抛 UnsupportedOperationException 对称:整对象加密,密文 offset ≠ 明文 offset。
    // 调用方(如 PreprocessStep range-slice)据此回退整份顺序流。
    return false;
  }

  /**
   * ⚠ 返回的是<b>存储层(密文)字节数</b>,不等于明文字节数(BATCHENC 含 magic + header + GCM tag)。
   * 仅可用于「对象是否存在/近似大小」判断;<b>严禁</b>当明文长度做 offset/切片计算 (切片入口已由 {@link #supportsRangeRead()} 关死)。
   */
  @Override
  public long statSize(String bucket, String key) {
    return delegate.statSize(bucket, key);
  }

  @Override
  public boolean exists(String bucket, String key) {
    return delegate.exists(bucket, key);
  }

  @Override
  public ObjectListing list(String bucket, String prefix, String afterMarker, int maxKeys) {
    return delegate.list(bucket, prefix, afterMarker, maxKeys);
  }

  /**
   * ⚠ presign 返回的是存储层原文的直链——对加密对象,终端用户拿到的是<b>密文</b>。 加密文件的下载必须走 console 代理路径(服务端 {@link #get}
   * 解密后回传, 见 {@code DefaultFileGovernanceService} 按 {@code content_encryption_enabled} 的路由);
   * 此处保留透传是因为装饰层下可能存在历史/外部写入的明文对象,对它们 presign 仍合法。
   */
  @Override
  public String presign(String bucket, String key, Duration ttl) {
    return delegate.presign(bucket, key, ttl);
  }
}
