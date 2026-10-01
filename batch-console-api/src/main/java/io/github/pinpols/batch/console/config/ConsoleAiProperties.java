package io.github.pinpols.batch.console.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Console AI 助手配置（{@code batch.console.ai}）。
 *
 * <p>AI 是 Console 控制面辅助能力，<b>不接触</b> orchestrator / worker / trigger 主链路。 输入边界：仅元数据 + 脱敏日志 +
 * 配置草稿；输出边界：仅建议 / 草稿 / 风险提示，不直接写入数据库。
 *
 * <p>详见 design/multi-tenant-and-security.md §11 + design/tech-stack-and-principles.md §4。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.ai")
public class ConsoleAiProperties {

  /** AI 总开关。生产高敏租户应关闭，避免数据出域。 */
  private boolean enabled = false;

  /**
   * 聊天模型提供方:{@code anthropic}(默认,走 Claude,推理质量更高)、{@code openai} 或 {@code
   * openai-compatible}。嵌入(RAG 向量化)始终走 {@code spring.ai.openai.embedding} 配置,与本项无关。
   */
  private Provider provider = Provider.ANTHROPIC;

  /** 跨 Provider 故障切换需显式启用，避免请求内容未经授权转发到其他模型服务。 */
  private boolean failoverEnabled = false;

  /** 仅在当前主模型通过真实图片流式探针后开启。 */
  private boolean imageInputEnabled = false;

  /** OpenAI-compatible 聊天端点配置,用于 DeepSeek、千问、智谱、Kimi、MiniMax 或私有兼容代理。 */
  private OpenaiCompatible openaiCompatible = new OpenaiCompatible();

  /** Prompt 长度上限（字符）。超长直接拒绝，避免成本失控 + DoS。 */
  private int maxPromptLength = 4000;

  /** 额外上下文序列化后的最大字符数。 */
  private int maxContextChars = 2000;

  /** 模型回复长度上限（字符）。超出截断。 */
  private int maxResponseLength = 3000;

  /** 单次请求最大输出 token；发送给 provider，作为成本上限而不是事后统计。 */
  private int maxCompletionTokens = 1024;

  /**
   * AI 对话每租户 + 每用户每分钟最大调用次数(滑动窗口)。AI 调用每次都烧 token + 调外部 LLM,比普通接口贵得多, 独立更严限流(默认 20/min)。复用 console
   * 现有 {@code SlidingWindowRateLimiter}(Redis);限流 key 含 tenantId 防跨租户压制。 超限返回 429 {@code
   * ResultCode.RATE_LIMITED}。设 &lt;= 0 关闭 AI 调用限流。
   */
  private int rateLimitPerMinute = 20;

  /** 每租户自然日最大 AI 请求数；小于等于 0 表示只做分钟限流，不启用日预算。 */
  private int dailyRequestLimit = 0;

  /** 日预算依赖 Redis 时是否在 Redis 不可用时拒绝请求。生产默认拒绝，避免成本护栏失效。 */
  private boolean budgetFailClosed = true;

  /**
   * 单次模型调用的最长等待时间。provider Java SDK 自带默认超时(约 10 分钟)不算无限等,但对 console UI 过长会拖住 Tomcat
   * 线程;这里在应用层再包一层更短的硬上限(默认 60s),超时 → 优雅降级(非 500)而非无限阻塞。
   */
  private Duration requestTimeout = Duration.ofSeconds(60);

  /** AI 审计保留天数；实际清理由显式开关控制，避免误删合规证据。 */
  private int auditRetentionDays = 365;

  /** 是否启用 AI 审计自动清理；默认关闭，生产需完成保留策略评审后再开启。 */
  private boolean auditRetentionEnabled = false;

  /** 允许使用 AI 的用户白名单（按 username）。空 list = 不限制（仅靠 authorities）。 */
  private List<String> allowedUsers = new ArrayList<>(List.of("admin"));

  /** 允许使用 AI 的角色白名单。 */
  private List<String> allowedAuthorities =
      new ArrayList<>(List.of(ConsoleRoles.ADMIN, ConsoleRoles.AUDITOR));

  /** 用于已识别平台问题的分类提示；单个通用词不能作为范围放行条件。 */
  private List<String> domainKeywords = new ArrayList<>(List.of(
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
      "readiness",
      "maven",
      "docker",
      "helm",
      "trivy",
      "changelog",
      "dependency",
      "version",
      "release",
      "script",
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
      "分片",
      "版本",
      "发布",
      "依赖",
      "门禁",
      "脚本"));

  /** 阻断关键词。Prompt 命中 → 直接 REJECTED_SAFETY，不发送到模型。覆盖密钥 / 越权类词。 */
  private List<String> blockedKeywords = new ArrayList<>(List.of(
      "password",
      "api key",
      "api-key",
      "secret",
      "token",
      "system prompt",
      "密钥",
      "密码",
      "口令",
      "私钥"));

  /** 检索增强(RAG)配置:把系统自身语料向量化后注入提示词,让模型基于事实作答。 */
  private Rag rag = new Rag();

  /** 服务端会话持久化；默认关闭，开启前必须配置正数保留期。 */
  private Persistence persistence = new Persistence();

  /** 私有图片上传边界；未经显式验收时能力保持关闭。 */
  private Image image = new Image();

  /** Provider token 单价与每租户月预算；预算为 0 时不执行费用预算，但已配置单价仍写入估算。 */
  private Cost cost = new Cost();

  /** 审计默认只存哈希和元数据；preview 仅在显式授权后开启。 */
  private boolean auditPreviewEnabled = false;

  /** 只读诊断工具(function-calling):让模型按需拉取实时 job 状态 / 日志 / 失败实例。 */
  private Tools tools = new Tools();

  @PostConstruct
  void validateGovernanceSettings() {
    if (image.getMaxFileBytes() < 1
        || image.getMaxFileBytes() > 20 * 1024 * 1024
        || image.getMaxImages() < 1
        || image.getMaxImages() > 8
        || image.getMaxTotalBytes() < image.getMaxFileBytes()
        || image.getMaxTotalBytes() > 40L * 1024 * 1024
        || image.getMaxSide() < 1
        || image.getMaxSide() > 8192
        || image.getMaxPixels() < 1
        || image.getMaxPixels() > 67_108_864L
        || image.getDraftRetentionHours() < 1
        || image.getDraftRetentionHours() > 168
        || image.getReservationTokensPerImage() < 1
        || image.getUploadLimitPerMinute() < 1
        || image.getMaxDraftsPerUser() < image.getMaxImages()
        || image.getMaxDraftBytesPerUser() < image.getMaxTotalBytes()
        || image.getMaxRetainedBytesPerUser() < image.getMaxDraftBytesPerUser()
        || image.getMaxRetainedBytesPerTenant() < image.getMaxRetainedBytesPerUser()) {
      throw new IllegalStateException("AI image limits are outside supported bounds");
    }
    if (persistence.isEnabled()
        && (persistence.getRetentionDays() < 1 || persistence.getRetentionDays() > 3650)) {
      throw new IllegalStateException("AI conversation retention must be between 1 and 3650 days");
    }
    if (persistence.getMaxHistoryTurns() < 1
        || persistence.getMaxHistoryChars() < 1
        || persistence.getConversationPageSize() < 1) {
      throw new IllegalStateException("AI conversation history and page limits must be positive");
    }
    if (EmptyChecks.isNull(cost.getMonthlyBudgetUsd())
        || cost.getMonthlyBudgetUsd().signum() < 0
        || EmptyChecks.isNull(cost.getReservationMargin())
        || cost.getReservationMargin().compareTo(BigDecimal.ONE) < 0) {
      throw new IllegalStateException(
          "AI cost budget must be nonnegative and reservation margin at least one");
    }
    boolean hasUsableRate = cost.getProviderRates().values().stream()
        .anyMatch(rate -> EmptyChecks.isNotNull(rate)
            && EmptyChecks.isNotNull(rate.getInputUsdPerMillionTokens())
            && EmptyChecks.isNotNull(rate.getOutputUsdPerMillionTokens())
            && (rate.getInputUsdPerMillionTokens().signum() > 0
                || rate.getOutputUsdPerMillionTokens().signum() > 0));
    if (cost.getMonthlyBudgetUsd().signum() > 0 && !hasUsableRate) {
      throw new IllegalStateException("AI monthly budget requires at least one provider rate");
    }
    cost.getProviderRates().forEach((providerName, rate) -> {
      if (EmptyChecks.isNull(providerName)
          || EmptyChecks.isBlank(providerName)
          || EmptyChecks.isNull(rate)
          || EmptyChecks.isNull(rate.getInputUsdPerMillionTokens())
          || EmptyChecks.isNull(rate.getOutputUsdPerMillionTokens())
          || rate.getInputUsdPerMillionTokens().signum() < 0
          || rate.getOutputUsdPerMillionTokens().signum() < 0) {
        throw new IllegalStateException(
            "AI provider rates require names and nonnegative input/output prices");
      }
    });
  }

  /** 支持的聊天模型提供方。使用枚举绑定确保拼写错误在应用启动期失败。 */
  public enum Provider {
    ANTHROPIC,
    OPENAI,
    OPENAI_COMPATIBLE
  }

  /** OpenAI-compatible 聊天端点参数。 */
  @Data
  public static class OpenaiCompatible {

    /** 审计与日志里展示的 provider 名称,例如 deepseek、qwen、zhipu、kimi、minimax 或 private-llm。 */
    private String providerName = "openai-compatible";

    /** 兼容 OpenAI Chat Completions 协议的基础地址。 */
    private String baseUrl = "";

    /** 兼容端点 API Key。生产必须通过密钥管理或环境变量注入。 */
    private String apiKey = "";

    /** 兼容端点聊天模型名。 */
    private String model = "";

    /** 仅对已验收的兼容端点与模型组合显式允许图片输入。 */
    private boolean imageInputEnabled = false;

    /** 兼容端点请求超时。 */
    private Duration timeout = Duration.ofSeconds(60);
  }

  @Data
  public static class Image {
    private int maxFileBytes = 5 * 1024 * 1024;
    private int maxImages = 4;
    private long maxTotalBytes = 12L * 1024 * 1024;
    private int maxSide = 4096;
    private long maxPixels = 16_777_216L;
    private int draftRetentionHours = 24;
    private int reservationTokensPerImage = 65_536;
    private int uploadLimitPerMinute = 12;
    private int maxDraftsPerUser = 16;
    private long maxDraftBytesPerUser = 48L * 1024 * 1024;
    private long maxRetainedBytesPerUser = 256L * 1024 * 1024;
    private long maxRetainedBytesPerTenant = 2L * 1024 * 1024 * 1024;
  }

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

  @Data
  public static class Persistence {

    private boolean enabled = false;

    /** 启用会话存储时必须显式设置为 1..3650 天。 */
    private int retentionDays = 0;

    private int maxHistoryTurns = 12;

    private int maxHistoryChars = 12000;

    private int conversationPageSize = 20;
  }

  @Data
  public static class Cost {

    private BigDecimal monthlyBudgetUsd = BigDecimal.ZERO;

    /** 预算预留系数，按提示词 UTF-8 字节数和最大输出令牌数估算。 */
    private BigDecimal reservationMargin = new BigDecimal("1.25");

    private Map<String, ProviderRate> providerRates = new LinkedHashMap<>();
  }

  @Data
  public static class ProviderRate {

    private BigDecimal inputUsdPerMillionTokens;

    private BigDecimal outputUsdPerMillionTokens;
  }
}
