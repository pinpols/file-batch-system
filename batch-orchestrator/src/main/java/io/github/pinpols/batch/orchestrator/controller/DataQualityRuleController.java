package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import io.github.pinpols.batch.orchestrator.mapper.DataQualityRuleMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-021 DQ 规则 CRUD 内部控制器：{@code /internal/orchestrator/dq/rules}
 *
 * <p>console-api 通过 ConsoleOrchestratorProxyService 转发；规则的 CRUD 操作触发 audit_log（待 ADR-021 §Console /
 * Audit 行实现）。
 *
 * <p><b>边界红线</b>（priority-scope §ADR-021 ❌）：拒收"裁定业务对错 / 主数据 / 数据治理 / 跨系统业务语义仲裁"类规则；评审需对照 ADR-021
 * §范围边界 表的"修业务数据 vs 裁定业务对错"判定提问。
 */
@RestController
@RequestMapping("/internal/orchestrator/dq/rules")
@RequiredArgsConstructor
public class DataQualityRuleController {

  private final DataQualityRuleMapper ruleMapper;

  /** 按 (tenantId, businessKey 前缀) 列出 enabled 规则。 */
  @GetMapping
  public CommonResponse<List<DataQualityRuleEntity>> list(
      @RequestParam("tenantId") String tenantId, @RequestParam("businessKey") String businessKey) {
    return CommonResponse.success(ruleMapper.selectEnabledByBusinessKey(tenantId, businessKey));
  }

  @GetMapping("/{id}")
  public CommonResponse<DataQualityRuleEntity> get(@PathVariable("id") Long id) {
    return CommonResponse.success(ruleMapper.selectById(id));
  }

  @PostMapping
  public CommonResponse<DataQualityRuleEntity> create(@RequestBody DataQualityRuleEntity rule) {
    ruleMapper.insert(rule);
    return CommonResponse.success(rule);
  }

  @PutMapping("/{id}")
  public CommonResponse<DataQualityRuleEntity> update(
      @PathVariable("id") Long id, @RequestBody DataQualityRuleEntity rule) {
    rule.setId(id);
    ruleMapper.update(rule);
    return CommonResponse.success(rule);
  }
}
