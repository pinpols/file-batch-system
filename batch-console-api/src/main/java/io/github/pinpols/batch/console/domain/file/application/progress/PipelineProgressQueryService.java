package io.github.pinpols.batch.console.domain.file.application.progress;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.file.infrastructure.query.ConsoleFileQueryService;
import io.github.pinpols.batch.console.domain.file.mapper.FilePipelineStepRunMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.shared.query.TenantScope;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 在租户快照内汇总 Pipeline 进度；任一对象读取失败时不返回部分结果。 */
@Service
@RequiredArgsConstructor
public class PipelineProgressQueryService {
  private final TenantIdResolver tenantGuard;
  private final FilePipelineStepRunMapper mapper;
  private final ConsoleFileQueryService files;

  public static List<Long> normalize(List<Long> ids) {
    if (EmptyChecks.isNull(ids)
        || EmptyChecks.isEmpty(ids)
        || ids.size() > 100
        || ids.stream().anyMatch(id -> EmptyChecks.isNull(id) || id <= 0))
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "pipelineInstanceIds");
    return ids.stream().distinct().sorted().toList();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PipelineProgressResponse query(String requestedTenant, List<Long> ids) {
    String resolvedTenant = tenantGuard.resolveTenant(requestedTenant);
    String requiredTenant = TenantScope.requireTenant(resolvedTenant);
    List<Long> normalizedIds = normalize(ids);
    return queryAuthorized(requiredTenant, normalizedIds);
  }
  /** 后台服务器采样显式设置RLS，不向客户端泄漏其他租户对象是否存在。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PipelineProgressResponse queryAuthorized(String tenant, List<Long> ids) {
    TenantScope.requireTenant(tenant);
    List<Long> scope = normalize(ids);
    mapper.setTenantContext(tenant);
    List<PipelineProgressResponse.Pipeline> pipelines =
        scope.stream().map(id -> files.pipelineSnapshotAuthorized(tenant, id)).toList();
    return new PipelineProgressResponse(tenant, BatchDateTimeSupport.utcNow(), pipelines);
  }
}
