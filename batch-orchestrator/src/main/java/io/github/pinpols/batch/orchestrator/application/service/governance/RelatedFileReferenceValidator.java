package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 验证运行时文件引用是否属于运行数据行所属的租户。 */
@Component
@RequiredArgsConstructor
public class RelatedFileReferenceValidator {

  private final FileGovernanceRepository fileGovernanceRepository;

  /** 文件不存在或属于其他租户时返回相同结果，避免枚举其他租户数据。 */
  public void validate(String tenantId, Long fileId) {
    if (EmptyChecks.isNull(fileId)) {
      return;
    }
    if (EmptyChecks.isNull(fileGovernanceRepository.loadFileRecord(tenantId, fileId))) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found");
    }
  }
}
