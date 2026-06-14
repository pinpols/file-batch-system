package com.example.batch.console.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.example.batch.common.dto.CommonResponse;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 守护 CLAUDE.md §Java 编码细则的两条 controller/事务红线(此前仅人工评审):
 *
 * <ul>
 *   <li>#6 Controller 端点(@RequestMapping 系)返回值一律 {@link CommonResponse};二进制下载 / 流式响应 豁免 {@code
 *       ResponseEntity} / {@code SseEmitter};禁裸返 DTO 或自封装 envelope。
 *   <li>#4 {@code @Transactional} 只放 Service 公共方法,禁放 Controller / Mapper。
 * </ul>
 */
class ConsoleApiContractArchTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.example.batch.console..");

  /** 端点方法允许的返回类型:统一信封 + 二进制/流式响应的框架类型 + void。 */
  private static final Set<String> ALLOWED_RETURN_TYPES =
      Set.of(
          CommonResponse.class.getName(),
          "org.springframework.http.ResponseEntity",
          "org.springframework.web.servlet.mvc.method.annotation.SseEmitter",
          "void");

  /** 直接或元注解标了 @RequestMapping(含 @GetMapping/@PostMapping 等)的方法即 HTTP 端点。 */
  private static final DescribedPredicate<JavaMethod> ARE_ENDPOINT_METHODS =
      new DescribedPredicate<>("are HTTP endpoint methods") {
        @Override
        public boolean test(JavaMethod method) {
          return method.isAnnotatedWith(RequestMapping.class)
              || method.isMetaAnnotatedWith(RequestMapping.class);
        }
      };

  private static final ArchCondition<JavaMethod> RETURN_ALLOWED_TYPE =
      new ArchCondition<>("return CommonResponse / ResponseEntity / SseEmitter / void") {
        @Override
        public void check(JavaMethod method, ConditionEvents events) {
          String returnType = method.getRawReturnType().getFullName();
          if (!ALLOWED_RETURN_TYPES.contains(returnType)) {
            events.add(
                SimpleConditionEvent.violated(
                    method,
                    String.format(
                        "%s 返回 %s,违反 CLAUDE.md #6(应返 CommonResponse;下载/流式才用"
                            + " ResponseEntity/SseEmitter)",
                        method.getFullName(), returnType)));
          }
        }
      };

  @Test
  void endpointMethodsReturnCommonResponse() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .areAnnotatedWith(RestController.class)
        .and(ARE_ENDPOINT_METHODS)
        .should(RETURN_ALLOWED_TYPE)
        .check(CLASSES);
  }

  @Test
  void transactionalNotOnControllers() {
    noMethods()
        .that()
        .areAnnotatedWith(Transactional.class)
        .should()
        .beDeclaredInClassesThat(isControllerOrMapper())
        .check(CLASSES);
  }

  @Test
  void transactionalClassNotOnControllers() {
    noClasses()
        .that(isControllerOrMapper())
        .should()
        .beAnnotatedWith(Transactional.class)
        .check(CLASSES);
  }

  private static DescribedPredicate<JavaClass> isControllerOrMapper() {
    return new DescribedPredicate<>("are @Controller/@RestController or *Mapper") {
      @Override
      public boolean test(JavaClass clazz) {
        return clazz.isAnnotatedWith(RestController.class)
            || clazz.isAnnotatedWith(Controller.class)
            || clazz.getSimpleName().endsWith("Mapper");
      }
    };
  }
}
