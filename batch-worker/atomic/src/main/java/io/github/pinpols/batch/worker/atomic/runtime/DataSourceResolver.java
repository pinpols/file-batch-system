package io.github.pinpols.batch.worker.atomic.runtime;

import javax.sql.DataSource;

/** 为原子执行器解析显式加入允许列表的 DataSource。 */
@FunctionalInterface
public interface DataSourceResolver {

  DataSource resolve(String beanName);
}
