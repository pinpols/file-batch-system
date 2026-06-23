package io.github.pinpols.batch.worker.imports.preprocess;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * P2: Spring 启动期把容器管理的 {@link ObjectMapper} 注入到 {@link ImportPreprocessPipeline} 的静态字段,替换 fallback
 * 实例。这样 preprocess_pipeline JSON 反序列化与项目全局 ObjectMapper 行为完全一致(共用 JavaTime / Mixin / Module 注册)。
 *
 * <p>保留 ImportPreprocessPipeline 的静态工具 API 是为了不打扰大量已有调用方 / 测试。
 */
@Component
@RequiredArgsConstructor
class ImportPreprocessObjectMapperInitializer {

  private final ObjectMapper objectMapper;

  @PostConstruct
  void init() {
    ImportPreprocessPipeline.setObjectMapper(objectMapper);
  }
}
