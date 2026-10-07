package io.github.pinpols.batch.common.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守护测试：仓库出参不得**新增** {@code Map<String, Object>}（口径见 docs/coding-conventions.md §1.2）。
 *
 * <p>{@link #BASELINE} 冻结 2026-10-07 已逐条复核的存量出参。存量分两类，理由登记在
 * docs/analysis/java-readability-phase-0-classification-2026-08-12.md §4.2 / §4.3：已符合约定的阶段性行映射
 * （有 typed view 兜底），以及刻意保留 Map 的动态边界与对外透传（出站报文键名是外部契约）。存量被类型化后应从
 * {@link #BASELINE} 删除；新增出参若确属动态边界，需先在分类总账登记理由再补基线。
 *
 * <p>固定行迁移为不可变 record 时用显式 {@code <resultMap><constructor>}（同节约定），不要依赖列序自动映射。
 */
@DisplayName("仓库出参约定:禁止新增 Map<String, Object> 出参,存量基线须与分类总账对齐")
class RepositoryMapReturnConventionTest {

  private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

  /**
   * 扫描根 = **磁盘目录**。worker 是嵌套聚合模块，目录是 {@code batch-worker/<name>}，不是 artifactId
   * {@code batch-worker-<name>}；写错会让 {@link Files#isDirectory} 为假并静默跳过整段覆盖。
   */
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

  /** 基线涉及的仓库类个数；少于此数说明扫描路径失效（防止静默漏扫）。 */
  private static final int BASELINE_FILES = 6;

  /** 只匹配 public 方法声明：方法名紧跟返回类型，避免命中方法体里的局部变量。 */
  private static final Pattern MAP_RETURN =
      Pattern.compile("public\\s+(?:List<Map<String,\\s*Object>>|Map<String,\\s*Object>)\\s+"
          + "([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

  private static final String ORCH_GOV =
      "batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator"
          + "/infrastructure/file/FileGovernanceRepository.java";
  private static final String CORE_INFRA =
      "batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/";
  private static final String DISPATCH_INFRA =
      "batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/";

  private static final Set<String> BASELINE = Set.of(
      ORCH_GOV + "#loadFileRecord",
      ORCH_GOV + "#loadLatestDispatchRecord",
      ORCH_GOV + "#loadTemplateSecurityForFile",
      ORCH_GOV + "#operationDetail",
      ORCH_GOV + "#selectArrivalDelaySamples",
      ORCH_GOV + "#selectArrivalGovernanceCandidates",
      ORCH_GOV + "#selectArrivalGroupFiles",
      ORCH_GOV + "#selectArrivalGroupSummaries",
      ORCH_GOV + "#selectProcessingDelaySamples",
      CORE_INFRA + "PlatformFileRecordRepository.java#loadFileRecord",
      CORE_INFRA + "PlatformFileRecordRepository.java#loadFileRecordByStoragePath",
      CORE_INFRA + "PlatformPipelineDefinitionRepository.java#loadLatestTemplateConfig",
      CORE_INFRA + "PlatformPipelineRunRepository.java#loadLatestSucceededStepOutputSummary",
      DISPATCH_INFRA + "FileDispatchRepository.java#loadChannel",
      DISPATCH_INFRA + "FileDispatchRepository.java#loadFile",
      DISPATCH_INFRA + "channel/DispatchChannelHealthRepository.java#findEnabledProbeChannels");

  @Test
  @DisplayName("Repository 冒出未登记的 Map<String, Object> 出参时守卫失败,并在诊断里点名文件与方法")
  void shouldRejectNewMapReturn_whenRepositoryAddsOne() throws IOException {
    Set<String> found = new TreeSet<>();
    Set<String> scannedFiles = new TreeSet<>();
    List<String> newEntries = new ArrayList<>();
    for (String module : SCAN_MODULES) {
      Path root = REPO_ROOT.resolve(module).resolve("src").resolve("main").resolve("java");
      assertThat(root)
          .as("扫描模块目录必须存在：%s（worker 目录是 batch-worker/<name>，不是 artifactId）", module)
          .isDirectory();
      try (Stream<Path> stream = Files.walk(root)) {
        for (Path file :
            stream.filter(p -> p.toString().endsWith("Repository.java")).toList()) {
          String relative = REPO_ROOT.relativize(file).toString().replace('\\', '/');
          for (String method : mapReturnMethods(file)) {
            scannedFiles.add(relative);
            String entry = relative + "#" + method;
            found.add(entry);
            if (!BASELINE.contains(entry)) {
              newEntries.add(entry);
            }
          }
        }
      }
    }
    assertThat(scannedFiles)
        .as("扫描覆盖面异常：命中出参的仓库类少于基线涉及的 %s 个，说明扫描路径漏了模块", BASELINE_FILES)
        .hasSizeGreaterThanOrEqualTo(BASELINE_FILES);
    assertThat(newEntries)
        .as("Repository 不得新增 Map<String, Object> 出参（docs/coding-conventions.md §1.2）：固定行请改 typed"
            + " record 并用显式 <resultMap><constructor> 映射；确属动态边界或对外透传的，先在"
            + " docs/analysis/java-readability-phase-0-classification-2026-08-12.md §4.2 登记理由，再补"
            + " BASELINE")
        .isEmpty();
    assertThat(found).as("基线条目应在仓库中仍可解析（若某条已被类型化，请从 BASELINE 删除）").isNotEmpty();
  }

  private static List<String> mapReturnMethods(Path file) throws IOException {
    List<String> names = new ArrayList<>();
    Matcher matcher = MAP_RETURN.matcher(Files.readString(file, StandardCharsets.UTF_8));
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names;
  }
}
