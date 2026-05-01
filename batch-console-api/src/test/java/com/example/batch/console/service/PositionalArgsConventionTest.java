package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 守护测试：CLAUDE.md §方法参数约束 "调用方约束" 子节落地。
 *
 * <p>对已治理的类型（{@link #GUARDED_TYPES}）拦回潮：
 *
 * <ol>
 *   <li>禁止 raw {@code new XxxParam(a, b, c, d, e, f, g)} 长构造（argc>6）
 *   <li>禁止 inline build：{@code mapper.x(XxxParam.builder()...build())}
 * </ol>
 *
 * <p>方案见 {@code docs/analysis/positional-args-cleanup-plan.md} v3。
 */
class PositionalArgsConventionTest {

  /** 已治理的类型白名单。新增治理类型追加到此即受守护。 */
  private static final Set<String> GUARDED_TYPES =
      Set.of(
          // Query records (PR-A 已纳入)
          "JobInstanceQuery",
          "JobDefinitionQuery",
          "JobStepInstanceQuery",
          "WorkflowEdgeQuery",
          "WorkflowNodeQuery",
          "WorkflowDefinitionQuery",
          "WorkflowRunQuery",
          "WorkflowNodeRunQuery",
          "FileTemplateConfigQuery",
          "FilePipelineQuery",
          "FileArrivalGroupQuery",
          "FileErrorRecordQuery",
          "ConsoleAiAuditLogQuery",
          "OutboxDeliveryLogQuery",
          "DeadLetterTaskQuery",
          "RetryScheduleQuery",
          "OutboxEventQuery",
          // 桶 ① 方法封装新增 Param records
          "FinishStepRunParam",
          "SubmitApprovalParam",
          "ParsedRecordWriteParam",
          "MarkFailedParam",
          // 桶 ② 加 @Builder 治理的类型（30 个）
          "ApprovalSubmitContext",
          "FileExecContext",
          "BadRecordContext",
          "DispatchHealthUpsertCommand",
          "PipelineStepTemplate",
          "NodeRunOutcome",
          "BatchDayAuditLogParam",
          "WebhookDeliveryLogInsertParam",
          "ArchivePolicyUpsertParam",
          "SecurityOptionsInput",
          "TaskOutcomeCommand",
          "ChildLaunchContext",
          "DagAdvanceContext",
          "TriggerStatusInfo",
          "SftpUploadContext",
          "FileGovernanceCommand",
          "ExportFormatContext",
          "DefinitionChangeContext",
          "ConsoleRealtimeDomainEvent",
          "AlertEmitRequest",
          "CreateSubscriptionCommand",
          "UpdateSubscriptionCommand",
          "PipelineStepDefinition",
          "ImportAuditContext",
          "JobDefinitionRow",
          "LaunchRequest",
          "CompensationSubmitCommand",
          "ArrivalGroupGovernanceCommand",
          // WorkerRegistryCache 内部 cache DTO
          "Entry");

  private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

  private static final List<String> SCAN_MODULES =
      List.of(
          "batch-common",
          "batch-trigger",
          "batch-orchestrator",
          "batch-worker-core",
          "batch-worker-import",
          "batch-worker-export",
          "batch-worker-process",
          "batch-worker-dispatch",
          "batch-console-api");

  /**
   * 规则 1：raw {@code new XxxParam(...)} 长构造（argc≥6）—— 拦白名单类型的 raw 位置参数构造。
   *
   * <p>采用"逗号数 ≥5 且左括号紧跟类型名"启发式：6 个参数对应 5 个逗号。
   */
  private static final Pattern RAW_LONG_CTOR =
      Pattern.compile("\\bnew\\s+([A-Z]\\w*)\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");

  /** 规则 2：inline build —— {@code <method>(... XxxParam.builder()...build() ...)} */
  private static final Pattern INLINE_BUILDER =
      Pattern.compile("[\\w.]+\\s*\\(\\s*([A-Z]\\w*)\\.builder\\s*\\(\\s*\\)");

  /** 扫 main + test 双路径（v4：test fixture 也走同一约束）。 */
  private static final List<String> SCAN_SCOPES = List.of("main", "test");

  @Test
  void noRawLongCtorOfGuardedType() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String module : SCAN_MODULES) {
      for (String scope : SCAN_SCOPES) {
        Path root = REPO_ROOT.resolve(module).resolve("src").resolve(scope).resolve("java");
        if (!Files.isDirectory(root)) {
          continue;
        }
        try (Stream<Path> stream = Files.walk(root)) {
          stream
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> !p.getFileName().toString().equals("PositionalArgsConventionTest.java"))
              .forEach(p -> collectRawCtorViolations(p, violations));
        }
      }
    }
    assertThat(violations)
        .as("禁止 raw `new XxxParam(...)` 长构造（argc≥6）出现在方法实参位置，请改用 builder + 提取引用变量")
        .isEmpty();
  }

  @Test
  void noInlineBuilderOfGuardedType() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String module : SCAN_MODULES) {
      for (String scope : SCAN_SCOPES) {
        Path root = REPO_ROOT.resolve(module).resolve("src").resolve(scope).resolve("java");
        if (!Files.isDirectory(root)) {
          continue;
        }
        try (Stream<Path> stream = Files.walk(root)) {
          stream
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> !p.getFileName().toString().equals("PositionalArgsConventionTest.java"))
              .forEach(p -> collectInlineBuilderViolations(p, violations));
        }
      }
    }
    assertThat(violations)
        .as("禁止 `f(XxxParam.builder()...build())` inline 在方法实参里 build；请先提取引用变量再传")
        .isEmpty();
  }

  private static int countTopLevelArgs(String args) {
    int depth = 0;
    int count = 1;
    for (char ch : args.toCharArray()) {
      if (ch == '(' || ch == '[' || ch == '<') {
        depth++;
      } else if (ch == ')' || ch == ']' || ch == '>') {
        depth--;
      } else if (ch == ',' && depth == 0) {
        count++;
      }
    }
    return count;
  }

  private void collectRawCtorViolations(Path file, List<String> sink) {
    String content;
    try {
      content = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return;
    }
    Matcher m = RAW_LONG_CTOR.matcher(content);
    while (m.find()) {
      String type = m.group(1);
      if (!GUARDED_TYPES.contains(type)) {
        continue;
      }
      String args = m.group(2).trim();
      if (args.isEmpty()) {
        continue;
      }
      int argc = countTopLevelArgs(args);
      if (argc < 6) {
        continue;
      }
      // 按方案 §3.5：仅拦"方法实参里 inline new"。
      // 排除赋值右侧 (`Type t = new Type(...)`) / return / 字段初始化 / throw。
      // 判定方式：往前回溯 'new' 之前最近的非空白字符；若是 `(` 或 `,` 即为方法实参位置。
      int newKeywordPos = content.lastIndexOf("new", m.start() + 4);
      if (newKeywordPos < 0) {
        continue;
      }
      String prefix = content.substring(0, newKeywordPos).stripTrailing();
      if (prefix.isEmpty()) {
        continue;
      }
      char lastCh = prefix.charAt(prefix.length() - 1);
      if (lastCh != '(' && lastCh != ',') {
        continue; // 赋值右侧 / return / 字段初始化 / throw 等，方案允许
      }
      int lineNo =
          (int) content.substring(0, m.start()).chars().filter(ch -> ch == '\n').count() + 1;
      sink.add(file + ":" + lineNo + " -> new " + type + "(...) argc=" + argc);
    }
  }

  private void collectInlineBuilderViolations(Path file, List<String> sink) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return;
    }
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      Matcher m = INLINE_BUILDER.matcher(line);
      while (m.find()) {
        String type = m.group(1);
        if (!GUARDED_TYPES.contains(type)) {
          continue;
        }
        // 排除赋值右侧（`Type t = Type.builder()...`）
        String prefix = line.substring(0, m.start()).stripTrailing();
        if (prefix.endsWith("=") || prefix.endsWith("return")) {
          continue;
        }
        sink.add(file + ":" + (i + 1) + " -> inline " + type + ".builder()");
      }
    }
  }
}
