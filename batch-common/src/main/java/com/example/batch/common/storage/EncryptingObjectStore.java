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

  public EncryptingObjectStore(
      BatchObjectStore delegate,
      BatchObjectCryptoService cryptoService,
      BatchSecurityProperties securityProperties,
      String defaultKeyRef) {
    this.delegate = delegate;
    this.cryptoService = cryptoService;
    this.securityProperties = securityProperties;
    this.defaultKeyRef = defaultKeyRef;
  }

  @Override
  public void put(String bucket, String key, InputStream in, long size, String contentType) {
    if (securityProperties.isBypassMode()) {
      delegate.put(bucket, key, in, size, contentType);
      return;
    }
    // BATCHENC 加密产物体积与明文不等长（含 magic + header + GCM tag），且 InputStream 路径不易预先知道密文长度。
    // 此处先全量加密到内存缓冲再以确切 size 写入 delegate；明文 ≤ 几十 MB 场景（export / dispatch 错误集等）足够，
    // 与现有 cryptoService.encrypt(byte[]) 范式保持一致。
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    cryptoService.encrypt(in, out, defaultKeyRef);
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

  @Override
  public String presign(String bucket, String key, Duration ttl) {
    return delegate.presign(bucket, key, ttl);
  }
}
