import org.flywaydb.core.Flyway;

import java.util.Map;

/** 为 CI 临时 PostgreSQL 执行与 Orchestrator 相同的正式平台迁移。 */
public final class FlywayMigrate {

  private static final System.Logger LOGGER = System.getLogger(FlywayMigrate.class.getName());

  private FlywayMigrate() {}

  public static void main(String[] args) {
    if (args.length != 3) {
      throw new IllegalArgumentException("usage: <jdbc-url> <user> <password>");
    }

    var result =
        Flyway.configure()
            .dataSource(args[0], args[1], args[2])
            .locations("filesystem:db/migration")
            .defaultSchema("batch")
            .schemas("batch", "quartz")
            .baselineOnMigrate(true)
            .baselineVersion("1")
            .validateOnMigrate(true)
            .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
            .load()
            .migrate();
    LOGGER.log(
        System.Logger.Level.INFO,
        "flyway migrate OK: initial={0} target={1} executed={2}",
        result.initialSchemaVersion,
        result.targetSchemaVersion,
        result.migrationsExecuted);
  }
}
