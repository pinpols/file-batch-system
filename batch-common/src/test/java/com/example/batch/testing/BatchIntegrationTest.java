package com.example.batch.testing;

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
 * Marks a test class as part of the batch integration-test suite.
 *
 * <p>This keeps integration tests consistent without repeating the same JUnit and Testcontainers
 * annotations in every child class.
 */
@Inherited
@Documented
@Tag("integration")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BatchIntegrationTest {
}
