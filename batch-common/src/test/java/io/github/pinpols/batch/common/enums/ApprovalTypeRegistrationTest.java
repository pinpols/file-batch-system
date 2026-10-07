package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守护测试:防止 approval_command.approval_type 写入路径出现 enum 之外的字面量。
 *
 * <p>历史 bug(2026-06-03 deep-scan §P0-1):{@code ConsoleSelfServiceJobService.submit} 直接 {@code
 * body.put("approvalType", "SELF_SERVICE")},而 {@link ApprovalType} enum 当时只声明 4 项,FE 字典 + 静态守护
 * 都漏一项。本测试 锁 code-set 不被静默删除,新增 type 同时新增断言保持 in sync。
 *
 * <p>本测试不扫源码,只锁 enum 声明完整;源码侧字面量扫描走 ArchUnit / mapper XML 守护(本仓已有 MapperXmlTenantGuardArchTest 模式)。
 */
@DisplayName("ApprovalType 枚举: 审批类型编码声明的完整性与未知编码的解析拒绝")
class ApprovalTypeRegistrationTest {

  @Test
  @DisplayName("仓内实际写入的各类审批类型编码都能解析到枚举项,不遗漏声明")
  void shouldDeclareAllKnownApprovalTypes() {
    // 准备: 当前仓内实际写入 approval_command.approval_type 的全部 code(grep 验证)
    String[] knownCodes = {
      "CATCH_UP", "COMPENSATION", "DLQ_REPLAY", "DOWNLOAD", "SELF_SERVICE",
    };
    // 执行并断言
    for (String code : knownCodes) {
      assertThat(ApprovalType.fromCode(code))
          .as("ApprovalType 未声明 code=%s,FE 字典 / DB 字面量校验会漏", code)
          .isNotNull();
    }
  }

  @Test
  @DisplayName("未知编码 / 空值 / 空串 一律解析为空,不误匹配已有类型")
  void shouldReturnNull_whenCodeUnknownOrBlank() {
    assertThat(ApprovalType.fromCode("UNKNOWN_TYPE")).isNull();
    assertThat(ApprovalType.fromCode(null)).isNull();
    assertThat(ApprovalType.fromCode("")).isNull();
  }

  @Test
  @DisplayName("每项审批类型的编码与枚举名一致,且展示名称非空")
  void shouldKeepCodeEqualToEnumName_whenEnumeratingAllTypes() {
    for (ApprovalType type : ApprovalType.values()) {
      assertThat(type.code()).as("code 与 enum name 必须一致(便于 DB 字面量直读)").isEqualTo(type.name());
      assertThat(type.label()).as("label for %s", type.name()).isNotBlank();
    }
  }

  @Test
  @DisplayName("自助服务审批类型保持声明,防止回归时被删除")
  void shouldDeclareSelfServiceType_whenEnumScanned() {
    // 显式锁定 SELF_SERVICE 不被回退删除(典型回归点)
    assertThat(ApprovalType.SELF_SERVICE.code()).isEqualTo("SELF_SERVICE");
  }
}
