package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleMetaQueryService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.response.auth.ConsoleMetaEnumItem;
import com.example.batch.console.web.response.auth.ConsoleMetaOption;
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
    "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN', 'ROLE_TENANT_USER')")
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
}
