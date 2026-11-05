package io.github.pinpols.batch.testing;

import io.github.pinpols.batch.common.utils.EmptyChecks;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 平台库受限角色演练（ARCH-20261103-01）：内建 Worker 或 Console/Trigger/Orchestrator 控制面。
 *
 * <p>以 {@code -Dbatch.drill.platform-worker-role=<worker>} 或 {@code
 * -Dbatch.drill.platform-control-plane-role=true} 启用，只作用于 {@link AbstractIntegrationTest} 的 Spring 上下文。
 * 平台库连接仍以 owner 登录：Spring Flyway 迁移完成后，用已提交的授权脚本给演练角色授权；此后生产代码发出的语句按调用栈
 * 归属先 {@code SET ROLE} 到对应模块的角色执行，测试 fixture、断言和 Flyway 保持 owner。这样触发器、RLS 与行锁都按
 * 受限角色校验，而 fixture 写入不会混入结论。权限拒绝（42501）与授权前执行的生产语句写入
 * {@code target/platform-worker-role-drill/<worker 或 control-plane>.tsv}。
 */
public final class PlatformWorkerRoleDrill {

  /** 启用演练并指定 Worker（import/export/process/dispatch/atomic）的系统属性。 */
  public static final String WORKER_PROPERTY = "batch.drill.platform-worker-role";

  /** 置为 {@code true} 时演练 Console/Trigger/Orchestrator 运行时角色。 */
  public static final String CONTROL_PLANE_PROPERTY = "batch.drill.platform-control-plane-role";

  private static final String WORKER_SCRIPT = "scripts/db/platform-worker-roles.sql";
  private static final String CONTROL_PLANE_SCRIPT = "scripts/db/platform-control-plane-roles.sql";
  // Console 跨租户转发缓存失效队列依赖该脚本创建的角色专属 RLS 策略。
  private static final String CONFIG_CACHE_SCRIPT = "scripts/db/config-cache-invalidation-role.sql";
  private static final String CONTROL_PLANE_REPORT = "control-plane";
  private static final String PERMISSION_DENIED = "42501";
  private static final String IN_FAILED_TRANSACTION = "25P02";
  private static final Set<String> EXECUTE_METHODS = Set.of(
      "execute",
      "executeQuery",
      "executeUpdate",
      "executeLargeUpdate",
      "executeBatch",
      "executeLargeBatch");
  // Console 开启只读副本时的叶子数据源；路由数据源不包装，避免同一语句切换两次。
  private static final Set<String> CONSOLE_DATA_SOURCES =
      Set.of("consolePrimaryDataSource", "consoleReplicaDataSource");

  private static final String WORKER_PACKAGE = "io.github.pinpols.batch.worker.";
  private static final Map<String, String> CONTROL_PLANE_PACKAGES = Map.of(
      "io.github.pinpols.batch.orchestrator.", "orchestrator",
      "io.github.pinpols.batch.trigger.", "trigger",
      "io.github.pinpols.batch.console.", "console");
  private static final Map<String, String> CONTROL_PLANE_APPLICATIONS = Map.of(
      "io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication", "orchestrator",
      "io.github.pinpols.batch.trigger.BatchTriggerApplication", "trigger",
      "io.github.pinpols.batch.console.BatchConsoleApiApplication", "console");
  // E2E 把多个模块装进同一上下文并共用平台库数据源，只有调用栈能区分语句归属。
  private static final List<String> PRESENT_CONTROL_PLANE =
      CONTROL_PLANE_APPLICATIONS.entrySet().stream()
          .filter(application -> isPresent(application.getKey()))
          .map(Map.Entry::getValue)
          .sorted()
          .toList();
  private static final boolean SHARED_CONTROL_PLANE_CONTEXT =
      EmptyChecks.isNotEmpty(PRESENT_CONTROL_PLANE);

  private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
  private static final Map<String, AtomicLong> RESTRICTED = new ConcurrentHashMap<>();
  private static final AtomicLong UNATTRIBUTED = new AtomicLong();
  private static volatile Map<String, String> preparedRoles;

  private PlatformWorkerRoleDrill() {}

  private static boolean isPresent(String className) {
    try {
      Class.forName(className, false, PlatformWorkerRoleDrill.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException absent) {
      return false;
    }
  }

  /**
   * 演练对象：Worker 模式下是单个 Worker，控制面模式下是三个控制面模块。
   *
   * @param report 报告文件名
   * @param worker Worker 名；控制面模式为 null
   */
  record Target(String report, String worker) {

    boolean controlPlane() {
      return EmptyChecks.isNull(worker);
    }
  }

  static Optional<Target> configuredTarget() {
    String worker = System.getProperty(WORKER_PROPERTY);
    if (EmptyChecks.isNotBlank(worker)) {
      String trimmedWorker = worker.trim();
      return Optional.of(new Target(trimmedWorker, trimmedWorker));
    }
    boolean controlPlane = Boolean.parseBoolean(System.getProperty(CONTROL_PLANE_PROPERTY));
    return controlPlane ? Optional.of(new Target(CONTROL_PLANE_REPORT, null)) : Optional.empty();
  }

  static BeanPostProcessor postProcessor(Target target) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        // E2E 的 <worker>PlatformDataSource 直接返回共享的 dataSource，已包装的不再重复包装。
        if (bean instanceof DataSource dataSource
            && !(bean instanceof DrillDataSource)
            && isPlatformDataSource(beanName)) {
          return new DrillDataSource(dataSource, target);
        }
        // Spring Boot 的 Flyway 初始化器在 afterPropertiesSet 中完成迁移；之后表结构齐全才能授权。
        if (bean.getClass().getName().endsWith("FlywayMigrationInitializer")) {
          prepareRoles(target);
        }
        // Trigger 测试 profile 的 spring.quartz.jdbc.schema 会在迁移后删除并重建 Quartz 表，授权随之丢失；重建后重新授权。
        if (bean.getClass().getName().endsWith("QuartzDataSourceScriptDatabaseInitializer")
            && isPrepared()) {
          grant(target, preparedRoles);
        }
        return bean;
      }
    };
  }

  static boolean isPrepared() {
    return EmptyChecks.isNotNull(preparedRoles);
  }

  /**
   * Worker 平台库数据源名为 {@code <worker>PlatformDataSource}；Dispatch/Atomic 与控制面使用 Boot 默认的
   * {@code dataSource}，Console 开启只读副本时另有主库与副本两个叶子数据源。
   */
  private static boolean isPlatformDataSource(String beanName) {
    return "dataSource".equals(beanName)
        || beanName.endsWith("PlatformDataSource")
        || CONSOLE_DATA_SOURCES.contains(beanName);
  }

  private static synchronized void prepareRoles(Target target) {
    if (EmptyChecks.isNotNull(preparedRoles)) {
      return;
    }
    Map<String, String> roles = new LinkedHashMap<>();
    if (target.controlPlane()) {
      for (String module : List.of("console", "trigger", "orchestrator")) {
        roles.put(module, "drill_cp_" + module);
      }
    } else {
      roles.put(target.worker(), "drill_worker_" + target.worker());
    }
    grant(target, roles);
    roles.keySet().forEach(module -> RESTRICTED.put(module, new AtomicLong()));
    preparedRoles = Map.copyOf(roles);
    // 汇总行证明演练确实以受限角色执行过语句，避免全部落到 owner 时报告为空却被误读为通过。
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> report(target.report(), "SUMMARY", null, summary(roles.keySet()))));
  }

  /** 创建演练角色并执行授权脚本；脚本先撤销再授予，可重复执行。 */
  private static synchronized void grant(Target target, Map<String, String> roles) {
    var postgres = AbstractIntegrationTest.PLATFORM_POSTGRES;
    try {
      for (String role : roles.values()) {
        psql(
            postgres,
            List.of(
                "-c",
                "DO $$ BEGIN CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS;"
                    + " EXCEPTION WHEN duplicate_object THEN NULL; END $$"));
      }
      List<String> roleArguments = new ArrayList<>();
      roles.forEach(
          (module, role) -> roleArguments.addAll(List.of("-v", module + "_role=" + role)));
      runScript(
          postgres, target.controlPlane() ? CONTROL_PLANE_SCRIPT : WORKER_SCRIPT, roleArguments);
      if (target.controlPlane()) {
        runScript(
            postgres, CONFIG_CACHE_SCRIPT, List.of("-v", "console_role=" + roles.get("console")));
      }
    } catch (IOException failure) {
      throw new IllegalStateException("受限角色演练授权失败", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("受限角色演练授权被中断", failure);
    }
  }

  private static String summary(Set<String> modules) {
    StringBuilder summary = new StringBuilder();
    for (String module : modules) {
      String count = String.valueOf(RESTRICTED.get(module).get());
      summary
          .append(modules.size() == 1 ? "restricted" : "restricted_" + module)
          .append('=')
          .append(count)
          .append(' ');
    }
    return summary.append("unattributed=").append(UNATTRIBUTED.get()).toString();
  }

  private static void runScript(PostgreSQLContainer postgres, String script, List<String> variables)
      throws IOException, InterruptedException {
    String inContainer = "/tmp/" + Path.of(script).getFileName();
    postgres.copyFileToContainer(
        MountableFile.forHostPath(repositoryRoot().resolve(script).toString()), inContainer);
    List<String> arguments = new ArrayList<>(List.of("-v", "action=grant"));
    arguments.addAll(variables);
    arguments.addAll(List.of("-f", inContainer));
    psql(postgres, arguments);
  }

  private static void psql(PostgreSQLContainer postgres, List<String> arguments)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of(
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        postgres.getUsername(),
        "-d",
        postgres.getDatabaseName()));
    command.addAll(arguments);
    ExecResult result = postgres.execInContainer(command.toArray(String[]::new));
    if (result.getExitCode() != 0) {
      throw new IllegalStateException("psql 执行失败: " + result.getStderr());
    }
  }

  private static Path repositoryRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (EmptyChecks.isNotNull(current) && !Files.exists(current.resolve(WORKER_SCRIPT))) {
      current = current.getParent();
    }
    if (EmptyChecks.isNull(current)) {
      throw new IllegalStateException("找不到 " + WORKER_SCRIPT);
    }
    return current;
  }

  /**
   * 按调用栈判定语句归属的受限模块，返回 null 表示保持 owner。Flyway（含其回调）与测试代码保持 owner；否则取首个可归属的
   * 项目帧：Worker 模式只限制 Worker 包，控制面模式按 Orchestrator/Trigger/Console 包分别限制。只有 batch-common 等共享帧
   * 时，独立应用按该应用处理，多模块共享上下文无法归属则保持 owner 并计数。
   */
  private static String restrictedModule(Target target) {
    List<Caller> callers = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
        .walk(frames -> frames
            .map(StackWalker.StackFrame::getDeclaringClass)
            .map(PlatformWorkerRoleDrill::classify)
            .filter(caller -> caller.kind() != Kind.UNKNOWN)
            .toList());
    if (callers.stream().anyMatch(caller -> caller.kind() == Kind.MIGRATION)) {
      return null;
    }
    Caller first = callers.stream()
        .filter(caller -> caller.kind() != Kind.SHARED)
        .findFirst()
        .orElse(Caller.SHARED_CALLER);
    if (first.kind() == Kind.SHARED) {
      return sharedOwner(target);
    }
    if (target.controlPlane()) {
      return first.kind() == Kind.CONTROL_PLANE ? first.module() : null;
    }
    return first.kind() == Kind.WORKER ? target.worker() : null;
  }

  private static String sharedOwner(Target target) {
    if (target.controlPlane() && PRESENT_CONTROL_PLANE.size() == 1) {
      return PRESENT_CONTROL_PLANE.getFirst();
    }
    if (!target.controlPlane() && !SHARED_CONTROL_PLANE_CONTEXT) {
      return target.worker();
    }
    UNATTRIBUTED.incrementAndGet();
    return null;
  }

  private enum Kind {
    MIGRATION,
    FIXTURE,
    CONTROL_PLANE,
    WORKER,
    SHARED,
    UNKNOWN
  }

  /** 调用帧归属；module 只对控制面帧有值。 */
  private record Caller(Kind kind, String module) {

    static final Caller SHARED_CALLER = new Caller(Kind.SHARED, null);

    static Caller of(Kind kind) {
      return new Caller(kind, null);
    }
  }

  private static Caller classify(Class<?> type) {
    String name = type.getName();
    if (name.startsWith(PlatformWorkerRoleDrill.class.getName())) {
      return Caller.of(Kind.UNKNOWN);
    }
    if (name.startsWith("org.flywaydb.")) {
      return Caller.of(Kind.MIGRATION);
    }
    if (name.startsWith("org.springframework.test.")) {
      return Caller.of(Kind.FIXTURE);
    }
    if (!name.startsWith("io.github.pinpols.batch.")) {
      return Caller.of(Kind.UNKNOWN);
    }
    CodeSource source = type.getProtectionDomain().getCodeSource();
    String location = EmptyChecks.isNull(source) ? "" : source.getLocation().toString();
    if (location.contains("/test-classes/") || location.contains("batch-test-support")) {
      return Caller.of(Kind.FIXTURE);
    }
    if (name.startsWith(WORKER_PACKAGE)) {
      return Caller.of(Kind.WORKER);
    }
    return CONTROL_PLANE_PACKAGES.entrySet().stream()
        .filter(controlPlane -> name.startsWith(controlPlane.getKey()))
        .map(controlPlane -> new Caller(Kind.CONTROL_PLANE, controlPlane.getValue()))
        .findFirst()
        .orElse(Caller.SHARED_CALLER);
  }

  private static String productionFrame() {
    return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
        .walk(frames -> frames
            .filter(frame -> {
              Kind kind = classify(frame.getDeclaringClass()).kind();
              return kind == Kind.WORKER || kind == Kind.CONTROL_PLANE || kind == Kind.SHARED;
            })
            .map(frame ->
                frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
            .findFirst()
            .orElse("-"));
  }

  private static void report(String file, String kind, String sql, String detail) {
    String normalized =
        EmptyChecks.isNull(sql) ? "-" : sql.replaceAll("\\s+", " ").trim();
    if (normalized.length() > 500) {
      normalized = normalized.substring(0, 500) + "…";
    }
    String line = String.join(
        "\t",
        kind,
        productionFrame(),
        normalized,
        EmptyChecks.isNull(detail) ? "-" : detail.replaceAll("\\s+", " "));
    if (!REPORTED.add(line)) {
      return;
    }
    Path path = Path.of("target", "platform-worker-role-drill", file + ".tsv");
    try {
      Files.createDirectories(path.getParent());
      try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(
          path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
        out.println(line);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("写入演练报告失败: " + path, failure);
    }
  }

  /** 保留 {@code close} 委托，让 Spring 的销毁方法推断仍能关闭底层连接池。 */
  static final class DrillDataSource extends DelegatingDataSource implements AutoCloseable {

    private final Target target;

    DrillDataSource(DataSource delegate, Target target) {
      super(delegate);
      this.target = target;
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

    private <T> T proxy(Class<T> type, T delegate, String sql) {
      InvocationHandler handler = (ignored, method, args) -> invoke(delegate, method, args, sql);
      ClassLoader loader = PlatformWorkerRoleDrill.class.getClassLoader();
      Object proxy = Proxy.newProxyInstance(loader, new Class<?>[] {type}, handler);
      return type.cast(proxy);
    }

    private Object invoke(Object delegate, Method method, Object[] args, String sql)
        throws Throwable {
      String name = method.getName();
      boolean executes = delegate instanceof Statement && EXECUTE_METHODS.contains(name);
      String firstArg = firstStringArg(args);
      String statementSql = executes && EmptyChecks.isNotNull(firstArg) ? firstArg : sql;
      String module = null;
      if (executes) {
        module = restrictedModule(target);
        applyRole(((Statement) delegate).getConnection(), module, statementSql);
        Map<String, String> roles = preparedRoles;
        if (EmptyChecks.isNotNull(module) && EmptyChecks.isNotNull(roles)) {
          RESTRICTED.get(module).incrementAndGet();
        }
      }
      Object result;
      try {
        result = method.invoke(delegate, args);
      } catch (InvocationTargetException failure) {
        Throwable cause = failure.getCause();
        if (EmptyChecks.isNotNull(module)
            && cause instanceof SQLException sqlFailure
            && PERMISSION_DENIED.equals(sqlFailure.getSQLState())) {
          report(target.report(), denialKind(module), statementSql, sqlFailure.getMessage());
        }
        throw cause;
      }
      return wrapResult(method, args, result);
    }

    private String denialKind(String module) {
      return target.controlPlane() ? "DENIED:" + module : "DENIED";
    }

    private Object wrapResult(Method method, Object[] args, Object result) {
      String firstArg = firstStringArg(args);
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

    /** Statement 的 execute(String…) 与 Connection 的 prepare*(String…) 首个实参是 SQL。 */
    private static String firstStringArg(Object[] args) {
      return EmptyChecks.isNotEmpty(args) && args[0] instanceof String text ? text : null;
    }

    private void applyRole(Connection connection, String module, String sql) throws SQLException {
      Map<String, String> roles = preparedRoles;
      String role =
          EmptyChecks.isNull(module) || EmptyChecks.isNull(roles) ? null : roles.get(module);
      if (EmptyChecks.isNotNull(module) && EmptyChecks.isNull(role)) {
        report(target.report(), "BEFORE_GRANT", sql, "授权前执行，未按受限角色校验");
      }
      // SET 不是事务局部的，但会随事务回滚撤销，因此每条语句前都重新设定。
      try (Statement switcher = connection.createStatement()) {
        switcher.execute(EmptyChecks.isNotNull(role) ? "SET ROLE " + role : "RESET ROLE");
      } catch (SQLException failure) {
        if (!IN_FAILED_TRANSACTION.equals(failure.getSQLState())) {
          throw failure;
        }
      }
    }
  }
}
