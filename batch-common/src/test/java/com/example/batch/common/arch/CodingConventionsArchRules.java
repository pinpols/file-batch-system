package com.example.batch.common.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * 复用型 ArchUnit 规则,守护 CLAUDE.md 硬性规约。
 *
 * <p>使用方:在各模块 test sources 写一个 {@code XxxConventionsArchTest},import batch-common test-jar,
 * 把本类的规则跑在该模块自己 importPackages 出来的 JavaClasses 上。本类没有 {@code @Test} 方法, 不会在 batch-common surefire
 * 里被误执行;实际触发在每个模块自己的 test 类里。
 */
public final class CodingConventionsArchRules {

  private CodingConventionsArchRules() {}

  /** ZoneId.systemDefault() 仅 batch-common 时区基础设施可用。 */
  public static ArchRule zoneIdSystemDefaultRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackages(
            "com.example.batch.common.config..", "com.example.batch.common.time..")
        .should(callMethod("java.time.ZoneId", "systemDefault"))
        .allowEmptyShould(true)
        .because(
            "CLAUDE.md §时区策略:禁止业务代码 ZoneId.systemDefault();注入 BatchTimezoneProvider"
                + " 或调用 provider.defaultZone()。白名单 = batch-common.config / batch-common.time。");
  }

  /**
   * Charset.forName(...) 仅 EncodingUtils 可用,业务一律 StandardCharsets.UTF_8 / EncodingUtils.resolve。
   */
  public static ArchRule charsetForNameRule() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName("com.example.batch.common.utils.EncodingUtils")
        .should(callMethod("java.nio.charset.Charset", "forName"))
        .allowEmptyShould(true)
        .because(
            "CLAUDE.md §字符编码:禁止 Charset.forName(\"UTF-8\") / 字面量;改用 StandardCharsets.UTF_8"
                + " 或 EncodingUtils.resolve(raw)。白名单 = EncodingUtils 自身。");
  }

  /**
   * 禁止 {@code *Record} 后缀的持久化/领域类:CLAUDE.md §持久化(ADR-001)明文统一 {@code *Entity} 后缀。
   *
   * <p>豁免:Java 14+ JEP 359 的"record"类型本身(因为 record 关键字与命名后缀语义不同),仅当类名是 "Record"(裸名)或位于 SDK
   * testkit/example 时跳过。
   */
  public static ArchRule recordSuffixForbiddenRule() {
    return noClasses()
        .that()
        .haveSimpleNameEndingWith("Record")
        .and()
        .haveSimpleNameNotEndingWith("RecordEntity")
        .and(
            new DescribedPredicate<>("is not the bare name \"Record\"") {
              @Override
              public boolean test(JavaClass clazz) {
                return !"Record".equals(clazz.getSimpleName());
              }
            })
        .should(existAtAll())
        .allowEmptyShould(true)
        .because(
            "CLAUDE.md §持久化(ADR-001):表行/领域类一律 *Entity 后缀,禁 *Record。"
                + "新增以 Record 结尾的类必须改名 *Entity(例如 ApiKeyEntity / ImportBadRecordEntity)。");
  }

  /**
   * 禁止同一方法同时标 {@code @EventListener} 与 {@code @Transactional}:CLAUDE.md #4 要求
   * {@code @Transactional} 只放 Service 公共方法。事件监听方法直接挂事务是误用(监听器由框架直接调用, 不走 Service
   * 代理边界,事务语义不清/易吞异常),应改用 {@code TransactionTemplate} 显式包裹。
   */
  public static ArchRule noTransactionalOnEventListenerRule() {
    return ArchRuleDefinition.noMethods()
        .should(
            haveBothAnnotations(
                "org.springframework.context.event.EventListener",
                "org.springframework.transaction.annotation.Transactional"))
        .allowEmptyShould(true)
        .because(
            "CLAUDE.md #4:@EventListener 方法禁直接叠 @Transactional(误用);"
                + "事件监听不走 Service 代理边界,改用 TransactionTemplate 显式包裹事务。");
  }

  /**
   * 禁止同一方法同时标 {@code @Scheduled} 与 {@code @Transactional}:CLAUDE.md #4 要求 {@code @Transactional} 只放
   * Service 公共方法。定时任务方法直接挂事务是误用(调度由框架直接调用, 不走 Service 代理边界),应抽出 Service 方法或改用 {@code
   * TransactionTemplate}。
   */
  public static ArchRule noTransactionalOnScheduledRule() {
    return ArchRuleDefinition.noMethods()
        .should(
            haveBothAnnotations(
                "org.springframework.scheduling.annotation.Scheduled",
                "org.springframework.transaction.annotation.Transactional"))
        .allowEmptyShould(true)
        .because(
            "CLAUDE.md #4:@Scheduled 方法禁直接叠 @Transactional(误用);"
                + "调度不走 Service 代理边界,抽 Service 方法或改用 TransactionTemplate。");
  }

  /** 命中条件 = 方法同时带 triggerAnnotation 与 transactional 两个注解(同一方法)。 */
  private static ArchCondition<com.tngtech.archunit.core.domain.JavaMethod> haveBothAnnotations(
      String triggerAnnotation, String transactionalAnnotation) {
    return new ArchCondition<>(
        "be annotated with both @"
            + triggerAnnotation.substring(triggerAnnotation.lastIndexOf('.') + 1)
            + " and @"
            + transactionalAnnotation.substring(transactionalAnnotation.lastIndexOf('.') + 1)) {
      @Override
      public void check(
          com.tngtech.archunit.core.domain.JavaMethod method, ConditionEvents events) {
        if (method.isAnnotatedWith(triggerAnnotation)
            && method.isAnnotatedWith(transactionalAnnotation)) {
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  method.getFullName()
                      + " 同时标了 @"
                      + triggerAnnotation.substring(triggerAnnotation.lastIndexOf('.') + 1)
                      + " 与 @"
                      + transactionalAnnotation.substring(
                          transactionalAnnotation.lastIndexOf('.') + 1)
                      + " — 违反 CLAUDE.md #4,改用 TransactionTemplate 显式包裹事务"));
        }
      }
    };
  }

  private static ArchCondition<JavaClass> existAtAll() {
    return new ArchCondition<>("not exist (any match = violation)") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        events.add(
            SimpleConditionEvent.violated(
                item,
                item.getName() + " ends with \"Record\" — 违反 CLAUDE.md §持久化命名约束,请改为 *Entity 后缀"));
      }
    };
  }

  /**
   * Spring Boot Application 类的 {@code @SpringBootApplication.scanBasePackages}(或
   * {@code @ComponentScan.basePackages})必须覆盖给定 {@code requiredPrefixes}。
   *
   * <p>"覆盖"= 配置列表里存在某个 entry 等于或为 prefix 的祖先包(如配置 {@code "com.example.batch"} 覆盖 {@code
   * "com.example.batch.common.spi.task"})。
   *
   * <p>防止 P0/P1-B 已踩过的"加新 batch-common 子包但 e2e/app 没扫 → bean 找不到"问题(PR #113 / #114)。
   */
  public static ArchRule componentScanCoversRule(String... requiredPrefixes) {
    return ArchRuleDefinition.classes()
        .that()
        .areAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
        .should(coversRequiredPackages(requiredPrefixes))
        .allowEmptyShould(true)
        .because(
            "新增 com.example.batch.common.<subpkg> 后,所有 SB Application 的 ComponentScan 必须能扫到,"
                + "否则 bean 启动 NoSuchBean。defaults: 加 scanBasePackages=\"com.example.batch\""
                + " 或在列表里显式补 "
                + String.join(", ", requiredPrefixes)
                + "。");
  }

  /**
   * {@code @Component} / Spring stereotype 类有多个 public 构造器时,必须有一个标 {@code @Autowired}。
   *
   * <p>Spring Boot 4 移除了"参数最多者优先"的隐式规则,无标注 → 回退 no-arg → NoSuchMethodException 启动失败 (PR #111 已踩)。
   */
  public static ArchRule multipleCtorsRequireAutowiredRule() {
    return ArchRuleDefinition.classes()
        .that(isSpringStereotype())
        .should(haveExactlyOnePublicCtorOrOneAutowired())
        .allowEmptyShould(true)
        .because(
            "Spring Boot 4 不再隐式取参数最多 ctor;多 ctor 必须显式 @Autowired 标主。否则启动"
                + " NoSuchMethodException(no-arg fallback 没找到)。见 PR #111 fix(main-red).");
  }

  // ─── 上述 2 条规则的辅助 ─────────────────────────────────────────────────────

  private static ArchCondition<JavaClass> coversRequiredPackages(String... requiredPrefixes) {
    String description =
        "have @SpringBootApplication / @ComponentScan covering: "
            + String.join(", ", requiredPrefixes);
    return new ArchCondition<>(description) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        java.util.List<String> scanList = readScanBasePackages(item);
        if (scanList.isEmpty()) {
          // 没显式 scanBasePackages → 默认扫 class 所在包(SB 行为)
          scanList = java.util.List.of(item.getPackageName());
        }
        for (String req : requiredPrefixes) {
          boolean covered =
              scanList.stream().anyMatch(entry -> req.equals(entry) || req.startsWith(entry + "."));
          if (!covered) {
            events.add(
                SimpleConditionEvent.violated(
                    item,
                    item.getName()
                        + ": @ComponentScan basePackages "
                        + scanList
                        + " does not cover required package \""
                        + req
                        + "\""));
          }
        }
      }

      private java.util.List<String> readScanBasePackages(JavaClass clazz) {
        java.util.List<String> out = new java.util.ArrayList<>();
        clazz
            .tryGetAnnotationOfType("org.springframework.boot.autoconfigure.SpringBootApplication")
            .ifPresent(a -> addStringArrayProperty(a, "scanBasePackages", out));
        clazz
            .tryGetAnnotationOfType("org.springframework.context.annotation.ComponentScan")
            .ifPresent(
                a -> {
                  addStringArrayProperty(a, "basePackages", out);
                  addStringArrayProperty(a, "value", out);
                });
        return out;
      }

      private void addStringArrayProperty(
          com.tngtech.archunit.core.domain.JavaAnnotation<?> a,
          String prop,
          java.util.List<String> out) {
        Object v = a.get(prop).orElse(null);
        if (v instanceof String[]) {
          for (String s : (String[]) v) if (s != null && !s.isBlank()) out.add(s);
        } else if (v instanceof String && !((String) v).isBlank()) {
          out.add((String) v);
        }
      }
    };
  }

  private static DescribedPredicate<JavaClass> isSpringStereotype() {
    return new DescribedPredicate<>("is Spring @Component / stereotype") {
      private final java.util.Set<String> stereotypes =
          java.util.Set.of(
              "org.springframework.stereotype.Component",
              "org.springframework.stereotype.Service",
              "org.springframework.stereotype.Repository",
              "org.springframework.stereotype.Controller",
              "org.springframework.web.bind.annotation.RestController",
              "org.springframework.context.annotation.Configuration");

      @Override
      public boolean test(JavaClass clazz) {
        return clazz.getAnnotations().stream()
            .map(a -> a.getRawType().getName())
            .anyMatch(stereotypes::contains);
      }
    };
  }

  private static ArchCondition<JavaClass> haveExactlyOnePublicCtorOrOneAutowired() {
    return new ArchCondition<>("have ≤1 public ctor OR @Autowired on one when ≥2") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        java.util.Set<com.tngtech.archunit.core.domain.JavaConstructor> publicCtors =
            item.getConstructors().stream()
                .filter(
                    c ->
                        c.getModifiers()
                            .contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC))
                .collect(java.util.stream.Collectors.toSet());
        if (publicCtors.size() < 2) {
          return; // 0 或 1 个 public ctor:Spring 自动选,无歧义
        }
        boolean anyAutowired =
            publicCtors.stream()
                .anyMatch(
                    c ->
                        c.isAnnotatedWith(
                            "org.springframework.beans.factory.annotation.Autowired"));
        if (!anyAutowired) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getName()
                      + ": has "
                      + publicCtors.size()
                      + " public ctors but none is @Autowired — Spring Boot 4 will fall back to"
                      + " no-arg ctor and fail startup"));
        }
      }
    };
  }

  private static ArchCondition<JavaClass> callMethod(String targetOwner, String methodName) {
    return new ArchCondition<>("call " + targetOwner + "." + methodName + "(..)") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        item.getMethodCallsFromSelf().stream()
            .filter(call -> call.getTargetOwner().getName().equals(targetOwner))
            .filter(call -> call.getName().equals(methodName))
            .forEach(
                call ->
                    events.add(
                        SimpleConditionEvent.violated(
                            item,
                            item.getName()
                                + " calls "
                                + targetOwner
                                + "."
                                + methodName
                                + "() at "
                                + call.getSourceCodeLocation())));
      }
    };
  }
}
