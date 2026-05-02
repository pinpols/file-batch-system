package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.entity.SystemParameterEntity;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.service.ConsoleSystemParameterService;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.web.Idempotent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 系统参数管理：运行时动态调整系统级参数（重试次数、超时阈值、并发度等），无需重启。 */
@RestController
@Validated
@RequestMapping("/api/console/system-parameters")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleSystemParameterController {

  private final ConsoleSystemParameterService parameterService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  @GetMapping
  public CommonResponse<List<SystemParameterEntity>> list(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(parameterService.list(tenantId));
  }

  @GetMapping("/value")
  public CommonResponse<Map<String, String>> getValue(
      @RequestParam("tenantId") String tenantId, @RequestParam("key") @NotBlank String key) {
    return responseFactory.success(
        parameterService
            .getValue(tenantId, key)
            .map(v -> Map.of("key", key, "value", v))
            .orElse(Map.of("key", key)));
  }

  @PutMapping
  public CommonResponse<Void> upsert(
      @RequestParam("tenantId") String tenantId, @RequestBody @Validated UpsertParam param) {
    String operator = requestMetadataResolver.current().operatorId();
    parameterService.upsert(tenantId, param.key(), param.value(), param.description(), operator);
    return responseFactory.success(null);
  }

  @DeleteMapping
  public CommonResponse<Void> delete(
      @RequestParam("tenantId") String tenantId, @RequestParam("key") @NotBlank String key) {
    parameterService.delete(tenantId, key);
    return responseFactory.success(null);
  }

  record UpsertParam(
      @NotBlank @Size(max = 128) String key,
      @NotBlank @Size(max = 2048) String value,
      @Size(max = 512) String description) {}
}
