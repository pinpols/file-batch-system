package io.github.pinpols.batch.e2e;

import io.github.pinpols.batch.common.config.ShedLockProviderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Acquires the production JDBC ShedLock directly and holds it until the parent kills this JVM. */
public final class ShedLockLeaseHolderChildProcess {

  private static final String LOCK_NAME = "workflow_run_stuck_reconcile";
  private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(5);

  private ShedLockLeaseHolderChildProcess() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("expected properties, ready marker, and stop marker paths");
    }
    Path propertiesPath = Path.of(args[0]);
    Path readyMarker = Path.of(args[1]);
    Path stopMarker = Path.of(args[2]);
    Properties properties = new Properties();
    try (var input = Files.newInputStream(propertiesPath)) {
      properties.load(input);
    }

    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        properties.getProperty("spring.datasource.url"),
        properties.getProperty("spring.datasource.username"),
        properties.getProperty("spring.datasource.password"));
    LockProvider lockProvider = ShedLockProviderFactory.jdbcTemplateLockProvider(dataSource, false);
    Optional<SimpleLock> acquired = lockProvider.lock(new LockConfiguration(
        Instant.now(), LOCK_NAME, LOCK_AT_MOST_FOR, Duration.ZERO));
    if (acquired.isEmpty()) {
      throw new IllegalStateException("could not acquire the reconciler lease for the test");
    }
    SimpleLock heldLock = acquired.orElseThrow();
    String leaseSnapshot = new JdbcTemplate(dataSource).queryForObject(
        "select concat('lock_until=', lock_until::text, ', utc_now=', "
            + "timezone('utc', current_timestamp)::text, "
            + "', locked_by=', locked_by) from batch.shedlock where name = ?",
        String.class,
        LOCK_NAME);
    Files.writeString(readyMarker, "lease-acquired; " + leaseSnapshot);
    try {
      while (!Files.exists(stopMarker)) {
        Thread.sleep(100L);
      }
    } finally {
      heldLock.unlock();
    }
  }
}
