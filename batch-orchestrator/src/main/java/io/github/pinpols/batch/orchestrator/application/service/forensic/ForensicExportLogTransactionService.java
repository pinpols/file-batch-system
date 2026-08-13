package io.github.pinpols.batch.orchestrator.application.service.forensic;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.ForensicExportLogEntity;
import io.github.pinpols.batch.orchestrator.mapper.ForensicExportLogMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 取证导出日志的独立事务边界。
 *
 * <p>PROCESSING 必须先于耗时的文件写入提交。这样即使进程在写 zip 时中断，运维仍能查询到一次未完成导出，
 * 而不会误以为请求从未到达。
 */
@Service
@RequiredArgsConstructor
class ForensicExportLogTransactionService {

  private final ForensicExportLogMapper forensicExportLogMapper;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void insertProcessingRow(
      ForensicExportRequest request, String exportId, String format, Instant requestedAt) {
    String scopeJson =
        JsonUtils.toJson(List.of("job_instances", "batch_day_operation_audits", "manifest"));
    String jobCodesJson =
        EmptyChecks.isEmpty(request.jobCodes()) ? null : JsonUtils.toJson(request.jobCodes());
    forensicExportLogMapper.insert(ForensicExportLogEntity.builder()
        .tenantId(request.tenantId())
        .exportId(exportId)
        .bizDateFrom(request.bizDateFrom())
        .bizDateTo(request.bizDateTo())
        .jobCodesJson(jobCodesJson)
        .scopeJson(scopeJson)
        .exportFormat(format)
        .status("PROCESSING")
        .requestedBy(Texts.hasText(request.requestedBy()) ? request.requestedBy() : "UNKNOWN")
        .requestedAt(requestedAt)
        .traceId(request.traceId())
        .build());
  }
}
