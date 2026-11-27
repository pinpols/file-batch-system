package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 批次日结算栅栏的锁模式验证：准入、派发与回报使用共享读者锁，开日/人工操作/结算使用独占锁。
 *
 * <p>不变量：读者之间不互斥；独占的结算窗口内读者排队；结算提交后读者才能继续，从而看到新的业务日状态。
 */
@SpringBootTest(classes = BatchOrchestratorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("批次日结算栅栏共享读者与独占写者的并发语义")
class BatchDayFenceLockModeIntegrationTest extends AbstractIntegrationTest {

    private static final String ACQUIRE_SHARED = "select batch.acquire_batch_day_settlement_fence_shared(?, ?, ?)";
    private static final String ACQUIRE_EXCLUSIVE = "select batch.acquire_batch_day_settlement_fence(?, ?, ?)";
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 11, 20);

    private final DataSource dataSource;

    @Autowired
    BatchDayFenceLockModeIntegrationTest(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Test
    @DisplayName("两个共享读者可以在同一业务日并发持锁")
    void sharedReaders_doNotBlockEachOther() throws Exception {
        String tenantId = uniqueTenant("shared-pair");
        try (Connection first = openTransaction();
                Connection second = openTransaction()) {
            acquire(first, ACQUIRE_SHARED, tenantId);
            long startedAt = System.nanoTime();
            acquire(second, ACQUIRE_SHARED, tenantId);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(elapsedMillis).isLessThan(1_000L);
            first.rollback();
            second.rollback();
        }
    }

    @Test
    @DisplayName("结算的独占请求会等待共享读者提交后才继续")
    void exclusiveSettlement_waitsForSharedReader() throws Exception {
        String tenantId = uniqueTenant("settle-wait");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection reader = openTransaction()) {
            acquire(reader, ACQUIRE_SHARED, tenantId);
            Future<Long> settlement = executor.submit(() -> acquireInOwnTransaction(ACQUIRE_EXCLUSIVE, tenantId));

            assertThat(awaitBlocked(settlement)).isTrue();

            reader.commit();
            assertThat(settlement.get(5, TimeUnit.SECONDS)).isNotZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("结算窗口内共享读者排队，提交后才能继续并看到新的业务日状态")
    void sharedReader_waitsForSettlementAndObservesCommittedState() throws Exception {
        String tenantId = uniqueTenant("admission-wait");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection settlement = openTransaction()) {
            acquire(settlement, ACQUIRE_EXCLUSIVE, tenantId);
            Future<Long> reader = executor.submit(() -> acquireInOwnTransaction(ACQUIRE_SHARED, tenantId));

            assertThat(awaitBlocked(reader)).isTrue();

            settlement.commit();
            assertThat(reader.get(5, TimeUnit.SECONDS)).isNotZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private Connection openTransaction() throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private long acquireInOwnTransaction(String statementSql, String tenantId) {
        try (Connection connection = openTransaction()) {
            long key = acquire(connection, statementSql, tenantId);
            connection.commit();
            return key;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private long acquire(Connection connection, String statementSql, String tenantId) throws Exception {
        try (var statement = connection.prepareStatement(statementSql)) {
            statement.setString(1, tenantId);
            statement.setString(2, "default");
            statement.setObject(3, BIZ_DATE);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private boolean awaitBlocked(Future<Long> future) throws Exception {
        try {
            future.get(500, TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException blocked) {
            return true;
        } catch (ExecutionException failure) {
            throw new AssertionError("fence acquisition failed", failure.getCause());
        }
    }

    private String uniqueTenant(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
