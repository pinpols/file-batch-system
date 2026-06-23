package io.github.pinpols.batch.console.domain.ops.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.entity.AtomicTaskConfigEntity;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleAtomicTaskConfigService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * R3-5 / Round-1 TOP-8 — atomic 节点配置写库端点(FE 2-B 工作流编辑器侧后续接入,FE 留 follow-up)。
 *
 * <p>读路径:GET 按 (tenant_id, taskType) 列;写路径:POST 创建。schema 校验 / 凭据拒入由 {@link
 * ConsoleAtomicTaskConfigService} 回退,Controller 仅做参数收集 + 租户解析。
 *
 * <p>与 {@link ConsoleAtomicTaskTypeController}(静态 schema 目录)区分:本控制器面向"已保存的配置",带租户维度。
 */
@RestController
@RequestMapping("/api/console/ops/atomic-task-configs")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
public class ConsoleAtomicTaskConfigController {

  private final ConsoleAtomicTaskConfigService configService;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  /** GET /api/console/ops/atomic-task-configs?taskType=sql — 列本租户 + taskType 下已保存的配置。 */
  @GetMapping
  public CommonResponse<List<AtomicTaskConfigEntity>> list(
      @RequestParam("taskType") String taskType,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return responseFactory.success(configService.listByTaskType(resolved, taskType));
  }

  /**
   * POST /api/console/ops/atomic-task-configs — 创建一条 atomic 节点配置。
   *
   * <p>request body:{@code { tenantId?, taskType, name, parameters: {...} }}。 createdBy 由请求上下文
   * operatorId 自动填充,不接受外部传入。
   */
  @PostMapping
  public CommonResponse<AtomicTaskConfigEntity> create(@RequestBody CreateRequest request) {
    String resolved = tenantGuard.resolveTenant(request.tenantId());
    String operator = resolveOperator();
    AtomicTaskConfigEntity created =
        configService.create(
            resolved, request.taskType(), request.name(), request.parameters(), operator);
    return responseFactory.success(created);
  }

  private String resolveOperator() {
    ConsoleRequestMetadata metadata = requestMetadataResolver.current();
    return metadata == null ? null : metadata.operatorId();
  }

  /**
   * 创建请求体。tenantId 为空 → 由 {@link ConsoleTenantGuard} 回退(租户角色用 JWT,全局角色必传)。
   *
   * @param tenantId 可空,见上
   * @param taskType 内置原子 taskType(sql / stored_proc / shell / http)
   * @param name 同租户同 taskType 内唯一的配置名
   * @param parameters 节点参数;key 必须落 schema,凭据字段被拒入
   */
  public record CreateRequest(
      String tenantId, String taskType, String name, Map<String, Object> parameters) {}
}
