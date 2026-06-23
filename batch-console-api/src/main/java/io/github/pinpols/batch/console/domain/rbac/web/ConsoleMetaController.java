package io.github.pinpols.batch.console.domain.rbac.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleMetaQueryService;
import io.github.pinpols.batch.console.domain.rbac.web.response.ConsoleMetaEnumItem;
import io.github.pinpols.batch.console.domain.rbac.web.response.ConsoleMetaOption;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/meta")
@PreAuthorize(
    "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsoleMetaController {

  private final ConsoleMetaQueryService queryService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/enums")
  public CommonResponse<Map<String, List<ConsoleMetaEnumItem>>> enums() {
    return responseFactory.success(queryService.enums());
  }

  @GetMapping("/queues")
  public CommonResponse<List<ConsoleMetaOption>> queues(@RequestParam("tenantId") String tenantId) {
    return responseFactory.success(queryService.queues(tenantId));
  }

  @GetMapping("/calendars")
  public CommonResponse<List<ConsoleMetaOption>> calendars(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(queryService.calendars(tenantId));
  }

  @GetMapping("/windows")
  public CommonResponse<List<ConsoleMetaOption>> windows(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(queryService.windows(tenantId));
  }

  @GetMapping("/worker-groups")
  public CommonResponse<List<ConsoleMetaOption>> workerGroups(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(queryService.workerGroups(tenantId));
  }

  @GetMapping("/biz-types")
  public CommonResponse<List<ConsoleMetaOption>> bizTypes(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(queryService.bizTypes(tenantId));
  }

  /**
   * Pipeline 9 stages 白名单（与 {@code ConfigPackageExcelValidator.STAGES_BY_TYPE} 一致）。 用于 FE
   * PipelineDefinitionList 步骤编辑器把 stageCode 改成下拉选择。
   */
  @GetMapping("/pipeline-stages")
  public CommonResponse<Map<String, List<String>>> pipelineStages() {
    return responseFactory.success(queryService.pipelineStages());
  }

  /**
   * step_registry 已注册 impl_code 白名单。传 module（IMPORT / EXPORT / PROCESS / DISPATCH）按模块过滤； 缺省返回全部。
   */
  @GetMapping("/step-impls")
  public CommonResponse<List<String>> stepImpls(
      @RequestParam(value = "module", required = false) String module) {
    return responseFactory.success(queryService.stepImpls(module));
  }
}
