package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.console.domain.ops.web.request.SecretVersionRotateRequest;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleSecretVersionResponse;
import io.github.pinpols.batch.console.web.query.ConfigChangeLogQueryRequest;
import io.github.pinpols.batch.console.web.query.ConfigReleaseQueryRequest;
import io.github.pinpols.batch.console.web.query.SecretVersionQueryRequest;
import io.github.pinpols.batch.console.web.request.config.ConfigReleaseActionRequest;
import io.github.pinpols.batch.console.web.request.config.ConfigReleaseUpsertRequest;
import io.github.pinpols.batch.console.web.response.config.ConsoleConfigChangeLogResponse;
import io.github.pinpols.batch.console.web.response.config.ConsoleConfigReleaseResponse;
import java.util.List;
import java.util.Map;

public interface ConsoleConfigApplicationService {

  List<ConsoleConfigReleaseResponse> configReleases(ConfigReleaseQueryRequest request);

  Long createConfigRelease(ConfigReleaseUpsertRequest request);

  String publishConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

  String grayConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

  String rollbackConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

  List<ConsoleSecretVersionResponse> secretVersions(SecretVersionQueryRequest request);

  Long rotateSecretVersion(SecretVersionRotateRequest request);

  List<ConsoleConfigChangeLogResponse> configChangeLogs(ConfigChangeLogQueryRequest request);

  ConsoleConfigReleaseResponse configReleaseDetail(String tenantId, Long releaseId);

  ConsoleSecretVersionResponse secretVersionDetail(String tenantId, Long secretVersionId);

  Map<String, Object> diffConfigReleases(String tenantId, Long releaseIdA, Long releaseIdB);

  /** 返回引用了 channel/template/calendar/window/queue 的 job 和 workflow。 */
  Map<String, Object> configDependencies(String tenantId, String configType, String configCode);
}
