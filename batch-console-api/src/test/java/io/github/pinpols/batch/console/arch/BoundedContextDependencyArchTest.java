package io.github.pinpols.batch.console.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Disabled;
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
 * <p><b>当前状态</b>:Stage 1 尚未完成,本测试 {@code @Disabled} 作为「目标基线」锁定语义。配套 {@link
 * BoundedContextMigrationProgressTest} 输出当前违规数,作为迁移进度 metric。
 *
 * <p>Stage 1 迁移完成后:删除 {@code @Disabled} 注解即启用守护。详见 {@code docs/architecture/p0-p1-p2-roadmap.md} §
 * P1-A。
 */
@Disabled("P1-A Stage 1 迁移完成后启用;当前作为目标基线锁定语义,见 docs/architecture/p0-p1-p2-roadmap.md")
class BoundedContextDependencyArchTest {

  /** 9 个有界上下文的子包名,对应 {@code io.github.pinpols.batch.console.domain.<ctx>}. */
  static final String[] BOUNDED_CONTEXTS = {
    "job", "workflow", "file", "ops", "governance", "notification", "audit", "rbac", "observability"
  };

  static final String DOMAIN_ROOT = "io.github.pinpols.batch.console.domain";
  static final String SHARED_ROOT = "io.github.pinpols.batch.console.shared";
  static final String SUPPRESS_TAG = "BoundedContext";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.pinpols.batch.console..");

  @Test
  void noCrossBoundedContextDependency() {
    for (String ctx : BOUNDED_CONTEXTS) {
      ArchRule rule =
          ArchRuleDefinition.classes()
              .that()
              .resideInAPackage(DOMAIN_ROOT + "." + ctx + "..")
              .and(notSuppressed())
              .should()
              .onlyDependOnClassesThat(allowedDependencyFor(ctx));
      rule.check(CLASSES);
    }
  }

  /** 类没有 {@code @SuppressWarnings("BoundedContext")} 豁免。 */
  static DescribedPredicate<JavaClass> notSuppressed() {
    return new DescribedPredicate<>("not annotated @SuppressWarnings(\"" + SUPPRESS_TAG + "\")") {
      @Override
      public boolean test(JavaClass javaClass) {
        return !hasBoundedContextSuppression(javaClass);
      }
    };
  }

  static boolean hasBoundedContextSuppression(JavaClass javaClass) {
    return javaClass.getAnnotations().stream()
        .filter(a -> a.getRawType().getName().equals(SuppressWarnings.class.getName()))
        .anyMatch(
            a -> {
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

  /** 允许依赖判定:依赖类只要不在「其它 bounded context」的 domain 子包下,就放行(shared / 应用层 / 框架 / JDK 全 OK)。 */
  static DescribedPredicate<JavaClass> allowedDependencyFor(String selfCtx) {
    return new DescribedPredicate<>("not reside in another bounded context under " + DOMAIN_ROOT) {
      @Override
      public boolean test(JavaClass dep) {
        String pkg = dep.getPackageName();
        // shared 永远放行
        if (pkg.startsWith(SHARED_ROOT)) {
          return true;
        }
        // 不在 domain 子包下 → 不归本规则管(应用层、infrastructure、JDK、Spring 等)
        if (!pkg.startsWith(DOMAIN_ROOT + ".")) {
          return true;
        }
        // 同 context 放行
        String tail = pkg.substring(DOMAIN_ROOT.length() + 1);
        int dot = tail.indexOf('.');
        String depCtx = dot < 0 ? tail : tail.substring(0, dot);
        if (depCtx.equals(selfCtx)) {
          return true;
        }
        // 其余 domain.<other-ctx>.* → 违规
        for (String known : BOUNDED_CONTEXTS) {
          if (known.equals(depCtx)) {
            return false;
          }
        }
        // 未登记的 domain 子包 → 视为未分类,放行(过渡期容忍)
        return true;
      }
    };
  }
}
