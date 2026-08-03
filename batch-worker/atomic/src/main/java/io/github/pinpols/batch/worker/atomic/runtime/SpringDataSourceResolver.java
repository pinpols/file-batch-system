package io.github.pinpols.batch.worker.atomic.runtime;

import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.stereotype.Component;

/** Spring adapter for the atomic executor DataSource port. */
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
