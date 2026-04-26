package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.ConsoleResourceQueueApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.Idempotent;
import com.example.batch.console.web.request.ResourceQueueCreateRequest;
import com.example.batch.console.web.request.ResourceQueueUpdateRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/console/queues")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleResourceQueueController {

  private final ConsoleResourceQueueApplicationService resourceQueueApplicationService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<PageResponse<Map<String, Object>>> list(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "queueCode", required = false) String queueCode,
      @RequestParam(value = "queueType", required = false) String queueType,
      @RequestParam(value = "enabled", required = false) Boolean enabled,
      @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
      @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
    return responseFactory.success(
        resourceQueueApplicationService.list(
            tenantId, queueCode, queueType, enabled, pageNo, pageSize));
  }

  @PostMapping
  public CommonResponse<Map<String, Object>> create(
      @Valid @RequestBody ResourceQueueCreateRequest request) {
    return responseFactory.success(resourceQueueApplicationService.create(request));
  }

  @PutMapping("/{id}")
  public CommonResponse<Map<String, Object>> update(
      @PathVariable Long id, @Valid @RequestBody ResourceQueueUpdateRequest request) {
    return responseFactory.success(resourceQueueApplicationService.update(id, request));
  }

  @PostMapping("/{id}/toggle")
  public CommonResponse<Void> toggle(
      @PathVariable Long id,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("enabled") Boolean enabled) {
    resourceQueueApplicationService.toggle(id, tenantId, enabled);
    return responseFactory.success(null);
  }
}
