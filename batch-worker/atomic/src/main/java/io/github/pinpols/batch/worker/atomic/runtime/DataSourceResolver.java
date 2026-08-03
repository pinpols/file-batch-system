package io.github.pinpols.batch.worker.atomic.runtime;

import javax.sql.DataSource;

/** Resolves an explicitly allow-listed DataSource for an atomic executor. */
@FunctionalInterface
public interface DataSourceResolver {

  DataSource resolve(String beanName);
}
