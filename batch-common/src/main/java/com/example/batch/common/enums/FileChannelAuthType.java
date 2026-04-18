package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileChannelAuthType implements DictEnum {
  NONE("NONE", "无"),
  PASSWORD("PASSWORD", "用户名密码"),
  KEY_PAIR("KEY_PAIR", "密钥对"),
  TOKEN("TOKEN", "Token"),
  OAUTH2("OAUTH2", "OAuth2"),
  CUSTOM("CUSTOM", "自定义");

  private final String code;
  private final String label;
}
