package io.github.pinpols.batch.e2e.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

/** 在独立 E2E 业务库执行业务表结构脚本。 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Sql(
    scripts = E2eTestSql.BIZ_SCHEMA,
    config =
        @SqlConfig(
            dataSource = "e2eBusinessDataSource",
            transactionManager = "e2eBusinessTransactionManager"))
public @interface E2eBusinessSchema {}
