package com.example.batch.worker.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-026 §"DryRunGuard 接入" 守护测试。
 *
 * <p>所有 worker stage step plugin（{@code *Step.java}）必须显式归入下列两表之一：
 *
 * <ul>
 *   <li>{@link #SIDE_EFFECTING_GUARDED} — 写业务表 / 外部 IO / 通知投递 等真副作用，必须导入并使用 {@code DryRunGuard} 做
 *       {@code isDryRun()} 短路；新增此类 plugin 必须同步加入名单 + 实现 dry-run 短路。
 *   <li>{@link #READ_ONLY_OR_LOCAL} — 只读 DB / 内存计算 / 本地临时文件，dry-run 模式下放行无害； 新增此类 plugin
 *       必须显式归入此表，避免漏 guard。
 * </ul>
 *
 * <p>新增 step plugin 时若不在两表之一，本测试 fail，强制开发者做边界判定。
 *
 * <p>边界判定问题：「这一步如果在 dry-run 模式下原样执行，会污染业务表 / 触发外部投递 / 改变下游状态吗？」
 *
 * <ul>
 *   <li>是 → {@link #SIDE_EFFECTING_GUARDED}（必须 guard）
 *   <li>否 → {@link #READ_ONLY_OR_LOCAL}（可放行）
 * </ul>
 *
 * <p>历史背景：commit {@code 8e07ee5c}/{@code 90eabcb9} 把 ADR-026 dry-run SDK + L1/L2/L3 +
 * result_version DRY_RUN 状态串通。本测试是收尾守护，固化"哪些 step 必须 guard"的工程契约，避免后续新加 plugin 漏 guard 让 dry-run
 * 边界裂开。
 */
class DryRunGuardConventionTest {

  /** 副作用（写业务表 / 外部投递 / file_record 注册等）必须用 DryRunGuard 短路的 plugin。 */
  private static final Set<String> SIDE_EFFECTING_GUARDED =
      Set.of(
          // batch-worker-import: 写 biz 表 / 完成审计
          "batch-worker-import/.*/stage/LoadStep.java",
          "batch-worker-import/.*/stage/FeedbackStep.java",
          // batch-worker-export: 上传 MinIO / 注册 file_record / 完成 / 投递审计
          "batch-worker-export/.*/stage/StoreStep.java",
          "batch-worker-export/.*/stage/RegisterStep.java",
          "batch-worker-export/.*/stage/CompleteStep.java",
          // batch-worker-process: COMMIT 写 biz 表 / FEEDBACK 写审计
          "batch-worker-process/.*/stage/CommitStep.java",
          "batch-worker-process/.*/stage/FeedbackStep.java",
          // batch-worker-dispatch: 5 类外部投递 / 收据 / 补偿 / 重试 / 完成
          "batch-worker-dispatch/.*/stage/DeliverDispatchStep.java",
          "batch-worker-dispatch/.*/stage/CompensateDispatchStep.java",
          "batch-worker-dispatch/.*/stage/RetryDispatchStep.java",
          "batch-worker-dispatch/.*/stage/AckDispatchStep.java",
          "batch-worker-dispatch/.*/stage/CompleteDispatchStep.java");

  /**
   * 只读 DB / 内存计算 / 本地临时文件 plugin。dry-run 模式下原样跑不污染业务也不投递外部，无需 guard。
   *
   * <p>任意写业务/触发外部 IO 的扩展会让此名单失效 → 该 plugin 必须迁到 {@link #SIDE_EFFECTING_GUARDED}。
   */
  private static final Set<String> READ_ONLY_OR_LOCAL =
      Set.of(
          // batch-worker-import: 拉文件 / 解密 / 解析 / 校验都是文件->内存，未触达 biz 表
          "batch-worker-import/.*/stage/ReceiveStep.java",
          "batch-worker-import/.*/stage/PreprocessStep.java",
          "batch-worker-import/.*/stage/ParseStep.java",
          "batch-worker-import/.*/stage/ValidateStep.java",
          "batch-worker-import/.*/stage/ImportStageStep.java",
          // batch-worker-export: PREPARE 只读 plugin 注册 / GENERATE 写本地 tmp，无对外 IO
          "batch-worker-export/.*/stage/PrepareStep.java",
          "batch-worker-export/.*/stage/GenerateStep.java",
          "batch-worker-export/.*/stage/ExportStageStep.java",
          // batch-worker-process: PREPARE / COMPUTE / VALIDATE 都在 staging 中间态，由 COMMIT 守门
          "batch-worker-process/.*/stage/PrepareStep.java",
          "batch-worker-process/.*/stage/ComputeStep.java",
          "batch-worker-process/.*/stage/ValidateStep.java",
          "batch-worker-process/.*/stage/ProcessStageStep.java",
          // batch-worker-dispatch: PREPARE 是 channel 配置组装 + biz file 元信息读取
          "batch-worker-dispatch/.*/stage/PrepareDispatchStep.java",
          "batch-worker-dispatch/.*/stage/DispatchStageStep.java");

  @Test
  void everyStepPluginMustBeClassified() throws IOException {
    Path repoRoot = Path.of(".").toAbsolutePath().normalize().getParent();
    List<String> unclassified = new ArrayList<>();
    List<String> guardedButMissingImport = new ArrayList<>();
    List<String> readOnlyButHasGuard = new ArrayList<>();

    for (String workerModule :
        List.of(
            "batch-worker-import",
            "batch-worker-export",
            "batch-worker-process",
            "batch-worker-dispatch")) {
      Path stageDir = repoRoot.resolve(workerModule).resolve("src/main/java");
      if (!Files.exists(stageDir)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(stageDir)) {
        files
            .filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().endsWith("Step.java"))
            .forEach(
                stepFile -> {
                  String relative =
                      repoRoot
                          .relativize(stepFile)
                          .toString()
                          .replace(java.io.File.separatorChar, '/');
                  boolean inGuarded = SIDE_EFFECTING_GUARDED.stream().anyMatch(relative::matches);
                  boolean inReadOnly = READ_ONLY_OR_LOCAL.stream().anyMatch(relative::matches);
                  if (!inGuarded && !inReadOnly) {
                    unclassified.add(relative);
                    return;
                  }
                  String content = readFileSafe(stepFile);
                  // SIDE_EFFECTING_GUARDED 三种合法接入姿势：isDryRun() 整体短路 / callOrSkip
                  // 包裹返回值 / runUnlessDryRun 包裹无返回副作用。任一即合规。
                  boolean usesGuard =
                      content.contains("DryRunGuard")
                          && (content.contains(".isDryRun()")
                              || content.contains(".callOrSkip(")
                              || content.contains(".runUnlessDryRun("));
                  if (inGuarded && !usesGuard) {
                    guardedButMissingImport.add(
                        relative
                            + " — 缺 DryRunGuard 接入（execute() 必须用 isDryRun() 短路 /"
                            + " callOrSkip / runUnlessDryRun 之一保护副作用调用）");
                  }
                  if (inReadOnly && usesGuard) {
                    readOnlyButHasGuard.add(
                        relative
                            + " — 既然登记为 READ_ONLY_OR_LOCAL 就不应再加 guard；"
                            + "要么删 guard，要么迁到 SIDE_EFFECTING_GUARDED");
                  }
                });
      }
    }

    assertThat(unclassified)
        .as(
            """
            发现未登记的 step plugin。新加 *Step.java 时必须显式归入：
              - SIDE_EFFECTING_GUARDED（写业务表 / 外部投递 / 文件持久化）
              - READ_ONLY_OR_LOCAL（只读 / 内存 / 本地 tmp，dry-run 放行无害）
            归入后再加测试，避免 dry-run 边界裂开。
            未登记 plugin: %s
            """,
            new TreeSet<>(unclassified))
        .isEmpty();
    assertThat(guardedButMissingImport)
        .as(
            "登记为 SIDE_EFFECTING_GUARDED 但缺 DryRunGuard.isDryRun() 短路:\n%s",
            String.join("\n", guardedButMissingImport))
        .isEmpty();
    assertThat(readOnlyButHasGuard)
        .as("登记为 READ_ONLY_OR_LOCAL 但实际加了 guard，定位不一致:\n%s", String.join("\n", readOnlyButHasGuard))
        .isEmpty();
  }

  private static String readFileSafe(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("read failed: " + file, ex);
    }
  }
}
