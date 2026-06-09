package com.example.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储加密装饰层开关（Phase 2 §5）。绑定前缀 {@code batch.storage.encryption}。
 *
 * <p>{@link #decoratorEnabled} 默认 {@code false}：阶段二零回归——现有 {@code StoreStep} 已自行 encrypt 文件再
 * put，若同时再启用 {@link com.example.batch.common.storage.EncryptingObjectStore} 装饰会双重加密。 future 把
 * manual encryption 迁移到装饰层后再切 {@code true}。
 */
@Data
@ConfigurationProperties(prefix = "batch.storage.encryption")
public class ObjectStoreEncryptionProperties {

  /**
   * 是否在 {@code BatchObjectStore} 装配链路最外层包装 {@link
   * com.example.batch.common.storage.EncryptingObjectStore}。
   */
  private boolean decoratorEnabled = false;

  /**
   * 加密装饰层当前使用内存缓冲生成密文，超过该阈值直接拒绝，避免大文件上传把 JVM 堆打满。
   *
   * <p>后续若改成临时文件或分片加密，可放宽该限制。
   */
  private long maxInMemoryEncryptBytes = 32L * 1024 * 1024;
}
