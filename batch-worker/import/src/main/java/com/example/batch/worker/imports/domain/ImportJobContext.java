package com.example.batch.worker.imports.domain;

import com.example.batch.worker.core.support.ExecutionContext;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * 单次导入作业的运行时上下文，贯穿解析、校验、写库等各阶段。 实现 {@link com.example.batch.worker.core.support.ExecutionContext}，
 * 通过 {@code attributes} Map 在流水线步骤间传递中间状态，避免方法参数耦合。
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
}
