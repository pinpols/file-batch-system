package io.github.pinpols.batch.common.rls;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** 业务数据源账号能够绕过 PostgreSQL RLS 时报告 DOWN。 */
public final class BusinessDataSourceRoleHealthIndicator implements HealthIndicator {

  private final BusinessDataSourceRoleChecker checker;

  public BusinessDataSourceRoleHealthIndicator(DataSource businessDataSource) {
    this.checker = new BusinessDataSourceRoleChecker(businessDataSource);
  }

  @Override
  public Health health() {
    try {
      BusinessDataSourceRoleChecker.Result result = checker.check();
      if (result.isClean()) {
        return Health.up().build();
      }
      return Health.down()
          .withDetail("unsafeBusinessDatabaseRoles", result.unsafeRoles())
          .build();
    } catch (SQLException exception) {
      return Health.down().withException(exception).build();
    }
  }
}
