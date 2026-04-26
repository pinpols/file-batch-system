package com.example.batch.worker.core.support;

import com.example.batch.worker.core.mapper.StepRegistryMapper;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用启动期把本模块注册到 Spring 上下文的 Step bean 集合写入 {@code batch.step_registry}。
 *
 * <p>采用"快照覆盖"策略：先按 module 清空，再一次性 INSERT 当前集合。这样已删除的类在下一次 启动后自动从表里消失；多实例并发启动也只会生成相同内容的覆盖，最终一致。
 *
 * <p>子类只需指定 module 名（{@code IMPORT / EXPORT / DISPATCH}）与 Step bean 类型。console-api 的 Excel 上传校验会按
 * pipeline 的 worker_type 查本表，拦住 {@code impl_code} 指向未注册 Spring bean 的坏配置（以前 seed 里 {@code
 * sftpReceiveStep} 之类的"幽灵 impl_code"只能等运行时抛 STEP_NOT_FOUND 才暴露）。
 */
@Slf4j
public abstract class AbstractStepBeanRegistrar<T> {

  private final ApplicationContext applicationContext;
  private final StepRegistryMapper stepRegistryMapper;
  private final Class<T> stepBeanType;
  private final String module;
  private final Function<T, String> implCodeExtractor;

  protected AbstractStepBeanRegistrar(
      ApplicationContext applicationContext,
      StepRegistryMapper stepRegistryMapper,
      Class<T> stepBeanType,
      String module,
      Function<T, String> implCodeExtractor) {
    this.applicationContext = applicationContext;
    this.stepRegistryMapper = stepRegistryMapper;
    this.stepBeanType = stepBeanType;
    this.module = module;
    this.implCodeExtractor = implCodeExtractor;
  }

  /**
   * REQUIRES_NEW 独立事务：本段登记失败不影响应用正常启动继续处理其他监听器；登记失败时 console-api 的 Excel 校验会退化为不校验 impl_code
   * 白名单（与修复前一致，保持兼容），运维收到 ERROR 日志即可定位。
   */
  @EventListener(ApplicationReadyEvent.class)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void registerStepBeansOnStartup() {
    try {
      Map<String, T> beans = applicationContext.getBeansOfType(stepBeanType);
      stepRegistryMapper.deleteByModule(module);
      if (beans.isEmpty()) {
        log.info("step registry snapshot refreshed: module={}, count=0 (未发现 bean)", module);
        return;
      }
      // 关键：用业务侧的 step.implCode() 做登记键而非 Spring bean name。运行时
      // DefaultImportStageExecutor / DefaultDispatchStageExecutor 用的就是 step.implCode()
      // 做 Map key（默认返回 "MODULE_STAGENAME" 比如 DISPATCH_PREPARE），而不是 Spring
      // camelCase 的 bean name（prepareDispatchStep）。登记必须对齐，否则 Excel 校验 /
      // 下拉的 impl_code 跟运行时脱节。
      java.util.LinkedHashSet<String> registeredCodes = new java.util.LinkedHashSet<>();
      beans
          .values()
          .forEach(
              bean -> {
                String code = implCodeExtractor.apply(bean);
                if (code == null || code.isBlank()) {
                  return;
                }
                // 同 impl_code 可能有多个 bean 实现（预留扩展），按第一个登记；同一 bean
                // 重复登记由 UK(module, impl_code) 拦截，这里手动去重避免 SQL 异常
                if (registeredCodes.add(code)) {
                  stepRegistryMapper.insertEntry(module, code, bean.getClass().getName());
                }
              });
      log.info(
          "step registry snapshot refreshed: module={}, count={}, implCodes={}",
          module,
          registeredCodes.size(),
          registeredCodes);
    } catch (Exception ex) {
      log.error("step registry snapshot failed: module={}, err={}", module, ex.getMessage(), ex);
    }
  }
}
