package io.github.pinpols.batch.worker.dispatchs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dispatch 外部文件通道的运行时保护参数。 */
@Data
@ConfigurationProperties(prefix = "batch.worker.dispatch.runtime")
public class DispatchRuntimeProperties {

  /** NAS 路径沙箱；为空时保留兼容模式，仅记录 symlink 告警。 */
  private String nasSandboxRoot = "";

  /** LOCAL outbox 路径沙箱；为空时保留本地开发兼容模式。 */
  private String localSandboxRoot = "";

  /** NAS 阻塞复制的最长等待时间，单位秒。 */
  private long nasCopyTimeoutSeconds = 300L;

  /** OSS 内联上传的最大对象大小，单位 MiB。 */
  private int ossMaxInlineMib = 512;

  /** HTTP/SMTP 健康探测的连接超时，单位毫秒。 */
  private long probeConnectTimeoutMillis = 5_000L;

  /** HTTP 健康探测的读取超时，单位毫秒。 */
  private long probeReadTimeoutMillis = 5_000L;
}
