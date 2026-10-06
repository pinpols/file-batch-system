package io.github.pinpols.batch.common.config;

import io.github.pinpols.batch.common.utils.Texts;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * {@code spring.application.name} 的统一读取入口。
 *
 * <p>把原先散落在各模块的同一 key 读取（{@code @Value("${spring.application.name:...}")} 与
 * {@code environment.getProperty("spring.application.name", ...)}）收敛到一处，避免 key 字符串与各自默认值
 * 在多处漂移。
 *
 * <p>默认值仍由调用方传入：本类只负责读取 key，不复制 Spring 的默认值，避免把 {@code spring.*} 复制成
 * 第二份事实来源（配置 Key 治理文档 §5.3 / §7）。
 */
@Component
public class ApplicationNameProvider {

  /** 统一维护的配置 key。 */
  public static final String APPLICATION_NAME_KEY = "spring.application.name";

  private final Environment environment;

  public ApplicationNameProvider(Environment environment) {
    this.environment = environment;
  }

  /**
   * 读取 {@code spring.application.name}。
   *
   * @param fallback 未配置或值为空白时的回退值，由调用方按各模块语义提供
   * @return 实际生效的应用名，或 {@code fallback}
   */
  public String name(String fallback) {
    return resolve(environment, fallback);
  }

  /**
   * 静态变体：供无法注入本 bean 的早期装配场景使用（例如 {@code BeanPostProcessor} 工厂刻意只注入 {@link
   * Environment}，以免过早实例化配置属性 bean）。
   *
   * @param environment Spring 环境
   * @param fallback 未配置或值为空白时的回退值
   * @return 实际生效的应用名，或 {@code fallback}
   */
  public static String resolve(Environment environment, String fallback) {
    String value = environment.getProperty(APPLICATION_NAME_KEY);
    return Texts.hasText(value) ? value : fallback;
  }
}
