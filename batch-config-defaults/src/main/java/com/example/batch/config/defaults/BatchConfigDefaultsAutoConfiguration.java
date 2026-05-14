package com.example.batch.config.defaults;

import org.springframework.context.annotation.Configuration;

/**
 * Marker AutoConfiguration —— 仅为让 batch-config-defaults 模块在依赖图里有一个可识别的锚点， 不注册任何 bean。
 *
 * <p>真正的"共享基线"载体是 classpath 下的 {@code batch-defaults.yml}，由各服务模块在 {@code application.yml} 中通过
 * {@code spring.config.import: "classpath:batch-defaults.yml"} 引入；位置无关，只要本模块在 classpath 上即可。
 *
 * <p>本类不应被 {@code spring.factories} 或 {@code AutoConfiguration.imports} 自动加载——
 * 加载它毫无副作用，但也毫无意义。它存在的唯一目的是：让 IDE/审计工具能从 Java 代码定位到 这个模块（光看一个 yml 文件不容易发现它的存在）。详见 ADR-029。
 */
@Configuration(proxyBeanMethods = false)
public class BatchConfigDefaultsAutoConfiguration {}
