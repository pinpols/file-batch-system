package io.github.pinpols.batch.common.startup;

import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

/** 在 V210/V211 校验密钥载荷约束前，使用应用 KMS 把历史明文 JSON 转换为加密信封。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecretPayloadFlywayCallback implements Callback {

  private static final int BATCH_SIZE = 100;
  private static final Set<String> TARGET_VERSIONS = Set.of("210", "211");
  private static final String SELECT_CANDIDATES_SQL = """
      select id, tenant_id, secret_payload::text
        from batch.secret_version
       where secret_payload is not null
         and coalesce(secret_payload ->> 'format', '') <> ?
       order by id
       limit ?
      """;
  private static final String UPDATE_PAYLOAD_SQL = """
      update batch.secret_version
         set secret_payload = ?::jsonb,
             updated_by = 'secret-payload-migration',
             updated_at = current_timestamp
       where id = ?
         and tenant_id = ?
         and secret_payload is not null
         and coalesce(secret_payload ->> 'format', '') <> ?
      """;

  private final SecretPayloadProtector secretPayloadProtector;

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.BEFORE_EACH_MIGRATE && isTargetMigration(context.getMigrationInfo());
  }

  @Override
  public boolean canHandleInTransaction(Event event, Context context) {
    return true;
  }

  @Override
  public void handle(Event event, Context context) {
    try {
      int migrated = migrateLegacyPayloads(context.getConnection());
      if (migrated > 0) {
        log.info(
            "legacy secret payload encryption completed before Flyway V{}: migratedCount={}",
            context.getMigrationInfo().getVersion(),
            migrated);
      }
    } catch (SQLException exception) {
      throw new FlywayException(
          "failed to encrypt legacy secret payloads before migration", exception);
    }
  }

  @Override
  public String getCallbackName() {
    return "secret-payload-protection";
  }

  private int migrateLegacyPayloads(Connection connection) throws SQLException {
    int migrated = 0;
    while (true) {
      List<LegacySecretPayload> candidates = selectCandidates(connection);
      if (EmptyChecks.isEmpty(candidates)) {
        return migrated;
      }
      int migratedInBatch = updateCandidates(connection, candidates);
      if (migratedInBatch == 0) {
        if (EmptyChecks.isEmpty(selectCandidates(connection))) {
          return migrated;
        }
        throw new FlywayException("legacy secret payload encryption made no progress");
      }
      migrated += migratedInBatch;
    }
  }

  private List<LegacySecretPayload> selectCandidates(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SELECT_CANDIDATES_SQL)) {
      statement.setString(1, SecretPayloadProtector.FORMAT);
      statement.setInt(2, BATCH_SIZE);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<LegacySecretPayload> candidates = new ArrayList<>();
        while (resultSet.next()) {
          candidates.add(new LegacySecretPayload(
              resultSet.getLong("id"),
              resultSet.getString("tenant_id"),
              resultSet.getString("secret_payload")));
        }
        return candidates;
      }
    }
  }

  private int updateCandidates(Connection connection, List<LegacySecretPayload> candidates)
      throws SQLException {
    int migrated = 0;
    try (PreparedStatement statement = connection.prepareStatement(UPDATE_PAYLOAD_SQL)) {
      for (LegacySecretPayload candidate : candidates) {
        statement.setString(1, secretPayloadProtector.protect(candidate.payload()));
        statement.setLong(2, candidate.id());
        statement.setString(3, candidate.tenantId());
        statement.setString(4, SecretPayloadProtector.FORMAT);
        migrated += statement.executeUpdate();
      }
    }
    return migrated;
  }

  private boolean isTargetMigration(MigrationInfo migrationInfo) {
    return !EmptyChecks.isNull(migrationInfo)
        && !EmptyChecks.isNull(migrationInfo.getVersion())
        && TARGET_VERSIONS.contains(migrationInfo.getVersion().toString());
  }

  private record LegacySecretPayload(long id, String tenantId, String payload) {}
}
