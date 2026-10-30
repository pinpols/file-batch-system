package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleBatchWindowApplicationService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchWindowCreateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchWindowUpdateRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchWindowResponse;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowInsertParam;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowUpdateParam;
import io.github.pinpols.batch.console.domain.job.support.BatchWindowRules;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.BatchWindowRow;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link ConsoleBatchWindowApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleBatchWindowApplicationService
    implements ConsoleBatchWindowApplicationService {

  private final BatchWindowMapper batchWindowMapper;
  private final TenantIdResolver tenantGuard;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;

  @Override
  public PageResponse<ConsoleBatchWindowResponse> list(
      String tenantId, String windowCode, Boolean enabled, int pageNo, int pageSize) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    PageRequest pageRequest = new PageRequest(pageNo, pageSize);
    long total = batchWindowMapper.countByQuery(resolved, windowCode, enabled);
    List<ConsoleBatchWindowResponse> items =
        batchWindowMapper.selectByQuery(resolved, windowCode, enabled, pageRequest).stream()
            .map(ConsoleBatchWindowResponse::from)
            .toList();
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  @Override
  public ConsoleBatchWindowResponse create(BatchWindowCreateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    BatchWindowRow existing =
        batchWindowMapper.selectByUniqueKey(tenantId, request.getWindowCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "window code already exists: " + request.getWindowCode());
    }
    BatchWindowInsertParam params = BatchWindowInsertParam.builder()
        .tenantId(tenantId)
        .windowCode(request.getWindowCode())
        .windowName(request.getWindowName())
        .timezone(request.getTimezone())
        .startTime(request.getStartTime())
        .endTime(request.getEndTime())
        .endStrategy(
            EmptyChecks.isNotNull(request.getEndStrategy())
                ? request.getEndStrategy()
                : "FINISH_RUNNING")
        .outOfWindowAction(
            EmptyChecks.isNotNull(request.getOutOfWindowAction())
                ? request.getOutOfWindowAction()
                : "WAIT")
        .allowCrossDay(
            EmptyChecks.isNotNull(request.getAllowCrossDay()) && request.getAllowCrossDay())
        .enabled(EmptyChecks.isNull(request.getEnabled()) || request.getEnabled())
        .description(request.getDescription())
        .build();
    BatchWindowRules.validate(
        params.getTimezone(),
        params.getStartTime(),
        params.getEndTime(),
        params.getAllowCrossDay());
    batchWindowMapper.insert(params);
    cacheInvalidationService.evictBatchWindow(tenantId, request.getWindowCode());
    long id = params.getId();
    return ConsoleBatchWindowResponse.from(batchWindowMapper.selectById(tenantId, id));
  }

  @Override
  public ConsoleBatchWindowResponse update(Long id, BatchWindowUpdateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    BatchWindowRow selectedWindow = batchWindowMapper.selectById(tenantId, id);
    BatchWindowRow existing = Guard.requireFound(selectedWindow, "batch window not found");
    String windowName = EmptyChecks.isNotNull(request.getWindowName())
        ? request.getWindowName()
        : existing.windowName();
    String timezone =
        EmptyChecks.isNotNull(request.getTimezone()) ? request.getTimezone() : existing.timezone();
    String effectiveStartTime = EmptyChecks.isNotNull(request.getStartTime())
        ? request.getStartTime()
        : existing.startTime();
    String effectiveEndTime =
        EmptyChecks.isNotNull(request.getEndTime()) ? request.getEndTime() : existing.endTime();
    BatchWindowUpdateParam params = BatchWindowUpdateParam.builder()
        .tenantId(tenantId)
        .id(id)
        .windowName(windowName)
        .timezone(timezone)
        .startTime(effectiveStartTime)
        .endTime(effectiveEndTime)
        .endStrategy(
            EmptyChecks.isNotNull(request.getEndStrategy())
                ? request.getEndStrategy()
                : existing.endStrategy())
        .outOfWindowAction(
            EmptyChecks.isNotNull(request.getOutOfWindowAction())
                ? request.getOutOfWindowAction()
                : existing.outOfWindowAction())
        .allowCrossDay(
            EmptyChecks.isNotNull(request.getAllowCrossDay())
                ? request.getAllowCrossDay()
                : existing.allowCrossDay())
        .enabled(
            EmptyChecks.isNotNull(request.getEnabled()) ? request.getEnabled() : existing.enabled())
        .description(
            EmptyChecks.isNotNull(request.getDescription())
                ? request.getDescription()
                : existing.description())
        .build();
    BatchWindowRules.validate(
        params.timezone(), params.startTime(), params.endTime(), params.allowCrossDay());
    batchWindowMapper.update(params);
    cacheInvalidationService.evictBatchWindow(tenantId, existing.windowCode());
    return ConsoleBatchWindowResponse.from(batchWindowMapper.selectById(tenantId, id));
  }

  @Override
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    int rows = batchWindowMapper.toggleEnabled(resolved, id, enabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_window.not_found");
    }
    BatchWindowRow window = batchWindowMapper.selectById(resolved, id);
    if (window != null) {
      cacheInvalidationService.evictBatchWindow(resolved, window.windowCode());
    }
  }
}
