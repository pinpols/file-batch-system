package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ApprovalType implements DictEnum {
  CATCH_UP("CATCH_UP", "补跑"),
  COMPENSATION("COMPENSATION", "补偿"),
  DLQ_REPLAY("DLQ_REPLAY", "死信重放"),
  DOWNLOAD("DOWNLOAD", "下载"),
  // 2026-06-03: ConsoleSelfServiceJobService.submit 写入
  // approval_command.approval_type="SELF_SERVICE"
  // 但 enum 未声明，导致 ConsoleMetaQueryService 返回的 FE 字典缺这一项 + DB 字面量无静态守护。
  // 见 docs/analysis/2026-06-03-deep-scan-be-business-ops.md P0-1。
  SELF_SERVICE("SELF_SERVICE", "自助补救");

  private final String code;
  private final String label;

  /** 反查：用于 ApprovalCommand 写入路径做回退校验，未知 code 返回 null。 */
  public static ApprovalType fromCode(String code) {
    if (code == null) {
      return null;
    }
    for (ApprovalType type : values()) {
      if (type.code.equals(code)) {
        return type;
      }
    }
    return null;
  }
}
