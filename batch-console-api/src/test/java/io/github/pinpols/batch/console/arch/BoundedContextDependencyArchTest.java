package io.github.pinpols.batch.console.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.pinpols.batch.console.shared.arch.BoundedContextExemption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P1-A Stage 1 守护:禁跨 bounded context 直接依赖。
 *
 * <p>10 个有界上下文:job / workflow / file / ops / governance / notification / audit / rbac /
 * observability / config,加 shared(共享 DTO / Entity / 工具)。
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
 * <p>豁免机制:类上加 {@link io.github.pinpols.batch.console.shared.arch.BoundedContextExemption} 可显式登记
 * 跨域依赖,必须写明 {@code reason} 与 {@code cleanupBy};原 {@code SuppressWarnings}（BoundedContext）为
 * SOURCE 级注解,字节码扫描不可见,故改用运行时注解。
 *
 * <p><b>当前状态</b>:Stage 1 已完成,本测试以严格 0 断言运行;并于 2026-11-08 收尾后新增旧顶层包守卫,
 * 禁止 {@code web} / {@code mapper} / {@code service} / {@code domain.entity} / {@code domain.param} /
 * {@code domain.query} / {@code domain.command} 重新出现。配套 {@link BoundedContextMigrationProgressTest}
 * 继续输出逐类清单,用于边界回归诊断。
 */
@DisplayName("限界上下文依赖隔离守护: 各上下文之间禁止跨域直接依赖, 豁免须显式声明")
class BoundedContextDependencyArchTest {

  /** 10 个有界上下文的子包名,对应 {@code io.github.pinpols.batch.console.domain.<ctx>}. */
  static final String[] BOUNDED_CONTEXTS = {
    "job",
    "workflow",
    "file",
    "ops",
    "governance",
    "notification",
    "audit",
    "rbac",
    "observability",
    "config"
  };

  /** P1-A Stage 1 已清空、不得回流的历史顶层包。 */
  static final String[] LEGACY_PACKAGE_ROOTS = {
    "io.github.pinpols.batch.console.web",
    "io.github.pinpols.batch.console.mapper",
    "io.github.pinpols.batch.console.service",
    "io.github.pinpols.batch.console.domain.entity",
    "io.github.pinpols.batch.console.domain.param",
    "io.github.pinpols.batch.console.domain.query",
    "io.github.pinpols.batch.console.domain.command"
  };

  static final String DOMAIN_ROOT = "io.github.pinpols.batch.console.domain";
  static final String SHARED_ROOT = "io.github.pinpols.batch.console.shared";
  private static final JavaClasses CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("io.github.pinpols.batch.console..");

  @Test
  @DisplayName("不同限界上下文之间不得直接依赖, 扫描到的跨域违例计数必须为 0")
  void shouldForbidCrossContextDependency_whenScanningDomainClasses() {
    List<String> violations = crossContextViolations(CLASSES);
    assertThat(violations)
        .as("bounded-context cross dependencies must be eliminated")
        .isEmpty();
  }

  @Test
  @DisplayName("旧顶层包必须清空, 业务类只能落在 domain.<ctx> 或 shared 下")
  void shouldForbidLegacyTopLevelPackages() {
    List<String> legacyPackages = CLASSES.stream()
        .map(JavaClass::getPackageName)
        .filter(BoundedContextDependencyArchTest::isLegacyPackage)
        .distinct()
        .sorted()
        .toList();
    assertThat(legacyPackages)
        .as("P1-A Stage 1 legacy packages must stay empty")
        .isEmpty();
  }

  static boolean isLegacyPackage(String packageName) {
    for (String root : LEGACY_PACKAGE_ROOTS) {
      if (packageName.equals(root) || packageName.startsWith(root + ".")) {
        return true;
      }
    }
    return false;
  }

  static List<String> crossContextViolations(JavaClasses classes) {
    List<String> violations = new ArrayList<>();
    for (JavaClass src : classes) {
      String srcCtx = boundedContextOf(src.getPackageName());
      if (srcCtx == null || hasBoundedContextSuppression(src)) {
        continue;
      }
      for (Dependency dep : src.getDirectDependenciesFromSelf()) {
        String depCtx = boundedContextOf(dep.getTargetClass().getPackageName());
        if (depCtx != null && !depCtx.equals(srcCtx)) {
          violations.add(dep.getDescription());
        }
      }
    }
    violations.sort(String::compareTo);
    return violations;
  }

  static boolean hasBoundedContextSuppression(JavaClass javaClass) {
    return javaClass.getAnnotations().stream()
        .filter(a -> a.getRawType().getName().equals(BoundedContextExemption.class.getName()))
        .anyMatch(a -> {
          String reason = String.valueOf(a.get("reason").orElse(""));
          String cleanupBy = String.valueOf(a.get("cleanupBy").orElse(""));
          return !reason.isBlank() && !cleanupBy.isBlank();
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
