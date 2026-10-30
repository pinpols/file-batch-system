package io.github.pinpols.batch.console.domain.workflow.application;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowExecutionProgressResponse;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowExecutionMapper;
import io.github.pinpols.batch.console.domain.workflow.view.WorkflowExecutionSnapshotView;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.shared.query.TenantScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkflowExecutionQueryService {
  private final TenantIdResolver tenantGuard;
  private final WorkflowExecutionMapper mapper;

  /** 同一只读事务下冻结图与当前状态一致，不能混入另一个租户或另一次刷新。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public WorkflowExecutionProgressResponse progress(String requestedTenant, Long id) {
    String tenantId = TenantScope.requireTenant(tenantGuard.resolveTenant(requestedTenant));
    return progressForAuthorizedTenant(tenantId, id);
  }

  /** 仅供已在订阅入口校验过身份的服务器推送使用；后台线程仍显式设置事务租户上下文。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public WorkflowExecutionProgressResponse progressForAuthorizedTenant(String tenantId, Long id) {
    TenantScope.requireTenant(tenantId);
    mapper.setTenantContext(tenantId);
    WorkflowExecutionSnapshotView run = mapper.selectSnapshot(tenantId, id);
    if (EmptyChecks.isNull(run))
      throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.run_not_found");
    return WorkflowExecutionGraphAssembler.assemble(run, mapper.selectLatestNodes(tenantId, id));
  }
}
