package com.example.batch.console.infrastructure.job;

import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.console.application.ConsoleJobTriggerService;
import com.example.batch.console.infrastructure.query.ConsoleJobOpsSupport;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.web.request.job.TriggerRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 作业触发入口：通过 {@link ConsoleJobOpsSupport#delegateLaunch} 把 launch 请求转给 batch-trigger 服务。
 *
 * <p>三种入口：
 *
 * <ul>
 *   <li>{@link #trigger}：单次触发。入参 triggerType 缺省为 {@code MANUAL}。
 *   <li>{@link #dryRunTrigger}：只做前置校验（tenant / jobCode / bizDate 格式 / triggerType 合法 / job 存在且启用），
 *       不真正落 trigger——方便 UI 在提交前预检，避免失败才报错影响用户体验。
 *   <li>{@link #batchTrigger}：列表批量入口。逐项独立 try/catch，失败项不中断全批；支持混合 dryRun： 每项可单独带 {@code
 *       dryRun=true} 预检而其他项正常触发。每项 idempotencyKey 派生为 {@code {baseKey}:{index}}，保证批内子项幂等独立，避免全部共用同一
 *       key 导致 trigger 服务去重误杀。
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobTriggerService implements ConsoleJobTriggerService {

  private final ConsoleJobOpsSupport ops;
  private final JobDefinitionMapper jobDefinitionMapper;

  @Override
  public String trigger(TriggerRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    String result =
        ops.delegateLaunch(
            tenantId,
            ConsoleTextSanitizer.safeInput(request.getJobCode(), 128),
            request.getBizDate(),
            ops.resolveTriggerType(request.getTriggerType(), TriggerType.MANUAL),
            ops.parsePayload(request.getPayload()),
            idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  @Override
  public Map<String, Object> dryRunTrigger(TriggerRequest request) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("dryRun", true);
    List<String> errors = new ArrayList<>();
    String tenantId;
    try {
      tenantId = ops.resolveTenant(request.getTenantId());
    } catch (Exception e) {
      errors.add("tenantId invalid: " + e.getMessage());
      result.put("valid", false);
      result.put("errors", errors);
      return result;
    }
    result.put("tenantId", tenantId);
    result.put("jobCode", request.getJobCode());
    result.put("bizDate", request.getBizDate());

    if (request.getJobCode() == null || request.getJobCode().isBlank()) {
      errors.add("jobCode is required");
    }
    if (request.getBizDate() == null || request.getBizDate().isBlank()) {
      errors.add("bizDate is required");
    } else {
      try {
        ops.parseBizDate(request.getBizDate());
      } catch (Exception e) {
        errors.add("bizDate format invalid (expected yyyy-MM-dd)");
      }
    }
    if (request.getTriggerType() != null && !request.getTriggerType().isBlank()) {
      try {
        ops.resolveTriggerType(request.getTriggerType(), TriggerType.MANUAL);
      } catch (Exception e) {
        errors.add("unsupported triggerType: " + request.getTriggerType());
      }
    }
    if (errors.isEmpty() && request.getJobCode() != null) {
      var jobDef = jobDefinitionMapper.selectByUniqueKey(tenantId, request.getJobCode());
      if (jobDef == null) {
        errors.add("job definition not found: " + request.getJobCode());
      } else if (jobDef.getEnabled() != null && !jobDef.getEnabled()) {
        errors.add("job definition is disabled: " + request.getJobCode());
      }
    }
    result.put("valid", errors.isEmpty());
    if (!errors.isEmpty()) {
      result.put("errors", errors);
    }
    return result;
  }

  @Override
  public List<Map<String, Object>> batchTrigger(List<TriggerRequest> items, String idempotencyKey) {
    List<Map<String, Object>> results = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      TriggerRequest item = items.get(i);
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("index", i);
      entry.put("jobCode", item.getJobCode());
      entry.put("bizDate", item.getBizDate());
      try {
        if (item.isDryRun()) {
          Map<String, Object> dryRun = dryRunTrigger(item);
          entry.put("dryRun", true);
          entry.put(
              "status", Boolean.TRUE.equals(dryRun.get("valid")) ? "DRY_RUN_OK" : "DRY_RUN_FAILED");
          entry.put("result", dryRun);
        } else {
          String itemKey = idempotencyKey + ":" + i;
          String instanceNo = trigger(item, itemKey);
          entry.put("status", "SUCCESS");
          entry.put("instanceNo", instanceNo);
        }
      } catch (Exception e) {
        entry.put("status", "FAILED");
        entry.put("error", e.getMessage());
      }
      results.add(entry);
    }
    return results;
  }
}
