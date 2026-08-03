package io.github.pinpols.batch.console.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * P1-A Stage 1 守护:禁跨 bounded context 直接依赖。
 *
 * <p>9 个有界上下文:job / workflow / file / ops / governance / notification / audit / rbac /
 * observability,加 shared(共享 DTO / Entity / 工具)。
 *
 * <p>核心规则:{@code io.github.pinpols.batch.console.domain.<ctx>.*} 内的类禁止直接 import {@code
 * io.github.pinpols.batch.console.domain.<other-ctx>.*} 下的类。
 *
 * <p>允许的跨 context 通信方式(详见 docs/architecture/bounded-context-rules.md):
 *
 * <ul>
 *   <li>引用 {@code io.github.pinpols.batch.console.shared.*}(共享 DTO / Entity / 工具)
 *   <li>注入应用层 service 接口(走 Spring DI;ArchUnit 仅看 import,不约束接口归属)
 *   <li>Spring 事件(`ApplicationEventPublisher` + `@EventListener`)
 *   <li>SpiPort 显式端口
 * </ul>
 *
 * <p>豁免机制:类或方法上加 {@code @SuppressWarnings("BoundedContext")} 可在过渡期单点放行,但必须在 commit message 或
 * javadoc 里写明计划清理时间。
 *
 * <p><b>当前状态</b>:Stage 1 尚未完成,本测试以 ratchet 门禁运行,只允许历史基线内的跨域依赖。配套 {@link
 * BoundedContextMigrationProgressTest} 输出当前违规矩阵,作为迁移进度 metric。
 *
 * <p>违规数降到 0 后,将把 ratchet 切换为严格 ArchUnit 规则。详见 {@code docs/architecture/p0-p1-p2-roadmap.md} § P1-A。
 */
class BoundedContextDependencyArchTest {

  /** 9 个有界上下文的子包名,对应 {@code io.github.pinpols.batch.console.domain.<ctx>}. */
  static final String[] BOUNDED_CONTEXTS = {
    "job", "workflow", "file", "ops", "governance", "notification", "audit", "rbac", "observability"
  };

  static final String DOMAIN_ROOT = "io.github.pinpols.batch.console.domain";
  static final String SHARED_ROOT = "io.github.pinpols.batch.console.shared";
  static final String SUPPRESS_TAG = "BoundedContext";
  static final int MAX_ALLOWED_CROSS_CONTEXT_VIOLATIONS = 1357;

  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.pinpols.batch.console..");

  @Test
  void crossBoundedContextDependenciesStayWithinBaseline() {
    int total = countCrossContextViolations(CLASSES);
    assertThat(total)
        .as("bounded-context cross dependencies must not increase from the migration baseline")
        .isLessThanOrEqualTo(MAX_ALLOWED_CROSS_CONTEXT_VIOLATIONS);
  }

  static int countCrossContextViolations(JavaClasses classes) {
    int total = 0;
    for (JavaClass src : classes) {
      String srcCtx = boundedContextOf(src.getPackageName());
      if (srcCtx == null || hasBoundedContextSuppression(src)) {
        continue;
      }
      for (Dependency dep : src.getDirectDependenciesFromSelf()) {
        String depCtx = boundedContextOf(dep.getTargetClass().getPackageName());
        if (depCtx != null && !depCtx.equals(srcCtx)) {
          total++;
        }
      }
    }
    return total;
  }

  static boolean hasBoundedContextSuppression(JavaClass javaClass) {
    return javaClass.getAnnotations().stream()
        .filter(a -> a.getRawType().getName().equals(SuppressWarnings.class.getName()))
        .anyMatch(a -> {
          Object value = a.getProperties().get("value");
          if (value instanceof Object[] arr) {
            for (Object v : arr) {
              if (SUPPRESS_TAG.equals(String.valueOf(v))) {
                return true;
              }
            }
          }
          return SUPPRESS_TAG.equals(String.valueOf(value));
        });
  }

  static String boundedContextOf(String pkg) {
    if (!pkg.startsWith(DOMAIN_ROOT + ".")) {
      return null;
    }
    String tail = pkg.substring(DOMAIN_ROOT.length() + 1);
    int dot = tail.indexOf('.');
    String head = dot < 0 ? tail : tail.substring(0, dot);
    for (String context : BOUNDED_CONTEXTS) {
      if (context.equals(head)) {
        return context;
      }
    }
    return null;
  }
}
