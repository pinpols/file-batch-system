package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

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
class ApprovalTypeRegistrationTest {

  @Test
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
  void fromCodeShouldRejectUnknownCode() {
    assertThat(ApprovalType.fromCode("UNKNOWN_TYPE")).isNull();
    assertThat(ApprovalType.fromCode(null)).isNull();
    assertThat(ApprovalType.fromCode("")).isNull();
  }

  @Test
  void everyEnumCodeMatchesEnumName() {
    for (ApprovalType type : ApprovalType.values()) {
      assertThat(type.code()).as("code 与 enum name 必须一致(便于 DB 字面量直读)").isEqualTo(type.name());
      assertThat(type.label()).as("label for %s", type.name()).isNotBlank();
    }
  }

  @Test
  void selfServiceShouldBeDeclared() {
    // 显式锁定 SELF_SERVICE 不被回退删除(典型回归点)
    assertThat(ApprovalType.SELF_SERVICE.code()).isEqualTo("SELF_SERVICE");
  }
}
