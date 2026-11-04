package io.github.pinpols.batch.testing;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 内建 Worker 平台库受限角色演练（ARCH-20261103-01）。
 *
 * <p>以 {@code -Dbatch.drill.platform-worker-role=<worker>} 启用，只作用于 {@link AbstractIntegrationTest}
 * 的 Spring 上下文。平台库连接仍以 owner 登录：Spring Flyway 迁移完成后，用已提交的
 * {@code scripts/db/platform-worker-roles.sql} 给演练角色授权；此后生产代码发出的语句先 {@code SET ROLE}
 * 到该角色执行，测试 fixture、断言和 Flyway 保持 owner。这样触发器、RLS 与行锁都按受限角色校验，
 * 而 fixture 写 Orchestrator 所有的表不会混入结论。权限拒绝（42501）与授权前执行的生产语句写入
 * {@code target/platform-worker-role-drill/<worker>.tsv}。
 */
public final class PlatformWorkerRoleDrill {

  /** 启用演练并指定 Worker（import/export/process/dispatch/atomic）的系统属性。 */
  public static final String WORKER_PROPERTY = "batch.drill.platform-worker-role";

  private static final String ROLES_SCRIPT = "scripts/db/platform-worker-roles.sql";
  private static final String SCRIPT_IN_CONTAINER = "/tmp/platform-worker-roles.sql";
  private static final String PERMISSION_DENIED = "42501";
  private static final String IN_FAILED_TRANSACTION = "25P02";
  private static final Set<String> EXECUTE_METHODS = Set.of(
      "execute",
      "executeQuery",
      "executeUpdate",
      "executeLargeUpdate",
      "executeBatch",
      "executeLargeBatch");

  private static final String WORKER_PACKAGE = "io.github.pinpols.batch.worker.";
  private static final List<String> CONTROL_PLANE_PACKAGES = List.of(
      "io.github.pinpols.batch.orchestrator.",
      "io.github.pinpols.batch.trigger.",
      "io.github.pinpols.batch.console.");
  // E2E 把 Orchestrator 与 Worker 装进同一上下文并共用平台库数据源，只有调用栈能区分语句归属。
  private static final boolean SHARED_CONTROL_PLANE_CONTEXT =
      isPresent("io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication");

  private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
  private static final AtomicLong RESTRICTED = new AtomicLong();
  private static final AtomicLong UNATTRIBUTED = new AtomicLong();
  private static volatile String preparedRole;

  private PlatformWorkerRoleDrill() {}

  private static boolean isPresent(String className) {
    try {
      Class.forName(className, false, PlatformWorkerRoleDrill.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException absent) {
      return false;
    }
  }

  static Optional<String> configuredWorker() {
    String worker = System.getProperty(WORKER_PROPERTY);
    return worker == null || worker.isBlank() ? Optional.empty() : Optional.of(worker.trim());
  }

  static BeanPostProcessor postProcessor(String worker) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        // E2E 的 <worker>PlatformDataSource 直接返回共享的 dataSource，已包装的不再重复包装。
        if (bean instanceof DataSource dataSource
            && !(bean instanceof DrillDataSource)
            && isPlatformDataSource(beanName)) {
          return new DrillDataSource(dataSource, worker);
        }
        // Spring Boot 的 Flyway 初始化器在 afterPropertiesSet 中完成迁移；之后表结构齐全才能授权。
        if (bean.getClass().getName().endsWith("FlywayMigrationInitializer")) {
          prepareRole(worker);
        }
        return bean;
      }
    };
  }

  static boolean isPrepared() {
    return preparedRole != null;
  }

  /** Worker 平台库数据源名为 {@code <worker>PlatformDataSource}；Dispatch/Atomic 使用 Boot 默认的 {@code dataSource}。 */
  private static boolean isPlatformDataSource(String beanName) {
    return "dataSource".equals(beanName) || beanName.endsWith("PlatformDataSource");
  }

  private static synchronized void prepareRole(String worker) {
    if (preparedRole != null) {
      return;
    }
    String role = "drill_worker_" + worker;
    var postgres = AbstractIntegrationTest.PLATFORM_POSTGRES;
    try {
      exec(
          postgres,
          "psql",
          "-X",
          "-v",
          "ON_ERROR_STOP=1",
          "-U",
          postgres.getUsername(),
          "-d",
          postgres.getDatabaseName(),
          "-c",
          "DO $$ BEGIN CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS;"
              + " EXCEPTION WHEN duplicate_object THEN NULL; END $$");
      postgres.copyFileToContainer(
          MountableFile.forHostPath(repositoryRoot().resolve(ROLES_SCRIPT).toString()),
          SCRIPT_IN_CONTAINER);
      exec(
          postgres,
          "psql",
          "-X",
          "-v",
          "ON_ERROR_STOP=1",
          "-v",
          "action=grant",
          "-v",
          worker + "_role=" + role,
          "-U",
          postgres.getUsername(),
          "-d",
          postgres.getDatabaseName(),
          "-f",
          SCRIPT_IN_CONTAINER);
    } catch (IOException failure) {
      throw new IllegalStateException("受限角色演练授权失败", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("受限角色演练授权被中断", failure);
    }
    preparedRole = role;
    // 汇总行证明演练确实以受限角色执行过语句，避免全部落到 owner 时报告为空却被误读为通过。
    Runtime.getRuntime()
        .addShutdownHook(new Thread(() -> report(
            worker,
            "SUMMARY",
            null,
            "restricted=" + RESTRICTED.get() + " unattributed=" + UNATTRIBUTED.get())));
  }

  private static void exec(PostgreSQLContainer postgres, String... command)
      throws IOException, InterruptedException {
    ExecResult result = postgres.execInContainer(command);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException("psql 执行失败: " + result.getStderr());
    }
  }

  private static Path repositoryRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null && !Files.exists(current.resolve(ROLES_SCRIPT))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException("找不到 " + ROLES_SCRIPT);
    }
    return current;
  }

  /**
   * 按调用栈判定是否以受限角色执行：Flyway（含其回调）、测试代码和控制面保持 owner；首个可归属的项目帧
   * 在 Worker 包内时受限。只有 batch-common 等共享帧时，独立 Worker 应用按 Worker 处理，E2E 共享上下文
   * 无法归属则保持 owner 并计数。
   */
  private static boolean isWorkerCall() {
    List<Caller> callers = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
        .walk(frames -> frames
            .map(StackWalker.StackFrame::getDeclaringClass)
            .map(PlatformWorkerRoleDrill::classify)
            .filter(caller -> caller != Caller.UNKNOWN)
            .toList());
    if (callers.contains(Caller.MIGRATION)) {
      return false;
    }
    Caller first = callers.stream()
        .filter(caller -> caller != Caller.SHARED)
        .findFirst()
        .orElse(Caller.SHARED);
    if (first == Caller.SHARED) {
      if (SHARED_CONTROL_PLANE_CONTEXT) {
        UNATTRIBUTED.incrementAndGet();
        return false;
      }
      return true;
    }
    return first == Caller.WORKER;
  }

  private enum Caller {
    MIGRATION,
    FIXTURE,
    CONTROL_PLANE,
    WORKER,
    SHARED,
    UNKNOWN
  }

  private static Caller classify(Class<?> type) {
    String name = type.getName();
    if (name.startsWith(PlatformWorkerRoleDrill.class.getName())) {
      return Caller.UNKNOWN;
    }
    if (name.startsWith("org.flywaydb.")) {
      return Caller.MIGRATION;
    }
    if (name.startsWith("org.springframework.test.")) {
      return Caller.FIXTURE;
    }
    if (!name.startsWith("io.github.pinpols.batch.")) {
      return Caller.UNKNOWN;
    }
    CodeSource source = type.getProtectionDomain().getCodeSource();
    String location = source == null ? "" : source.getLocation().toString();
    if (location.contains("/test-classes/") || location.contains("batch-test-support")) {
      return Caller.FIXTURE;
    }
    if (name.startsWith(WORKER_PACKAGE)) {
      return Caller.WORKER;
    }
    return CONTROL_PLANE_PACKAGES.stream().anyMatch(name::startsWith)
        ? Caller.CONTROL_PLANE
        : Caller.SHARED;
  }

  private static String productionFrame() {
    return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
        .walk(frames -> frames
            .filter(frame -> classify(frame.getDeclaringClass()) == Caller.WORKER
                || classify(frame.getDeclaringClass()) == Caller.SHARED)
            .map(frame ->
                frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
            .findFirst()
            .orElse("-"));
  }

  private static void report(String worker, String kind, String sql, String detail) {
    String normalized = sql == null ? "-" : sql.replaceAll("\\s+", " ").trim();
    if (normalized.length() > 500) {
      normalized = normalized.substring(0, 500) + "…";
    }
    String line = String.join(
        "\t",
        kind,
        productionFrame(),
        normalized,
        detail == null ? "-" : detail.replaceAll("\\s+", " "));
    if (!REPORTED.add(line)) {
      return;
    }
    Path file = Path.of("target", "platform-worker-role-drill", worker + ".tsv");
    try {
      Files.createDirectories(file.getParent());
      try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(
          file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
        out.println(line);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("写入演练报告失败: " + file, failure);
    }
  }

  /** 保留 {@code close} 委托，让 Spring 的销毁方法推断仍能关闭底层连接池。 */
  static final class DrillDataSource extends DelegatingDataSource implements AutoCloseable {

    private final String worker;

    DrillDataSource(DataSource target, String worker) {
      super(target);
      this.worker = worker;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return proxy(Connection.class, obtainTargetDataSource().getConnection(), null);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return proxy(
          Connection.class, obtainTargetDataSource().getConnection(username, password), null);
    }

    @Override
    public void close() throws Exception {
      if (obtainTargetDataSource() instanceof AutoCloseable closeable) {
        closeable.close();
      }
    }

    private <T> T proxy(Class<T> type, T target, String sql) {
      InvocationHandler handler = (ignored, method, args) -> invoke(target, method, args, sql);
      return type.cast(Proxy.newProxyInstance(
          PlatformWorkerRoleDrill.class.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private Object invoke(Object target, Method method, Object[] args, String sql)
        throws Throwable {
      String name = method.getName();
      boolean executes = target instanceof Statement && EXECUTE_METHODS.contains(name);
      String statementSql =
          executes && args != null && args.length > 0 && args[0] instanceof String text
              ? text
              : sql;
      boolean restricted = false;
      if (executes) {
        restricted = isWorkerCall();
        applyRole(((Statement) target).getConnection(), restricted, statementSql);
        if (restricted && preparedRole != null) {
          RESTRICTED.incrementAndGet();
        }
      }
      Object result;
      try {
        result = method.invoke(target, args);
      } catch (InvocationTargetException failure) {
        Throwable cause = failure.getCause();
        if (restricted
            && cause instanceof SQLException sqlFailure
            && PERMISSION_DENIED.equals(sqlFailure.getSQLState())) {
          report(worker, "DENIED", statementSql, sqlFailure.getMessage());
        }
        throw cause;
      }
      return wrapResult(method, args, result);
    }

    private Object wrapResult(Method method, Object[] args, Object result) {
      String firstArg =
          args != null && args.length > 0 && args[0] instanceof String text ? text : null;
      return switch (result) {
        case CallableStatement call
        when "prepareCall".equals(method.getName()) ->
          proxy(CallableStatement.class, call, firstArg);
        case PreparedStatement prepared
        when "prepareStatement".equals(method.getName()) ->
          proxy(PreparedStatement.class, prepared, firstArg);
        case Statement statement
        when "createStatement".equals(method.getName()) -> proxy(Statement.class, statement, null);
        case null, default -> result;
      };
    }

    private void applyRole(Connection connection, boolean restricted, String sql)
        throws SQLException {
      String role = preparedRole;
      if (restricted && role == null) {
        report(worker, "BEFORE_GRANT", sql, "授权前执行，未按受限角色校验");
      }
      // SET 不是事务局部的，但会随事务回滚撤销，因此每条语句前都重新设定。
      try (Statement switcher = connection.createStatement()) {
        switcher.execute(restricted && role != null ? "SET ROLE " + role : "RESET ROLE");
      } catch (SQLException failure) {
        if (!IN_FAILED_TRANSACTION.equals(failure.getSQLState())) {
          throw failure;
        }
      }
    }
  }
}
