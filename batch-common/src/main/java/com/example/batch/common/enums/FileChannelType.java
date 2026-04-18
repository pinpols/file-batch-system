package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileChannelType implements DictEnum {
  SFTP("SFTP", "SFTP"),
  API("API", "API"),
  API_PUSH("API_PUSH", "API 推送"),
  EMAIL("EMAIL", "邮件"),
  NAS("NAS", "NAS"),
  OSS("OSS", "对象存储"),
  LOCAL("LOCAL", "本地");

  private final String code;
  private final String label;
}
