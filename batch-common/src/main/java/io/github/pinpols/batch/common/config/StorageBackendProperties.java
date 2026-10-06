package io.github.pinpols.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储后端选择配置（{@code batch.storage.backend}）。
 *
 * <p>把原先散落在启动守护、诊断端点和后端守卫里的 batch.storage.backend 字符串 key 读取收敛到类型安全配置。
 * {@code batch.storage} 前缀下更细的子域（{@code batch.storage.s3} / {@code batch.storage.filesystem} /
 * {@code batch.storage.encryption} / {@code batch.storage.backend-guard}）仍由各自配置类持有，本类只绑定后端选择这一个字段。
 */
@Data
@ConfigurationProperties(prefix = "batch.storage")
public class StorageBackendProperties {

  /** 完整 key；供启动守护、诊断端点与测试夹具复用，避免字面量在多处漂移。 */
  public static final String BACKEND_KEY = "batch.storage.backend";

  /** 后端标识：{@code s3}（默认）或 {@code filesystem}。 */
  private String backend = "s3";
}
