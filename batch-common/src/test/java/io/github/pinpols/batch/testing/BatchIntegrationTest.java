package io.github.pinpols.batch.testing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 将测试类标记为批量集成测试套件的一部分。
 *
 * <p>通过统一注解保持集成测试的一致性，避免在每个子类中重复声明 JUnit 和 Testcontainers 注解。
 */
@Inherited
@Documented
@Tag("integration")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BatchIntegrationTest {}
