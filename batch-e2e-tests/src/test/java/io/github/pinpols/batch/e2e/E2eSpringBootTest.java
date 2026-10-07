package io.github.pinpols.batch.e2e;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AliasFor;

/** 统一 E2E profile 和标签，同时保留各测试对 Spring 应用上下文的显式配置。 */
@Documented
@Inherited
@E2eTest
@SpringBootTest
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface E2eSpringBootTest {

  @AliasFor(annotation = SpringBootTest.class, attribute = "classes")
  Class<?>[] classes() default {};

  @AliasFor(annotation = SpringBootTest.class, attribute = "webEnvironment")
  SpringBootTest.WebEnvironment webEnvironment() default SpringBootTest.WebEnvironment.MOCK;

  @AliasFor(annotation = SpringBootTest.class, attribute = "properties")
  String[] properties() default {};
}
