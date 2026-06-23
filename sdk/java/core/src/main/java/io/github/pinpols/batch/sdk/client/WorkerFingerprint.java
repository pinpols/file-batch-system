package io.github.pinpols.batch.sdk.client;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * SDK-P5-3 运行指纹采集 — register 时上报到平台 {@code worker_registry},便于运维定位具体进程实例。
 *
 * <p>字段来源:
 *
 * <ul>
 *   <li>{@code hostName} / {@code hostIp}:{@link InetAddress#getLocalHost()};解析失败回退 {@code
 *       HOSTNAME} 环境变量 / null(平台列可空)。
 *   <li>{@code processId}:{@link ProcessHandle#current()} 的 pid。
 *   <li>{@code buildId}:租户经 {@link BatchPlatformClientConfig#getBuildId()} 注入(CI 产出),SDK 不臆造。
 *   <li>{@code sdkVersion}:本 SDK jar manifest 的 {@code Implementation-Version}(maven-jar-plugin
 *       默认写入); IDE / 测试无 jar 时为 null。
 * </ul>
 *
 * <p>全部字段「尽力而为」:任一采集失败均降级为 null,绝不让指纹采集阻断 worker 启动。
 */
public final class WorkerFingerprint {

  private WorkerFingerprint() {}

  public static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      String env = System.getenv("HOSTNAME");
      return env == null || env.isBlank() ? null : env;
    }
  }

  public static String hostIp() {
    try {
      return InetAddress.getLocalHost().getHostAddress();
    } catch (UnknownHostException e) {
      return null;
    }
  }

  public static String processId() {
    return Long.toString(ProcessHandle.current().pid());
  }

  /** 本 SDK 库版本 — 从 jar manifest Implementation-Version 读;非 jar 运行(IDE / 测试)返回 null。 */
  public static String sdkVersion() {
    Package pkg = WorkerFingerprint.class.getPackage();
    return pkg == null ? null : pkg.getImplementationVersion();
  }
}
