package io.github.pinpols.batch.console.arch;

import static io.github.pinpols.batch.console.arch.BoundedContextDependencyArchTest.BOUNDED_CONTEXTS;
import static io.github.pinpols.batch.console.arch.BoundedContextDependencyArchTest.DOMAIN_ROOT;
import static io.github.pinpols.batch.console.arch.BoundedContextDependencyArchTest.MAX_ALLOWED_CROSS_CONTEXT_VIOLATIONS;
import static io.github.pinpols.batch.console.arch.BoundedContextDependencyArchTest.SHARED_ROOT;
import static io.github.pinpols.batch.console.arch.BoundedContextDependencyArchTest.hasBoundedContextSuppression;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * P1-A Stage 1 迁移进度 metric。
 *
 * <p>统计当前 {@code domain.<ctx>.*} 之间的非法直接依赖数量,输出到 stdout,并校验不超过 ratchet 基线。
 * 通过 {@code -DboundedContext.report=<path>} 可额外生成逐类 TSV 清单,供迁移批次评审使用。
 *
 * <p>每次跑测试都能看到迁移进度,例如:
 *
 * <pre>
 *   [BoundedContext] total cross-context violations: 124
 *   [BoundedContext]   job -> workflow : 42
 *   [BoundedContext]   ops -> job      : 31
 *   ...
 * </pre>
 *
 * <p>{@link BoundedContextDependencyArchTest} 当前已经以 ratchet 模式启用;依赖数降到 0 后再切换严格隔离规则。
 */
class BoundedContextMigrationProgressTest {

  /**
   * 2026-06-21 基线:当前 console bounded context 直接依赖违规数。这个测试作为 ratchet 护栏防新增债务;每次迁移减少后必须同步下调预算。降到 0
   * 后把 {@link BoundedContextDependencyArchTest} 切换为严格规则。
   *
   * <p>基线对齐 main 实测 1711(原 capture 写 1697 是 de-stale 前的旧快照,合 main 后域代码增加到 1711)。
   *
   * <p>2026-07-11(#795):AI 告警分诊工具 ConsoleAiTools(audit 域)新增只读 getOpenAlerts/getRecentAlerts, 经
   * console 现有查询层读 notification 域的 alert_event(引用 AlertEventQueryRequest /
   * ConsoleAlertEventResponse), 引入 audit→notification 只读跨域依赖 +5(1711→1716)。AI
   * 工具本质就是跨域**只读**聚合各域数据(诊断/告警), 该依赖合理、不为降数字破坏工具设计;照 #770/#779 先例上调预算而非挪包(AI tools 属 audit 域业务工具,非
   * shared 基建)。
   *
   * <p>2026-07-12(#811):4 类 worker 执行可观测性补全。file 域 ConsoleFilePipelineQuery 服务端桥接实时行数, 经 ops 域
   * orchestratorProxy 读 orchestrator 进程内进度 cache(设计文档要求前端不按 workerCode 查,桥接必须服务端做); 引入 file→ops
   * 只读跨域依赖 +6(1716→1722)。桥接是缺口1的必需路径(实时进度只存 orchestrator 内存), 依赖合理、不为降数字破坏观测设计;照 #795/#770/#779
   * 先例上调预算而非挪包。
   *
   * <p>2026-07-13(对抗审查 #2):presign-download 审批提交分支修跨租户写越权,submitApproval 新增一处 {@code
   * tenantGuard.resolveTenant(...)} 校验(会话身份覆盖 body 声明的 tenantId,与带 approvalId 分支一致); file 域→tenant
   * guard 只读跨域依赖 +1(1722→1723)。该调用是消灭跨租户审批注入的必需路径(安全修复), 依赖合理、不为降数字牺牲越权防护;照 #795/#811 先例上调预算。
   *
   * <p>2026-07-16(#837):以 #836 合并提交为基线实测仍为 1724；此前常量 1723 是未同步的旧值， 本次仅校正护栏基线，不计入本 PR 的新增依赖。
   *
   * <p>2026-08-02(#868):observability timeline 与 rate-limit degradation 观测接入 console 查询/ops
   * 边界，CI JDK 21 实测为 1807；本地 JDK 25 的同一字节码扫描为 1841。1841 是跨 JDK 的当前
   * 兼容上限；这些是已交付运维闭环的只读聚合依赖，更新 ratchet 基线，不为降低数字破坏功能边界。
   *
   * <p>2026-08-03(Phase 1):将无业务状态的 ConsoleQuerySupport 与租户解析 Port 移入 shared.query，移除
   * file / ops / workflow / job 查询服务对 observability 工具类的直接依赖，实测降至 1480。
   *
   * <p>2026-08-03(Phase 2):将 SimpleOptionView 提取到 shared.view，将集群诊断投影收回 ops.view.cluster，实测降至 1464。
   *
   * <p>2026-08-03(Phase 3):将纯实时事件载荷 ConsoleRealtimeDomainEvent 提取到 shared.event，保留
   * observability 的发布器与桥接基础设施，实测降至 1449。
   *
   * <p>2026-08-03(Phase 4):Ops 只依赖租户解析 Port，当前租户作用域单独依赖 TenantScopeResolver；保留
   * ConsoleTenantGuard 作为唯一实现，实测降至 1374。
   *
   * <p>2026-08-03(Phase 5):notification 的租户感知 Service 改依赖 TenantIdResolver，实时 Controller 暂保留
   * 具体守卫以避免 API 门禁误报，实测降至 1357。
   *
   * <p>2026-08-03(Phase 6):file 的文件服务和查询服务改依赖 TenantIdResolver，实时 Controller 暂保留具体守卫，实测降至 1329。
   *
   * <p>2026-08-03(Phase 7):job 的定义、日历、窗口、Bundle 和自服务改依赖 TenantIdResolver，Controller 暂保留具体守卫，实测降至 1295。
   *
   * <p>2026-08-03(Phase 8):workflow 查询服务改依赖 TenantIdResolver，3 个实时 Controller 暂保留具体守卫，实测降至 1293。
   *
   * <p>2026-08-03(Phase 9):audit 查询和 observability 查询/实时流改依赖 TenantIdResolver，实测降至 1266。
   *
   * <p>2026-08-03(Phase 10):无状态横切审计声明 {@code AuditAction} 移入 {@code shared.audit}，供各领域 Controller 复用；切面仍由
   * audit context 持有，HTTP 和审计事务语义不变，实测降至 1213。
   *
   * <p>2026-08-03(Phase 11):租户作用域非空断言 {@code TenantScope} 移入 {@code shared.query}，保留 fail-fast 语义，实测降至 1204。
   *
   * <p>2026-08-03(Phase 12):不可变认证身份载荷 {@code ConsolePrincipal} 移入 {@code shared.security}，认证与授权策略仍归 rbac，实测降至 1196。
   *
   * <p>2026-08-03(Phase 13):observability 聚合服务改依赖顶层 {@code ConsoleOpsQueryPort}，Ops 查询实现仍归 Ops，实测降至 1181。
   *
   * <p>2026-08-03(Phase 14):跨域实时变更发布改依赖顶层 {@code ConsoleRealtimeEventPort}，Spring 事件和 SSE 适配器仍归 observability，实测降至 1145。
   *
   * <p>2026-08-03(Phase 15):跨域 SSE Controller 改依赖 {@code ConsoleRealtimeSubscriptionPort}，Hub 的连接生命周期仍归 observability，实测降至 1120。
   */
  private static final Set<String> CTX_SET = Set.copyOf(Arrays.asList(BOUNDED_CONTEXTS));

  private static final Map<String, String> LAYER_CATEGORIES = Map.ofEntries(
      Map.entry("entity", "PERSISTENCE"),
      Map.entry("mapper", "PERSISTENCE"),
      Map.entry("application", "APPLICATION"),
      Map.entry("service", "APPLICATION"),
      Map.entry("command", "CONTRACT"),
      Map.entry("dto", "CONTRACT"),
      Map.entry("param", "CONTRACT"),
      Map.entry("query", "CONTRACT"),
      Map.entry("view", "CONTRACT"),
      Map.entry("web", "WEB_OR_REALTIME"),
      Map.entry("realtime", "WEB_OR_REALTIME"),
      Map.entry("infrastructure", "ADAPTER_OR_SUPPORT"),
      Map.entry("support", "ADAPTER_OR_SUPPORT"));

  @Test
  void reportCurrentViolationCount() {
    JavaClasses classes = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.github.pinpols.batch.console..");

    Map<String, Integer> matrix = new TreeMap<>();
    List<String> inventoryRows = new ArrayList<>();
    int total = 0;
    int suppressed = 0;

    for (JavaClass src : classes) {
      String srcCtx = boundedContextOf(src.getPackageName());
      if (srcCtx == null) {
        continue;
      }
      boolean srcSuppressed = hasBoundedContextSuppression(src);
      for (Dependency dep : src.getDirectDependenciesFromSelf()) {
        String depPkg = dep.getTargetClass().getPackageName();
        if (depPkg.startsWith(SHARED_ROOT)) {
          continue;
        }
        String depCtx = boundedContextOf(depPkg);
        if (depCtx == null || depCtx.equals(srcCtx)) {
          continue;
        }
        String sourceLayer = layerOf(src.getPackageName());
        String targetLayer = layerOf(depPkg);
        String status = srcSuppressed ? "SUPPRESSED" : "ACTIVE";
        inventoryRows.add(String.join(
            "\t",
            status,
            srcCtx,
            src.getName(),
            sourceLayer,
            depCtx,
            dep.getTargetClass().getName(),
            targetLayer,
            LAYER_CATEGORIES.getOrDefault(targetLayer, "OTHER")));
        if (srcSuppressed) {
          suppressed++;
          continue;
        }
        String key = srcCtx + " -> " + depCtx;
        matrix.merge(key, 1, Integer::sum);
        total++;
      }
    }

    Map<String, Integer> sorted = new LinkedHashMap<>();
    matrix.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .forEach(e -> sorted.put(e.getKey(), e.getValue()));

    System.out.println("[BoundedContext] total cross-context violations: " + total);
    System.out.println("[BoundedContext] suppressed (whitelisted) edges: " + suppressed);
    sorted.forEach((k, v) -> System.out.println("[BoundedContext]   " + k + " : " + v));
    writeInventoryIfRequested(inventoryRows);
    assertThat(total)
        .as("bounded-context cross dependencies must not increase; lower this budget as migration"
            + " progresses")
        .isLessThanOrEqualTo(MAX_ALLOWED_CROSS_CONTEXT_VIOLATIONS);
  }

  private static void writeInventoryIfRequested(List<String> rows) {
    String reportPath = System.getProperty("boundedContext.report");
    if (reportPath == null || reportPath.isBlank()) {
      return;
    }
    try {
      Path path = Path.of(reportPath);
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      List<String> output = new ArrayList<>(rows.size() + 1);
      output.add(
          "status\tsource_context\tsource_class\tsource_layer\ttarget_context\ttarget_class\ttarget_layer\tcategory");
      rows.stream().sorted().forEach(output::add);
      Files.write(path, output, StandardCharsets.UTF_8);
      System.out.println("[BoundedContext] inventory written: " + path);
    } catch (IOException exception) {
      throw new UncheckedIOException("failed to write bounded-context inventory", exception);
    }
  }

  private static String boundedContextOf(String pkg) {
    if (!pkg.startsWith(DOMAIN_ROOT + ".")) {
      return null;
    }
    String tail = pkg.substring(DOMAIN_ROOT.length() + 1);
    int dot = tail.indexOf('.');
    String head = dot < 0 ? tail : tail.substring(0, dot);
    return CTX_SET.contains(head) ? head : null;
  }

  private static String layerOf(String pkg) {
    String tail = pkg.substring(DOMAIN_ROOT.length() + 1);
    int firstDot = tail.indexOf('.');
    if (firstDot < 0) {
      return "(root)";
    }
    String layers = tail.substring(firstDot + 1);
    int secondDot = layers.indexOf('.');
    return secondDot < 0 ? layers : layers.substring(0, secondDot);
  }
}
