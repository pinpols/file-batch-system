package io.github.pinpols.batch.common.rls;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/** 业务数据源使用可绕过 RLS 的账号时拒绝应用启动。 */
public final class BusinessDataSourceRoleStartupCheck {

  private final BusinessDataSourceRoleChecker checker;

  public BusinessDataSourceRoleStartupCheck(DataSource businessDataSource) {
    this.checker = new BusinessDataSourceRoleChecker(businessDataSource);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void checkOnStartup() {
    try {
      BusinessDataSourceRoleChecker.Result result = checker.check();
      if (!result.isClean()) {
        throw new IllegalStateException("Business datasource role bypasses PostgreSQL RLS: "
            + result.unsafeRoles()
            + ". Use batch_business_writer or another NOSUPERUSER NOBYPASSRLS role.");
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Business datasource role verification failed: " + exception.getMessage(), exception);
    }
  }
}
