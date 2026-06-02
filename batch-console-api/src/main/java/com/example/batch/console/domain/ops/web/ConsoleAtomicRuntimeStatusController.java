package com.example.batch.console.domain.ops.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.application.ConsoleAtomicRuntimeStatusService;
import com.example.batch.console.domain.ops.web.response.ConsoleAtomicRuntimeStatusResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Round-3 #8(Round-2 §4 P0 #8):暴露 atomic worker 4 个 executor 的 effective 安全门控配置, 给 Operator
 * 在/ops/atomic-runtime 菜单做对账(确认 prod 隐式 enforce-allowlist 真的生效)。
 *
 * <p>Operator 权限(=TENANT_ADMIN 起步,与 #248 ops 菜单同档)。Console 不直接 UPDATE/DELETE 任何下游状态,只反向 GET。
 */
@RestController
@RequestMapping("/api/console/ops")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
public class ConsoleAtomicRuntimeStatusController {

  private final ConsoleAtomicRuntimeStatusService runtimeStatusService;
  private final ConsoleResponseFactory responseFactory;

  /** GET /api/console/ops/atomic-runtime-status — 反向拉取 atomic worker 当前 effective 安全门控快照。 */
  @GetMapping("/atomic-runtime-status")
  public CommonResponse<ConsoleAtomicRuntimeStatusResponse> getAtomicRuntimeStatus() {
    return responseFactory.success(runtimeStatusService.fetch());
  }
}
