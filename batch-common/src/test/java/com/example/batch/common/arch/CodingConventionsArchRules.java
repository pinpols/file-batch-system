package com.example.batch.common.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

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
