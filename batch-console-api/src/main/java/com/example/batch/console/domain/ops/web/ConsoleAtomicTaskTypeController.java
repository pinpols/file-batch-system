package com.example.batch.console.domain.ops.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.dto.AtomicTaskTypeSchema;
import com.example.batch.console.domain.ops.service.ConsoleAtomicTaskTypeSchemaService;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FE 2-B：平台内置原子任务四类(sql / shell / stored_proc / http)的参数 schema + 安全闸,供 console 工作流编辑器渲染节点配置表单。
 *
 * <p>静态元数据,非租户维度(平台内置对所有租户一致),无 tenant 参数。安全闸只读展示(租户改不了,需找平台管理员)。 与自定义 taskType(走 registry 的 {@link
 * ConsoleCustomTaskTypeController})分治:内置走本端点静态 schema,自定义走 descriptor。
 */
@RestController
@RequestMapping("/api/console/atomic-task-types")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
public class ConsoleAtomicTaskTypeController {

  private final ConsoleAtomicTaskTypeSchemaService schemaService;
  private final ConsoleResponseFactory responseFactory;

  /** GET /api/console/atomic-task-types/schema — 四类内置原子任务的参数 schema + 安全闸。 */
  @GetMapping("/schema")
  public CommonResponse<List<AtomicTaskTypeSchema>> schema() {
    return responseFactory.success(schemaService.schema());
  }
}
