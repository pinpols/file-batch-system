package com.example.batch.common.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * 对象存储统一抽象（Phase 2 §1 定稿契约，共 9 方法）。
 *
 * <p>收敛全系统对底层对象存储 SDK（当前为 MinIO/S3）的直连。生产实现为 {@link S3ObjectStore}，覆盖 S3 协议全系（MinIO / AWS S3 / 阿里
 * OSS / 腾讯 COS / GCS）；本地文件系统后端为阶段二内容，此处不实现。
 *
 * <p>异常约定：所有方法在底层失败时抛 {@link ObjectStoreException} 体系——对象不存在抛 {@link ObjectNotFoundException}，
 * 权限/认证失败抛 {@link ObjectStoreAccessException}，其余抛 {@link ObjectStoreException}。
 */
public interface BatchObjectStore {

  /**
   * 写入对象。
   *
   * <p>调用方负责关闭传入的 {@link InputStream}；实现类只读取，不接管流生命周期。{@code size >= 0} 时实现类必须按精确长度写入，底层读到的字节数不匹配应抛
   * {@link ObjectStoreException}。
   */
  void put(String bucket, String key, InputStream in, long size, String contentType);

  /** 同 bucket 内服务端复制对象。 */
  void copy(String bucket, String srcKey, String dstKey);

  /** 删除对象。 */
  void delete(String bucket, String key);

  /** 读取整对象顺序流（下载 / sha256 / export 用）。 */
  InputStream get(String bucket, String key);

  /** 从 {@code offset} 起的正向流（range-slice 用）；调用方读多少、何时停由其自定。 */
  InputStream getFrom(String bucket, String key, long offset);

  /**
   * 是否支持按明文 offset 的 range 读（{@link #getFrom}）。加密装饰层（{@link EncryptingObjectStore}）整对象加密， 密文 offset
   * ≠ 明文 offset，返回 {@code false}；需要 range-slice 的调用方（如 import 分片直载）应先探测本方法， 不支持时回退整份顺序流，而不是调 {@code
   * getFrom} 砸 {@link UnsupportedOperationException}。
   */
  default boolean supportsRangeRead() {
    return true;
  }

  /** 返回对象字节大小。 */
  long statSize(String bucket, String key);

  /** 对象是否存在。 */
  boolean exists(String bucket, String key);

  /**
   * 分页惰性列举。从 {@code afterMarker}（不含）起，前缀匹配 {@code prefix}，最多取 {@code maxKeys} 条； {@link
   * ObjectListing#nextMarker()} 为 {@code null} 表示末页。
   */
  ObjectListing list(String bucket, String prefix, String afterMarker, int maxKeys);

  /** 生成 GET 预签名下载 URL，有效期 {@code ttl}。 */
  String presign(String bucket, String key, Duration ttl);
}
