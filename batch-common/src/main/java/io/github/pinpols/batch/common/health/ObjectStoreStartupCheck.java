package io.github.pinpols.batch.common.health;

import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.ObjectListing;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 对象存储启动冒烟自检:启动期对配置 bucket 真做一遍 put → exists → statSize → get → list → deleteMany 探针,
 * <b>fail-fast</b>(任一步不符即抛 {@link IllegalStateException},boot 失败)。
 *
 * <p>价值:把「换了对象存储后端(MinIO→SeaweedFS/RustFS 等)跑起来才发现不兼容/endpoint 错/bucket 无权限/path-style 或 checksum
 * 没配对」从<b>运行时惊吓</b>变成<b>启动期失败</b>。走 {@link BatchObjectStore} 抽象(含加密装饰层),验的是业务真用的整条路径, 不是裸 S3Client。
 *
 * <p>探针对象写在保留前缀 {@code __batch-startup-probe__/} 下、随机 key,自检完即删;即便中途失败也尽力清理。 由 {@code
 * BatchObjectStoreAutoConfiguration} 在 {@code batch.storage.startup-check.enabled=true}(默认)且存在
 * {@link BatchObjectStore} bean 时装配。
 */
@Slf4j
public class ObjectStoreStartupCheck implements ApplicationRunner {

  private static final String PROBE_PREFIX = "__batch-startup-probe__/";

  private final BatchObjectStore objectStore;
  private final String bucket;

  public ObjectStoreStartupCheck(BatchObjectStore objectStore, String bucket) {
    this.objectStore = objectStore;
    this.bucket = bucket;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (bucket == null || bucket.isBlank()) {
      throw new IllegalStateException(
          "object store startup check: bucket 未配置(batch.storage.s3.bucket)");
    }
    String key = PROBE_PREFIX + UUID.randomUUID();
    byte[] payload = ("batch-startup-probe-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    try {
      objectStore.put(bucket, key, new ByteArrayInputStream(payload), payload.length, "text/plain");

      if (!objectStore.exists(bucket, key)) {
        throw fail("put 后 exists 返回 false", key, null);
      }
      long size = objectStore.statSize(bucket, key);
      // 用 >= 而非 ==:加密装饰层(EncryptingObjectStore)的 statSize 返回密文字节数(含 magic+header+GCM tag),
      // 严格大于明文长度;这里只兜底「截断/空写」这类粗粒度故障,内容正确性由下面 get 解密后全等兜底。
      if (size < payload.length) {
        throw fail("statSize 偏小(明文 " + payload.length + " 字节,实得 " + size + ")疑似截断", key, null);
      }
      try (InputStream in = objectStore.get(bucket, key)) {
        byte[] got = in.readAllBytes();
        if (!Arrays.equals(got, payload)) {
          throw fail("get 取回内容与写入不一致", key, null);
        }
      }
      ObjectListing listing = objectStore.list(bucket, PROBE_PREFIX, null, 100);
      boolean listed = listing.objects().stream().anyMatch(o -> key.equals(o.key()));
      if (!listed) {
        throw fail("list 未列出刚写入的探针对象", key, null);
      }
      log.info(
          "object store startup check passed: bucket={}, backend reachable + put/get/list/delete"
              + " OK",
          bucket);
    } catch (IllegalStateException ex) {
      throw ex;
    } catch (Exception ex) {
      throw fail("探针 I/O 失败:" + ex.getMessage(), key, ex);
    } finally {
      // 尽力清理探针对象(即便上面失败)。
      try {
        objectStore.deleteMany(bucket, List.of(key));
      } catch (RuntimeException cleanupEx) {
        log.warn(
            "object store startup probe cleanup failed: bucket={}, key={}", bucket, key, cleanupEx);
      }
    }
  }

  private IllegalStateException fail(String reason, String key, Exception cause) {
    String msg =
        "object store startup check failed("
            + reason
            + "):bucket="
            + bucket
            + ", probeKey="
            + key
            + "。请核对 endpoint/凭据/bucket 权限,以及该 S3 兼容后端是否需要 path-style / checksum 配置。";
    return cause == null ? new IllegalStateException(msg) : new IllegalStateException(msg, cause);
  }
}
