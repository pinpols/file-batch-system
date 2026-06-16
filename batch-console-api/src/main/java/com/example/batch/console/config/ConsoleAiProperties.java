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

  /**
   * 聊天模型提供方:{@code anthropic}(默认,走 Claude,推理质量更高)或 {@code openai}。 嵌入(RAG 向量化)始终走 OpenAI
   * embedding,与本项无关。
   */
  private String provider = "anthropic";

  /** 聊天模型 ID(仅用于审计记录展示;实际模型由对应 provider 的 spring.ai 配置决定)。 */
  private String model = "claude-opus-4-8";

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

  /** 检索增强(RAG)配置:把系统自身语料向量化后注入提示词,让模型基于事实作答。 */
  private Rag rag = new Rag();

  /** 只读诊断工具(function-calling):让模型按需拉取实时 job 状态 / 日志 / 失败实例。 */
  private Tools tools = new Tools();

  /** L3 工具调用参数。 */
  @Data
  public static class Tools {

    /** 工具调用开关。开启后模型可调用只读查询工具(强制限定当前租户)诊断实时状态。 */
    private boolean enabled = true;

    /** 单次工具查询返回的最大行数(日志 / 失败实例列表),控制 token 与噪声。 */
    private int maxRows = 10;
  }

  /** RAG 检索参数。 */
  @Data
  public static class Rag {

    /** RAG 开关。开启需 OpenAI embedding 可用;不可用会自动降级为「仅 primer」回答,不影响整体可用。 */
    private boolean enabled = true;

    /** 每次注入的最相关片段数。 */
    private int topK = 4;

    /** 余弦相似度阈值,低于此值的片段视为不相关、丢弃。 */
    private double minScore = 0.5;

    /** 注入提示词的检索上下文总字符上限,超出按片段顺序截断,防止 token 失控。 */
    private int maxContextChars = 6000;

    /** 知识库语料位置(Spring Resource pattern)。默认内置知识包;可追加挂载的 docs 目录。 */
    private List<String> locations = new ArrayList<>(List.of("classpath:ai-knowledge/*.md"));
  }
}
