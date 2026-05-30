package com.example.batch.console.config;

import com.example.batch.console.domain.rbac.support.ConsoleRoles;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Console AI 助手配置（{@code batch.console.ai}）。
 *
 * <p>AI 是 Console 控制面辅助能力，<b>不接触</b> orchestrator / worker / trigger 主链路。 输入边界：仅元数据 + 脱敏日志 +
 * 配置草稿；输出边界：仅建议 / 草稿 / 风险提示，不直接落库。
 *
 * <p>详见 design/multi-tenant-and-security.md §11 + design/tech-stack-and-principles.md §4。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.ai")
public class ConsoleAiProperties {

  /** AI 总开关。生产高敏租户应关闭，避免数据出域。 */
  private boolean enabled = false;

  /** OpenAI 模型 ID。{@code gpt-4o-mini} = 默认低成本模型。 */
  private String model = "gpt-4o-mini";

  /** Prompt 长度上限（字符）。超长直接拒绝，避免成本失控 + DoS。 */
  private int maxPromptLength = 4000;

  /** 模型回复长度上限（字符）。超出截断。 */
  private int maxResponseLength = 3000;

  /** 允许使用 AI 的用户白名单（按 username）。空 list = 不限制（仅靠 authorities）。 */
  private List<String> allowedUsers = new ArrayList<>(List.of("admin"));

  /** 允许使用 AI 的角色白名单。 */
  private List<String> allowedAuthorities =
      new ArrayList<>(List.of(ConsoleRoles.ADMIN, ConsoleRoles.AUDITOR));

  /**
   * 域内关键词。Prompt 包含至少一个 → 判为 in-scope（PLATFORM/WORKFLOW/FILE_GOVERNANCE/OPERATIONS）；都不含 →
   * REJECTED_SCOPE。
   */
  private List<String> domainKeywords =
      new ArrayList<>(
          List.of(
              "batch",
              "workflow",
              "job",
              "instance",
              "partition",
              "task",
              "file",
              "dispatch",
              "import",
              "export",
              "orchestrator",
              "trigger",
              "worker",
              "pipeline",
              "dag",
              "retry",
              "dead letter",
              "dead-letter",
              "archive",
              "governance",
              "console",
              "audit",
              "reconcile",
              "归档",
              "重分发",
              "工作流",
              "文件",
              "调度",
              "导入",
              "导出",
              "重试",
              "死信",
              "节点",
              "分片"));

  /** 阻断关键词。Prompt 命中 → 直接 REJECTED_SAFETY，不发送到模型。覆盖密钥 / 越权类词。 */
  private List<String> blockedKeywords =
      new ArrayList<>(
          List.of(
              "password",
              "api key",
              "api-key",
              "secret",
              "token",
              "system prompt",
              "system prompt",
              "密钥",
              "密码",
              "口令",
              "私钥"));
}
