package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 真实 Mapper 与提交后的文件版本状态共同验证去重和新版本写入；不启动其它 Worker 链路。 */
@DisplayName("文件登记：路径去重复用不改变版本标识，新版本原子替换最新标记")
class PlatformFileRecordRepositoryIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.create();

  private static JdbcTemplate jdbcTemplate;
  private static TransactionTemplate transaction;
  private static PlatformFileRecordRepository repository;
  private static PlatformFileRuntimeMapper mapper;

  @BeforeAll
  static void startDatabase() throws Exception {
    POSTGRES.start();
    DataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    String createSchemaSql = "create schema batch";
    jdbcTemplate.execute(createSchemaSql);
    // 使用不可变 V6 的原始 file_record DDL，保留真实字段、CHECK 和唯一约束；不是完整 Flyway 验收。
    Path schemaPath = Path.of("..", "..", "db", "migration", "V6__create_file_tables.sql");
    String schemaSql = Files.readString(schemaPath);
    int firstTable = schemaSql.indexOf("CREATE TABLE");
    int nextTable = schemaSql.indexOf("CREATE TABLE", firstTable + 1);
    String createFileRecordSql = schemaSql.substring(0, nextTable);
    jdbcTemplate.execute(createFileRecordSql);
    // 生产 V124 为 NULL 校验和路径提供唯一围栏，竞争失败必须由外层事务回滚后重试。
    Path hardeningPath =
        Path.of("..", "..", "db", "migration", "V124__r3_constraint_hardening.sql");
    String hardeningSql = Files.readString(hardeningPath);
    int indexStart =
        hardeningSql.indexOf("CREATE UNIQUE INDEX IF NOT EXISTS uk_file_record_no_checksum");
    int indexEnd = hardeningSql.indexOf(';', indexStart);
    String createNullChecksumIndexSql = hardeningSql.substring(indexStart, indexEnd + 1);
    jdbcTemplate.execute(createNullChecksumIndexSql);

    Configuration configuration = new Configuration(
        new Environment("file-record", new SpringManagedTransactionFactory(), dataSource));
    try (InputStream xml = Resources.getResourceAsStream("mapper/PlatformFileRuntimeMapper.xml")) {
      XMLMapperBuilder builder = new XMLMapperBuilder(
          xml,
          configuration,
          "mapper/PlatformFileRuntimeMapper.xml",
          configuration.getSqlFragments());
      builder.parse();
    }
    SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
    SqlSessionTemplate session = new SqlSessionTemplate(factory);
    mapper = session.getMapper(PlatformFileRuntimeMapper.class);
    repository = new PlatformFileRecordRepository(mapper);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clearFiles() {
    String truncateFilesSql = "truncate batch.file_record restart identity";
    jdbcTemplate.execute(truncateFilesSql);
  }

  @Test
  @DisplayName("重复登记无校验和文件返回同一ID且保留唯一的最新版本")
  void shouldKeepLatestFlagWhenReusingFileWithoutChecksum() {
    FileRecordParam file = file("incoming/orders-a.csv", null);
    Long firstId = create(file);

    Long reusedId = create(file);

    assertThat(reusedId).isEqualTo(firstId);
    assertThat(fileCount()).isEqualTo(1);
    assertThat(isLatest(firstId)).isTrue();
    assertThat(generation(firstId)).isEqualTo(1);
  }

  @Test
  @DisplayName("复用历史路径不取消当前最新版本，也不把历史版本重新提升为最新")
  void shouldPreserveCurrentGenerationWhenReusingHistoricalFile() {
    FileRecordParam historical = file("incoming/orders-a.csv", null);
    Long historicalId = create(historical);
    Long currentId = create(file("incoming/orders-b.csv", null));

    Long reusedId = create(historical);

    assertThat(reusedId).isEqualTo(historicalId);
    assertThat(fileCount()).isEqualTo(2);
    assertThat(isLatest(historicalId)).isFalse();
    assertThat(isLatest(currentId)).isTrue();
    assertThat(generation(historicalId)).isEqualTo(1);
    assertThat(generation(currentId)).isEqualTo(2);
  }

  @Test
  @DisplayName("登记新路径创建下一代版本并在同一提交中取消旧版本最新标记")
  void shouldCreateNextGenerationForNewStoragePath() {
    Long firstId = create(file("incoming/orders-a.csv", null));

    Long secondId = create(file("incoming/orders-b.csv", null));

    assertThat(secondId).isNotEqualTo(firstId);
    assertThat(fileCount()).isEqualTo(2);
    assertThat(isLatest(firstId)).isFalse();
    assertThat(isLatest(secondId)).isTrue();
    assertThat(generation(secondId)).isEqualTo(2);
  }

  @Test
  @DisplayName("新登记触发唯一约束失败时回滚旧版本最新标记的更新")
  void shouldRollbackLatestFlagWhenInsertFails() {
    FileRecordParam file = file("incoming/orders-a.csv", "checksum-a");
    Long firstId = create(file);

    assertThatThrownBy(() -> create(file)).isInstanceOf(DuplicateKeyException.class);

    assertThat(fileCount()).isEqualTo(1);
    assertThat(isLatest(firstId)).isTrue();
    assertThat(generation(firstId)).isEqualTo(1);
  }

  @Test
  @DisplayName("无逻辑编码的同路径并发登记仅一方插入，回滚后重试复用且不改变其它文件版本")
  void shouldPreserveLatestFileAfterConcurrentDedupRace() throws Exception {
    Long historicalId = create(file("incoming/orders-a.csv", null));
    FileRecordParam target = file("incoming/orders-b.csv", null, null);
    CountDownLatch bothLookupsFinished = new CountDownLatch(2);
    CountDownLatch continueInserts = new CountDownLatch(1);
    PlatformFileRuntimeMapper coordinatedMapper =
        mock(PlatformFileRuntimeMapper.class, delegatesTo(mapper));
    // 所有读写仍委托真实 Mapper；只在两次真实预查完成后同时放行，稳定制造唯一约束竞争。
    doAnswer(invocation -> {
          String tenantId = invocation.getArgument(0);
          String bucket = invocation.getArgument(1);
          String path = invocation.getArgument(2);
          Object row = mapper.selectFileRecordByStoragePath(tenantId, bucket, path);
          bothLookupsFinished.countDown();
          assertThat(continueInserts.await(10, TimeUnit.SECONDS)).isTrue();
          return row;
        })
        .when(coordinatedMapper)
        .selectFileRecordByStoragePath(any(), any(), any());
    PlatformFileRecordRepository coordinatedRepository =
        new PlatformFileRecordRepository(coordinatedMapper);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    List<Long> insertedIds = new ArrayList<>();
    int rejectedInserts = 0;
    try {
      Future<Long> first = executor.submit(
          () -> transaction.execute(status -> coordinatedRepository.createFileRecord(target)));
      Future<Long> second = executor.submit(
          () -> transaction.execute(status -> coordinatedRepository.createFileRecord(target)));
      assertThat(bothLookupsFinished.await(10, TimeUnit.SECONDS)).isTrue();
      continueInserts.countDown();
      for (Future<Long> attempt : List.of(first, second)) {
        try {
          insertedIds.add(attempt.get(20, TimeUnit.SECONDS));
        } catch (ExecutionException ex) {
          assertThat(ex.getCause()).isInstanceOf(DuplicateKeyException.class);
          rejectedInserts++;
        }
      }
    } finally {
      continueInserts.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(rejectedInserts).isEqualTo(1);
    assertThat(insertedIds).hasSize(1);
    Long retriedId = create(target);
    assertThat(retriedId).isEqualTo(insertedIds.getFirst());
    assertThat(fileCount()).isEqualTo(2);
    assertThat(isLatest(historicalId)).isTrue();
    assertThat(isLatest(retriedId)).isTrue();
    assertThat(generation(retriedId)).isEqualTo(1);
  }

  @Test
  @DisplayName("不同新路径并发登记同一逻辑文件时按提交顺序递增版本且只保留一代最新")
  void shouldSerializeGenerationsForConcurrentNewPaths() throws Exception {
    ConcurrentFileRegistration result = concurrentRegistrations(true, "incoming/orders-c.csv");

    assertThat(result.versions()).containsExactly("1:false", "2:false", "3:true");
    assertThat(result.historicalId())
        .isNotEqualTo(result.firstId())
        .isNotEqualTo(result.secondId());
    assertThat(result.firstId()).isNotEqualTo(result.secondId());
  }

  @Test
  @DisplayName("相同逻辑编码和路径并发登记时等待前一事务提交后复用同一版本")
  void shouldReuseSameGenerationForConcurrentSamePath() throws Exception {
    ConcurrentFileRegistration result = concurrentRegistrations(true, "incoming/orders-b.csv");

    assertThat(result.versions()).containsExactly("1:false", "2:true");
    assertThat(result.firstId()).isEqualTo(result.secondId());
  }

  @Test
  @DisplayName("尚无历史记录的逻辑文件也能串行登记首代和第二代")
  void shouldSerializeFirstGenerationsWithoutExistingFileRows() throws Exception {
    ConcurrentFileRegistration result = concurrentRegistrations(false, "incoming/orders-c.csv");

    assertThat(result.historicalId()).isNull();
    assertThat(result.versions()).containsExactly("1:false", "2:true");
    assertThat(result.firstId()).isNotEqualTo(result.secondId());
  }

  @Test
  @DisplayName("版本事务锁按租户和文件编码隔离，分隔符不同组合也不共享同一逻辑身份")
  void shouldKeepUnrelatedFileGenerationLocksIndependent() throws Exception {
    CountDownLatch ownerLocked = new CountDownLatch(1);
    CountDownLatch releaseOwner = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> owner = executor.submit(() -> transaction.execute(status -> {
        assertThat(mapper.lockFileGeneration("tenant:a", "orders")).isEqualTo(1);
        ownerLocked.countDown();
        try {
          assertThat(releaseOwner.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(ex);
        }
        return null;
      }));
      assertThat(ownerLocked.await(10, TimeUnit.SECONDS)).isTrue();
      Future<?> unrelated = executor.submit(() -> transaction.execute(status -> {
        assertThat(mapper.lockFileGeneration("other-tenant", "orders")).isEqualTo(1);
        assertThat(mapper.lockFileGeneration("tenant:a", "other-file")).isEqualTo(1);
        assertThat(mapper.lockFileGeneration("tenant", "a:orders")).isEqualTo(1);
        return null;
      }));
      unrelated.get(5, TimeUnit.SECONDS);
      releaseOwner.countDown();
      owner.get(5, TimeUnit.SECONDS);
    } finally {
      releaseOwner.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static ConcurrentFileRegistration concurrentRegistrations(
      boolean seedHistory, String secondPath) throws Exception {
    Long historicalId = seedHistory ? create(file("incoming/orders-a.csv", null)) : null;
    CountDownLatch firstDemotionHeld = new CountDownLatch(1);
    CountDownLatch finishFirstInsert = new CountDownLatch(1);
    CountDownLatch secondTransactionStarted = new CountDownLatch(1);
    AtomicInteger demotions = new AtomicInteger();
    PlatformFileRuntimeMapper coordinatedMapper =
        mock(PlatformFileRuntimeMapper.class, delegatesTo(mapper));
    // 真实更新已持有数据库行锁；暂停首笔插入，让第二笔真实事务进入锁等待。
    doAnswer(invocation -> {
          String tenantId = invocation.getArgument(0);
          String fileCode = invocation.getArgument(1);
          int changedRows = mapper.markHistoricalFileNotLatest(tenantId, fileCode);
          if (demotions.incrementAndGet() == 1) {
            firstDemotionHeld.countDown();
            assertThat(finishFirstInsert.await(10, TimeUnit.SECONDS)).isTrue();
          }
          return changedRows;
        })
        .when(coordinatedMapper)
        .markHistoricalFileNotLatest(any(), any());
    PlatformFileRecordRepository coordinatedRepository =
        new PlatformFileRecordRepository(coordinatedMapper);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Long firstId;
    Long secondId;
    try {
      Future<Long> first = executor.submit(() -> transaction.execute(
          status -> coordinatedRepository.createFileRecord(file("incoming/orders-b.csv", null))));
      assertThat(firstDemotionHeld.await(10, TimeUnit.SECONDS)).isTrue();
      Future<Long> second = executor.submit(() -> transaction.execute(status -> {
        String applicationNameSql = "set local application_name = 'file-generation-contender'";
        jdbcTemplate.execute(applicationNameSql);
        secondTransactionStarted.countDown();
        return coordinatedRepository.createFileRecord(file(secondPath, null));
      }));
      assertThat(secondTransactionStarted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitGenerationLockWait();
      finishFirstInsert.countDown();
      firstId = first.get(20, TimeUnit.SECONDS);
      secondId = second.get(20, TimeUnit.SECONDS);
    } finally {
      finishFirstInsert.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    String versionStateSql = """
        select file_generation_no::text || ':' || is_latest::text
        from batch.file_record order by id
        """;
    List<String> versions = jdbcTemplate.queryForList(versionStateSql, String.class);
    return new ConcurrentFileRegistration(historicalId, firstId, secondId, versions);
  }

  private static void awaitGenerationLockWait() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    String waitingTransactionSql = """
        select count(*) from pg_stat_activity
        where application_name = 'file-generation-contender' and wait_event_type = 'Lock'
        """;
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(waitingTransactionSql, Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("generation contender did not wait for a database lock");
  }

  private record ConcurrentFileRegistration(
      Long historicalId, Long firstId, Long secondId, List<String> versions) {}

  private static FileRecordParam file(String path, String checksum) {
    return file(path, checksum, "orders");
  }

  private static FileRecordParam file(String path, String checksum, String fileCode) {
    return FileRecordParam.builder()
        .tenantId("file-dedup-tenant")
        .fileCode(fileCode)
        .fileCategory("INPUT")
        .fileName("orders.csv")
        .fileFormatType("DELIMITED")
        .storageType("S3")
        .storageBucket("batch-test")
        .storagePath(path)
        .checksumValue(checksum)
        .sourceType("UPLOAD")
        .fileStatus("RECEIVED")
        .build();
  }

  private static Long create(FileRecordParam file) {
    return transaction.execute(status -> repository.createFileRecord(file));
  }

  private static boolean isLatest(Long fileId) {
    String selectLatestSql = "select is_latest from batch.file_record where id = ?";
    return Boolean.TRUE.equals(jdbcTemplate.queryForObject(selectLatestSql, Boolean.class, fileId));
  }

  private static Integer generation(Long fileId) {
    String selectGenerationSql = "select file_generation_no from batch.file_record where id = ?";
    return jdbcTemplate.queryForObject(selectGenerationSql, Integer.class, fileId);
  }

  private static Integer fileCount() {
    String countFilesSql = "select count(*) from batch.file_record";
    return jdbcTemplate.queryForObject(countFilesSql, Integer.class);
  }
}
