package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.ConsoleFileChannelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.request.EnabledPatchRequest;
import com.example.batch.console.web.request.FileChannelCreateRequest;
import com.example.batch.console.web.request.FileChannelUpdateRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 文件通道（file_channel_config）CRUD REST 接口。 */
@RestController
@Validated
@RequestMapping("/api/console/file-channels")
@RequiredArgsConstructor
public class ConsoleFileChannelController {

  private final ConsoleFileChannelApplicationService fileChannelApplicationService;
  private final ConsoleResponseFactory responseFactory;

  /** 分页查询文件通道列表。 */
  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public CommonResponse<PageResponse<Map<String, Object>>> list(
      @Valid @ModelAttribute FileChannelQueryRequest request) {
    return responseFactory.success(fileChannelApplicationService.list(request));
  }

  /** 获取文件通道详情。 */
  @GetMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public CommonResponse<Map<String, Object>> get(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(fileChannelApplicationService.get(id, tenantId));
  }

  /** 新建文件通道。 */
  @PostMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<Map<String, Object>> create(
      @Valid @RequestBody FileChannelCreateRequest request) {
    return responseFactory.success(fileChannelApplicationService.create(request));
  }

  /** 更新文件通道。 */
  @PutMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<Map<String, Object>> update(
      @PathVariable Long id, @Valid @RequestBody FileChannelUpdateRequest request) {
    return responseFactory.success(fileChannelApplicationService.update(id, request));
  }

  /** 启用/禁用文件通道。 */
  @PatchMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<Void> patch(
      @PathVariable Long id, @Valid @RequestBody EnabledPatchRequest request) {
    fileChannelApplicationService.toggle(id, request.getTenantId(), request.getEnabled());
    return responseFactory.success(null);
  }
}
