package io.github.pinpols.batch.common.stateful;

import io.github.pinpols.batch.common.utils.Texts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Persists and validates the backend identity of a stateful pluggable feature.
 *
 * <p>The first deployment records a baseline. A later backend or location change requires a
 * non-reused cutover id; otherwise startup fails before the application becomes ready.
 */
public final class StatefulBackendGuard {

  private static final int FEATURE_KEY_MAX = 160;
  private static final int BACKEND_MAX = 64;
  private static final int IDENTITY_MAX = 1024;
  private static final int CUTOVER_ID_MAX = 160;
  private static final int ACTOR_MAX = 160;

  private final DataSource dataSource;

  public StatefulBackendGuard(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public GuardResult verify(DesiredBackend desired) {
    DesiredBackend normalized = normalize(desired);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        lockFeature(connection, normalized.featureKey());
        CurrentBinding current = selectCurrent(connection, normalized.featureKey());
        GuardResult result;
        if (current == null) {
          insertBaseline(connection, normalized);
          result = new GuardResult(GuardAction.BASELINE_RECORDED, 0L);
        } else if (current.matches(normalized)) {
          result = new GuardResult(GuardAction.VERIFIED, current.generation());
        } else {
          result = cutover(connection, current, normalized);
        }
        connection.commit();
        return result;
      } catch (RuntimeException | SQLException ex) {
        rollback(connection, ex);
        throw ex;
      }
    } catch (StatefulBackendSwitchRejectedException ex) {
      throw ex;
    } catch (SQLException ex) {
      throw new IllegalStateException(
          "stateful backend guard could not access batch.stateful_backend_binding; "
              + "ensure Flyway V193 is applied",
          ex);
    }
  }

  private GuardResult cutover(Connection connection, CurrentBinding current, DesiredBackend desired)
      throws SQLException {
    if (!Texts.hasText(desired.cutoverId())) {
      throw new StatefulBackendSwitchRejectedException(
          "stateful backend switch rejected for "
              + desired.featureKey()
              + ": registered="
              + current.backend()
              + " ("
              + current.backendIdentity()
              + "), requested="
              + desired.backend()
              + " ("
              + desired.backendIdentity()
              + "). Drain writes, migrate or explicitly accept the transition, then set a new "
              + "one-time cutover-id.");
    }
    if (cutoverIdWasUsed(connection, desired.featureKey(), desired.cutoverId())) {
      throw new StatefulBackendSwitchRejectedException(
          "stateful backend switch rejected for "
              + desired.featureKey()
              + ": cutover-id "
              + desired.cutoverId()
              + " was already used");
    }

    long generation = current.generation() + 1L;
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE batch.stateful_backend_binding
               SET backend = ?,
                   backend_identity = ?,
                   generation = ?,
                   last_cutover_id = ?,
                   updated_by = ?,
                   updated_at = CURRENT_TIMESTAMP
             WHERE feature_key = ?
            """)) {
      statement.setString(1, desired.backend());
      statement.setString(2, desired.backendIdentity());
      statement.setLong(3, generation);
      statement.setString(4, desired.cutoverId());
      statement.setString(5, desired.actor());
      statement.setString(6, desired.featureKey());
      statement.executeUpdate();
    }
    insertHistory(connection, current, desired, generation, "CUTOVER");
    return new GuardResult(GuardAction.CUTOVER_RECORDED, generation);
  }

  private void insertBaseline(Connection connection, DesiredBackend desired) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO batch.stateful_backend_binding
                (feature_key, backend, backend_identity, generation, updated_by)
            VALUES (?, ?, ?, 0, ?)
            """)) {
      statement.setString(1, desired.featureKey());
      statement.setString(2, desired.backend());
      statement.setString(3, desired.backendIdentity());
      statement.setString(4, desired.actor());
      statement.executeUpdate();
    }
    insertHistory(connection, null, desired, 0L, "BASELINE");
  }

  private void insertHistory(
      Connection connection,
      CurrentBinding current,
      DesiredBackend desired,
      long generation,
      String action)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO batch.stateful_backend_cutover_history
                (feature_key, generation, action,
                 previous_backend, previous_backend_identity,
                 target_backend, target_backend_identity, cutover_id, changed_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, desired.featureKey());
      statement.setLong(2, generation);
      statement.setString(3, action);
      statement.setString(4, current == null ? null : current.backend());
      statement.setString(5, current == null ? null : current.backendIdentity());
      statement.setString(6, desired.backend());
      statement.setString(7, desired.backendIdentity());
      statement.setString(8, "CUTOVER".equals(action) ? desired.cutoverId() : null);
      statement.setString(9, desired.actor());
      statement.executeUpdate();
    }
  }

  private CurrentBinding selectCurrent(Connection connection, String featureKey)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT backend, backend_identity, generation
              FROM batch.stateful_backend_binding
             WHERE feature_key = ?
             FOR UPDATE
            """)) {
      statement.setString(1, featureKey);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return new CurrentBinding(
            resultSet.getString("backend"),
            resultSet.getString("backend_identity"),
            resultSet.getLong("generation"));
      }
    }
  }

  private boolean cutoverIdWasUsed(Connection connection, String featureKey, String cutoverId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT 1
              FROM batch.stateful_backend_cutover_history
             WHERE feature_key = ? AND cutover_id = ?
            """)) {
      statement.setString(1, featureKey);
      statement.setString(2, cutoverId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  private void lockFeature(Connection connection, String featureKey) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      statement.setString(1, featureKey);
      statement.execute();
    }
  }

  private static DesiredBackend normalize(DesiredBackend desired) {
    if (desired == null) {
      throw new IllegalArgumentException("desired backend is required");
    }
    return new DesiredBackend(
        required(desired.featureKey(), "featureKey", FEATURE_KEY_MAX),
        required(desired.backend(), "backend", BACKEND_MAX).toLowerCase(),
        required(desired.backendIdentity(), "backendIdentity", IDENTITY_MAX),
        optional(desired.cutoverId(), "cutoverId", CUTOVER_ID_MAX),
        required(desired.actor(), "actor", ACTOR_MAX));
  }

  private static String required(String value, String field, int maxLength) {
    if (!Texts.hasText(value)) {
      throw new IllegalArgumentException(field + " is required");
    }
    return bounded(value.trim(), field, maxLength);
  }

  private static String optional(String value, String field, int maxLength) {
    return Texts.hasText(value) ? bounded(value.trim(), field, maxLength) : null;
  }

  private static String bounded(String value, String field, int maxLength) {
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
    }
    return value;
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
    }
  }

  public record DesiredBackend(
      String featureKey, String backend, String backendIdentity, String cutoverId, String actor) {}

  public record GuardResult(GuardAction action, long generation) {}

  public enum GuardAction {
    VERIFIED,
    BASELINE_RECORDED,
    CUTOVER_RECORDED
  }

  private record CurrentBinding(String backend, String backendIdentity, long generation) {
    private boolean matches(DesiredBackend desired) {
      return backend.equals(desired.backend()) && backendIdentity.equals(desired.backendIdentity());
    }
  }
}
