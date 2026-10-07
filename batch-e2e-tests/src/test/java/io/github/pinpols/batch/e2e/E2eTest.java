package io.github.pinpols.batch.e2e;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;

/** 统一声明 E2E 测试标签及测试环境 profile。 */
@Documented
@Inherited
@Tag("e2e")
@ActiveProfiles({"test", "e2e"})
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface E2eTest {}
