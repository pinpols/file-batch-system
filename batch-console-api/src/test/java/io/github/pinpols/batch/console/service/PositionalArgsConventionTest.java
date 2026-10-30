package io.github.pinpols.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守护测试：docs/agent-baseline.md §方法参数约束 "调用方约束" 子节落地。
 *
 * <p>对已治理的类型（{@link #GUARDED_TYPES}）拦回潮：
 *
 * <ol>
 *   <li>禁止 raw {@code new XxxParam(a, b, c, d, e, f, g)} 长构造（argc>6）
 *   <li>禁止 inline build：{@code mapper.x(XxxParam.builder()...build())}
 *   <li>禁止已治理 Query 在生产调用侧用多个 {@code null} 拼位置构造
 *   <li>状态型参数对象不得重新暴露公共构造器或通用 builder
 * </ol>
 *
 * <p>现行规则见 {@code docs/coding-conventions.md} §1。
 */
@DisplayName("方法位置参数规约守护: 已治理类型禁止构造与空值组合回潮")
class PositionalArgsConventionTest {

  private static final Set<String> CONTROLLED_FACTORY_TYPE_PATHS = Set.of(
      "batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/param/UpdateBatchDayReplayEntryStatusParam.java",
      "batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/param/UpdateBatchDayReplaySessionStatusParam.java");

  private static final Set<String> GUARDED_QUERY_TYPES = Set.of(
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
      "OutboxEventQuery");

  /** 已治理的类型白名单。新增治理类型追加到此即受守护。 */
  private static final Set<String> GUARDED_TYPES = Set.of(
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
      "UpdateBatchDayReplayEntryStatusParam",
      "UpdateBatchDayReplaySessionStatusParam",
      // WorkerRegistryCache 内部 cache DTO
      "Entry");

  private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

  private static final List<String> SCAN_MODULES = List.of(
      "batch-common",
      "batch-trigger",
      "batch-orchestrator",
      "batch-worker/core",
      "batch-worker/import",
      "batch-worker/export",
      "batch-worker/process",
      "batch-worker/dispatch",
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
  @DisplayName("已治理类型不得在方法实参位置出现六个及以上参数的位置构造")
  void shouldRejectRawLongConstructor_whenGuardedTypeUsedInArguments() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String module : SCAN_MODULES) {
      for (String scope : SCAN_SCOPES) {
        Path root = REPO_ROOT.resolve(module).resolve("src").resolve(scope).resolve("java");
        assertThat(root)
            .as(
                "扫描目录必须存在：%s（worker 的目录是 batch-worker/<name>，不是 artifactId"
                    + " batch-worker-<name>；路径写错会静默漏扫整段模块）",
                root)
            .isDirectory();
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
  @DisplayName("已治理类型不得在方法实参位置内联构建, 需先提取引用变量再传参")
  void shouldRejectInlineBuilder_whenGuardedTypeUsedInArguments() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String module : SCAN_MODULES) {
      for (String scope : SCAN_SCOPES) {
        Path root = REPO_ROOT.resolve(module).resolve("src").resolve(scope).resolve("java");
        assertThat(root)
            .as(
                "扫描目录必须存在：%s（worker 的目录是 batch-worker/<name>，不是 artifactId"
                    + " batch-worker-<name>；路径写错会静默漏扫整段模块）",
                root)
            .isDirectory();
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

  @Test
  @DisplayName("已治理 Query 的生产调用侧不得用多个 null 拼位置构造")
  void shouldRejectMultipleNullQueryConstructorOutsideQueryFactory() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String module : SCAN_MODULES) {
      Path root = REPO_ROOT.resolve(module).resolve("src/main/java");
      try (Stream<Path> stream = Files.walk(root)) {
        stream
            .filter(path -> path.toString().endsWith(".java"))
            .filter(path ->
                !GUARDED_QUERY_TYPES.contains(path.getFileName().toString().replace(".java", "")))
            .forEach(path -> collectMultipleNullQueryConstructors(path, violations));
      }
    }
    assertThat(violations)
        .as("已治理 Query 的生产调用侧禁止用多个 null 选择筛选模式；请使用具名工厂或 builder")
        .isEmpty();
  }

  @Test
  @DisplayName("状态型参数对象只能通过受控具名工厂构造")
  void shouldExposeOnlyControlledFactoriesForStateParams() throws IOException {
    for (String relativePath : CONTROLLED_FACTORY_TYPE_PATHS) {
      Path sourcePath = REPO_ROOT.resolve(relativePath);
      String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
      String simpleName = sourcePath.getFileName().toString().replace(".java", "");
      assertThat(source).as("%s 不得暴露通用 builder", relativePath).doesNotContain("@Builder");
      assertThat(source)
          .as("%s 不得暴露公共构造器", relativePath)
          .doesNotMatch("(?s).*\\bpublic\\s+" + simpleName + "\\s*\\(.*");
    }
  }

  @Test
  @DisplayName("新增生产代码不得把三层以上调用嵌入方法实参或返回表达式")
  void shouldRejectNewDeeplyNestedCallsites() throws IOException, InterruptedException {
    String base = System.getenv("BATCH_GOVERNANCE_BASE_SHA");
    if (base == null || base.isBlank()) {
      base = gitOutput("merge-base", "origin/main", "HEAD").strip();
    }
    assertThat(base).as("可读性增量门禁必须能解析 main 基线").isNotBlank();

    String diff = gitOutput("diff", "--unified=0", "--no-color", base, "--", "*.java");
    Map<String, List<LineRange>> changedLines = parseAddedLineRanges(diff);
    String untracked = gitOutput("ls-files", "--others", "--exclude-standard", "--", "*.java");
    for (String relativePath : untracked.split("\\R")) {
      if (!relativePath.isBlank() && relativePath.contains("/src/main/java/")) {
        Path path = REPO_ROOT.resolve(relativePath);
        if (Files.isRegularFile(path)) {
          int lineCount = Files.readAllLines(path, StandardCharsets.UTF_8).size();
          changedLines.putIfAbsent(relativePath, List.of(new LineRange(1, Math.max(1, lineCount))));
        }
      }
    }
    List<String> violations = new ArrayList<>();
    for (Map.Entry<String, List<LineRange>> entry : changedLines.entrySet()) {
      String relativePath = entry.getKey();
      if (!relativePath.contains("/src/main/java/") || entry.getValue().isEmpty()) continue;
      Path sourcePath = REPO_ROOT.resolve(relativePath);
      if (!Files.isRegularFile(sourcePath)) continue;
      String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
      violations.addAll(findDeepCallsiteViolations(relativePath, source, entry.getValue()));
    }

    assertThat(violations)
        .as("新增/修改的生產 Java 實參或 return 含至少三層連續方法/构造调用；"
            + "请提取有业务含义的局部变量。短 fluent receiver 链和回调函数体不计入")
        .isEmpty();
  }

  @Test
  @DisplayName("调用现场候选门禁拦深层实参并放行短包装、fluent receiver 与回调")
  void shouldClassifyCallsiteNestingWithoutFlaggingSimpleComposition() {
    String source = """
        class Sample {
          Object submit() {
            target(foo(bar(baz(value))));
            target(wrapper(optional(value)));
            target(value.map(x -> normalize(parse(x))).join());
            return wrap(parse(normalize(value)));
          }
        }
        """;

    List<String> findings =
        findDeepCallsiteViolations("Sample.java", source, List.of(new LineRange(1, 8)));

    assertThat(findings).hasSize(2);
    assertThat(findings).anySatisfy(finding -> assertThat(finding).contains(":3 -> 方法实参"));
    assertThat(findings).anySatisfy(finding -> assertThat(finding).contains(":6 -> return 表达式"));
    assertThat(findDeepCallsiteViolations("Sample.java", source, List.of(new LineRange(1, 2))))
        .as("基线中未修改的调用不应被增量门禁报告")
        .isEmpty();
  }

  private static String gitOutput(String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command)
        .directory(REPO_ROOT.toFile())
        .redirectErrorStream(true)
        .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IllegalStateException("git command failed (" + exitCode + "): " + output);
    }
    return output;
  }

  private static Map<String, List<LineRange>> parseAddedLineRanges(String diff) {
    Map<String, List<LineRange>> files = new HashMap<>();
    String currentFile = null;
    Pattern hunkPattern = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");
    for (String line : diff.split("\\R")) {
      if (line.startsWith("+++ b/")) {
        currentFile = line.substring("+++ b/".length());
        files.computeIfAbsent(currentFile, ignored -> new ArrayList<>());
        continue;
      }
      Matcher matcher = hunkPattern.matcher(line);
      if (!matcher.find() || currentFile == null) continue;
      int start = Integer.parseInt(matcher.group(1));
      int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
      if (count > 0) files.get(currentFile).add(new LineRange(start, start + count - 1));
    }
    return files;
  }

  private static List<String> findDeepCallsiteViolations(
      String file, String source, List<LineRange> changedLines) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("JDK compiler is required for AST gate");
    SimpleJavaFileObject sourceFile =
        new SimpleJavaFileObject(
            URI.create("string:///" + file.replace(' ', '_')), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    JavacTask task = (JavacTask) compiler.getTask(
        null,
        null,
        diagnostics,
        List.of("-proc:none", "--release", "21"),
        null,
        List.of(sourceFile));
    try {
      var unit = task.parse().iterator().next();
      var positions = Trees.instance(task).getSourcePositions();
      List<String> findings = new ArrayList<>();
      new TreePathScanner<Void, Void>() {
        private void addFinding(Tree expression, String kind) {
          long start = positions.getStartPosition(unit, expression);
          long end = positions.getEndPosition(unit, expression);
          if (start < 0 || end < start) return;
          long firstLine = unit.getLineMap().getLineNumber(start);
          long lastLine = unit.getLineMap().getLineNumber(end);
          boolean changed = changedLines.stream()
              .anyMatch(range -> range.first() <= lastLine && range.last() >= firstLine);
          if (changed) findings.add(file + ":" + firstLine + " -> " + kind);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
          for (var argument : node.getArguments()) {
            if (nestedCallDepth(argument) >= 3) addFinding(argument, "方法实参");
          }
          return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void unused) {
          for (var argument : node.getArguments()) {
            if (nestedCallDepth(argument) >= 3) addFinding(argument, "构造实参");
          }
          return super.visitNewClass(node, unused);
        }

        @Override
        public Void visitReturn(ReturnTree node, Void unused) {
          if (node.getExpression() != null && nestedCallDepth(node.getExpression()) >= 3) {
            addFinding(node.getExpression(), "return 表达式");
          }
          return super.visitReturn(node, unused);
        }
      }.scan(unit, null);
      return findings.stream().distinct().toList();
    } catch (IOException exception) {
      throw new IllegalArgumentException("Unable to parse " + file, exception);
    }
  }

  private static int nestedCallDepth(Tree expression) {
    int[] maximum = {0};
    new TreeScanner<Void, Integer>() {
      @Override
      public Void visitMethodInvocation(MethodInvocationTree node, Integer parentDepth) {
        int depth = parentDepth + 1;
        maximum[0] = Math.max(maximum[0], depth);
        node.getArguments().forEach(argument -> scan(argument, depth));
        return null;
      }

      @Override
      public Void visitNewClass(NewClassTree node, Integer parentDepth) {
        int depth = parentDepth + 1;
        maximum[0] = Math.max(maximum[0], depth);
        node.getArguments().forEach(argument -> scan(argument, depth));
        return null;
      }

      @Override
      public Void visitLambdaExpression(LambdaExpressionTree node, Integer parentDepth) {
        return null;
      }

      @Override
      public Void visitClass(ClassTree node, Integer parentDepth) {
        return null;
      }

      @Override
      public Void scan(Tree tree, Integer depth) {
        return tree == null ? null : super.scan(tree, depth == null ? 0 : depth);
      }
    }.scan(expression, 0);
    return maximum[0];
  }

  private record LineRange(int first, int last) {}

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

  private void collectMultipleNullQueryConstructors(Path file, List<String> sink) {
    String content;
    try {
      content = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return;
    }
    Matcher matcher = RAW_LONG_CTOR.matcher(content);
    while (matcher.find()) {
      String type = matcher.group(1);
      if (!GUARDED_QUERY_TYPES.contains(type)) {
        continue;
      }
      long nullCount =
          Pattern.compile("\\bnull\\b").matcher(matcher.group(2)).results().count();
      if (nullCount < 2) {
        continue;
      }
      int lineNo = (int) content
              .substring(0, matcher.start())
              .chars()
              .filter(ch -> ch == '\n')
              .count()
          + 1;
      sink.add(file + ":" + lineNo + " -> new " + type + "(...) nulls=" + nullCount);
    }
  }
}
