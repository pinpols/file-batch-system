package io.github.pinpols.batch.testing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * 临时隔离已确认的不稳定测试。
 *
 * <p>隔离项必须关联治理 Issue、责任人和到期日；默认测试门禁不重跑这些测试，定时任务单独重复执行。
 */
@Documented
@Tag("flaky")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface FlakyTest {

  String issue();

  String owner();

  String expiresOn();
}
