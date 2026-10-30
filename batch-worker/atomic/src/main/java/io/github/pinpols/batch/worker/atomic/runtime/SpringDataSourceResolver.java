package io.github.pinpols.batch.worker.atomic.runtime;

import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.stereotype.Component;

/** 原子执行器 DataSource 端口的 Spring 适配器。 */
@Component
public class SpringDataSourceResolver implements DataSourceResolver {

  private final BeanFactory beanFactory;
  private final DataSource defaultDataSource;

  public SpringDataSourceResolver(BeanFactory beanFactory, DataSource defaultDataSource) {
    this.beanFactory = beanFactory;
    this.defaultDataSource = defaultDataSource;
  }

  @Override
  public DataSource resolve(String beanName) {
    if (beanName == null) {
      return Objects.requireNonNull(defaultDataSource, "defaultDataSource required");
    }
    return beanFactory.getBean(beanName, DataSource.class);
  }
}
