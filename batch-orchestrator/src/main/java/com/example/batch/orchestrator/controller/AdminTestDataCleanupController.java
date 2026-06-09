package com.example.batch.orchestrator.controller;

import com.example.batch.common.config.BatchProfileSupport;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.application.service.governance.AdminTestDataCleanupService;
import jakarta.validation.constraints.Pattern;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试数据清理内部接口。
 *
 * <p>console-api 只保留 admin 入口和审计，实际 DELETE/UPDATE 运行态表由 orchestrator 执行，避免 console 越过 “orchestrator
 * 是唯一状态主机”的边界。
 */
@RestController
@Validated
@RequestMapping("/internal/admin/test-data")
@RequiredArgsConstructor
public class AdminTestDataCleanupController {

  private static final String PREFIX_PATTERN = "^[a-zA-Z][a-zA-Z0-9-]{2,32}$";

  private final AdminTestDataCleanupService cleanupService;
  private final Environment environment;

  @DeleteMapping
  public Map<String, Integer> cleanup(
      @RequestParam
          @Pattern(
              regexp = PREFIX_PATTERN,
              message = "prefix 必须字母开头,3-33 字符,只能含字母/数字/-(禁 _,SQL LIKE 单字符通配符)")
          String prefix) {
    rejectProductionProfile();
    if (prefix == null || prefix.isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.required");
    }
    return cleanupService.cleanupByPrefix(prefix);
  }

  @DeleteMapping("/by-ids")
  public Map<String, Integer> cleanupByExactIds(
      @RequestParam
          @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_,-]{2,255}$", message = "ids 必须字母开头,逗号分隔,长度 3-256")
          String ids) {
    rejectProductionProfile();
    if (ids == null || ids.isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.required");
    }
    List<String> tenantIds =
        Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    return cleanupService.cleanupByExactTenantIds(tenantIds);
  }

  private void rejectProductionProfile() {
    if (BatchProfileSupport.isProductionProfile(environment)) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.admin.test_data_cleanup_prod_forbidden");
    }
  }
}
