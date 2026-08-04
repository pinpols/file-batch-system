package io.github.pinpols.batch.sdk.internal;

import com.fasterxml.jackson.databind.ObjectMapper;

/** SDK 内部统一的 JSON mapper 工厂，保持 SDK 独立发布，不依赖平台 common 模块。 */
public final class SdkJsonMapperFactory {

  private SdkJsonMapperFactory() {}

  public static ObjectMapper create() {
    return new ObjectMapper().findAndRegisterModules();
  }
}
