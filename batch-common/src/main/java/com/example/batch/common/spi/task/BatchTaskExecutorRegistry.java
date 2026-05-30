package com.example.batch.common.spi.task;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link BatchTaskExecutor} 的统一注册表 — 启动期收集所有实现,运行期按 {@link BatchTaskExecutor#taskType()} O(1) 路由。
 *
 * <p>双路注册(Spring 优先,ServiceLoader 兜底):
 *
 * <ol>
 *   <li>Spring 容器内所有 {@link BatchTaskExecutor} bean(自动注入 List)
 *   <li>JDK {@link ServiceLoader} 扫 classpath {@code META-INF/services}
 * </ol>
 *
 * <p>同一 {@code taskType} 重复注册启动期 fail-fast,清晰报错(防 Spring + ServiceLoader 双重注入)。
 *
 * <p>P0 Phase 5:支持 {@link BatchWorkerSpiProperties#getEnabledTaskTypes()} 白名单过滤,实现 "同一 worker
 * 进程注册多个 taskType"的能力(空集 = 不过滤,向后兼容)。
 *
 * <p>设计依据:{@code docs/design/task-spi-design.md} §3.4 + §Phase 5。
 */
@Slf4j
@Component
public class BatchTaskExecutorRegistry {

  private final Map<String, BatchTaskExecutor> byType;
  private final Set<String> enabledFilter;

  /** Test-friendly 构造器:不过滤,全注册。Spring 路径请用主构造器。 */
  public BatchTaskExecutorRegistry(List<BatchTaskExecutor> springBeans) {
    this(springBeans, Set.of());
  }

  public BatchTaskExecutorRegistry(
      List<BatchTaskExecutor> springBeans, ObjectProvider<BatchWorkerSpiProperties> propsProvider) {
    this(springBeans, resolveFilter(propsProvider));
  }

  private static Set<String> resolveFilter(ObjectProvider<BatchWorkerSpiProperties> propsProvider) {
    BatchWorkerSpiProperties props = propsProvider.getIfAvailable();
    return props == null ? Set.of() : Set.copyOf(props.getEnabledTaskTypes());
  }

  private BatchTaskExecutorRegistry(List<BatchTaskExecutor> springBeans, Set<String> filter) {
    this.enabledFilter = filter;

    Map<String, BatchTaskExecutor> merged = new LinkedHashMap<>();
    Set<String> filteredOut = new java.util.LinkedHashSet<>();

    // 1) Spring 容器内的所有 BatchTaskExecutor bean
    for (BatchTaskExecutor exec : springBeans) {
      registerWithFilter(merged, filteredOut, exec, "spring");
    }

    // 2) ServiceLoader:META-INF/services 声明的实现
    for (BatchTaskExecutor exec : ServiceLoader.load(BatchTaskExecutor.class)) {
      registerWithFilter(merged, filteredOut, exec, "service-loader");
    }

    this.byType = Map.copyOf(merged);

    if (byType.isEmpty()) {
      log.warn(
          "BatchTaskExecutorRegistry 启动:无任何 BatchTaskExecutor 注册(Pipeline 任务仍走 @Primary 路径,但原子任务"
              + " SPI 未启用)");
    } else {
      log.info(
          "BatchTaskExecutorRegistry 启动:已注册 {} 个 taskType: {}", byType.size(), byType.keySet());
    }
    if (!filteredOut.isEmpty()) {
      log.info(
          "BatchTaskExecutorRegistry: 过滤掉 {} 个未启用 taskType: {} (启用白名单: {})",
          filteredOut.size(),
          filteredOut,
          enabledFilter);
    }
  }

  private void registerWithFilter(
      Map<String, BatchTaskExecutor> merged,
      Set<String> filteredOut,
      BatchTaskExecutor exec,
      String source) {
    String type = exec.taskType();
    if (type != null
        && !type.isBlank()
        && !enabledFilter.isEmpty()
        && !enabledFilter.contains(type)) {
      filteredOut.add(type);
      return;
    }
    register(merged, exec, source);
  }

  private static void register(
      Map<String, BatchTaskExecutor> merged, BatchTaskExecutor exec, String source) {
    String type = exec.taskType();
    if (type == null || type.isBlank()) {
      throw new IllegalStateException(
          "BatchTaskExecutor "
              + exec.getClass().getName()
              + " returned null/blank taskType() (source="
              + source
              + ")");
    }
    BatchTaskExecutor prev = merged.get(type);
    if (prev != null) {
      if (prev == exec) {
        // 同一实例(同时被 Spring 收 + ServiceLoader 扫到),幂等,不重复登记
        return;
      }
      throw new IllegalStateException(
          "BatchTaskExecutor duplicate taskType="
              + type
              + ": already registered="
              + prev.getClass().getName()
              + ", duplicate from "
              + source
              + "="
              + exec.getClass().getName());
    }
    merged.put(type, exec);
  }

  /** 按 taskType 查找,无注册返回 null(调用方负责降级 / 抛友好错)。 */
  public BatchTaskExecutor find(String taskType) {
    if (taskType == null) {
      return null;
    }
    return byType.get(taskType);
  }

  /** 当前所有已注册的 taskType(给 console-api 拉下拉框用)。 */
  public Set<String> registeredTypes() {
    return byType.keySet();
  }

  /** 用于诊断:列所有注册项(taskType → 实现类全名)。 */
  public Map<String, String> dumpRegistry() {
    Map<String, String> dump = new HashMap<>();
    byType.forEach((k, v) -> dump.put(k, v.getClass().getName()));
    return Map.copyOf(dump);
  }
}
