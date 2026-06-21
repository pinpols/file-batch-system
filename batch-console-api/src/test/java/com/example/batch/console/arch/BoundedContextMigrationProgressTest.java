package com.example.batch.console.arch;

import static com.example.batch.console.arch.BoundedContextDependencyArchTest.BOUNDED_CONTEXTS;
import static com.example.batch.console.arch.BoundedContextDependencyArchTest.DOMAIN_ROOT;
import static com.example.batch.console.arch.BoundedContextDependencyArchTest.SHARED_ROOT;
import static com.example.batch.console.arch.BoundedContextDependencyArchTest.hasBoundedContextSuppression;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * P1-A Stage 1 迁移进度 metric。
 *
 * <p>统计当前 {@code domain.<ctx>.*} 之间的非法直接依赖数量,输出到 stdout,不做 assert。
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
 * <p>启用守护测试 {@link BoundedContextDependencyArchTest} 的前提:本测试输出 0。
 */
class BoundedContextMigrationProgressTest {

  /**
   * 2026-06-21 基线:当前 console bounded context 直接依赖违规数。这个测试先作为 ratchet 护栏防新增债务;每次迁移减少后必须同步下调预算。降到 0
   * 后删除本预算并启用 {@link BoundedContextDependencyArchTest}。
   */
  private static final int MAX_ALLOWED_CROSS_CONTEXT_VIOLATIONS = 1697;

  private static final Set<String> CTX_SET = Set.copyOf(Arrays.asList(BOUNDED_CONTEXTS));

  @Test
  void reportCurrentViolationCount() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.batch.console..");

    Map<String, Integer> matrix = new TreeMap<>();
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
    if (total == 0) {
      System.out.println(
          "[BoundedContext] No violations detected — safe to remove @Disabled from "
              + "BoundedContextDependencyArchTest.");
    }
    assertThat(total)
        .as(
            "bounded-context cross dependencies must not increase; lower this budget as migration"
                + " progresses")
        .isLessThanOrEqualTo(MAX_ALLOWED_CROSS_CONTEXT_VIOLATIONS);
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
}
