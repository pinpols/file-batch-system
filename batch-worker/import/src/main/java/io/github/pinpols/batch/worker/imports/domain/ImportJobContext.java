package io.github.pinpols.batch.worker.imports.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.pinpols.batch.worker.core.support.ExecutionContext;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 单次导入作业的运行时上下文，贯穿解析、校验、写库等各阶段。 实现 {@link
 * io.github.pinpols.batch.worker.core.support.ExecutionContext}， 通过 {@code attributes} Map
 * 在流水线步骤间传递中间状态，避免方法参数耦合。
 */
@Data
public class ImportJobContext implements ExecutionContext {

  private String tenantId;
  private String jobCode;
  private String bizDate;
  private String fileId;
  private String workerId;
  private String rawPayload;
  private Map<String, Object> attributes = new LinkedHashMap<>();

  /** 仅供当前进程使用的文件句柄,不得进入持久化载荷、日志或执行快照。 */
  @JsonIgnore
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private ImportStagingFiles stagingFiles;
}
