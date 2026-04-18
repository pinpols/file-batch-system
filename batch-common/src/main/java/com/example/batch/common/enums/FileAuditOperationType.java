package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileAuditOperationType implements DictEnum {
  ARRIVAL_REGISTER("ARRIVAL_REGISTER", "到达登记"),
  RECEIVE_SCAN("RECEIVE_SCAN", "接收扫描"),
  IMPORT_FEEDBACK("IMPORT_FEEDBACK", "导入反馈"),
  BAD_RECORD_GOVERNANCE("BAD_RECORD_GOVERNANCE", "坏记录治理"),
  EXPORT_REGISTER("EXPORT_REGISTER", "导出登记"),
  EXPORT_COMPLETE("EXPORT_COMPLETE", "导出完成"),
  DISPATCH_COMPLETE("DISPATCH_COMPLETE", "分发完成"),
  DISPATCH_COMPENSATE("DISPATCH_COMPENSATE", "分发补偿"),
  CATCH_UP_APPROVAL("CATCH_UP_APPROVAL", "补跑审批"),
  BATCH_DAY_CATCH_UP("BATCH_DAY_CATCH_UP", "批次日补跑");

  private final String code;
  private final String label;
  public String label() { return label; }}
