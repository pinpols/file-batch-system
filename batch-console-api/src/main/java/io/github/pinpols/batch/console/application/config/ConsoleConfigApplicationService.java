package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.console.application.contract.query.ConfigChangeLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.ConfigReleaseQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.SecretVersionQueryRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigReleaseActionRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigReleaseUpsertRequest;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigDependenciesResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigGovernanceItemResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigReleaseDiffResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConsoleConfigChangeLogResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConsoleConfigReleaseResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.request.SecretVersionRotateRequest;
import io.github.pinpols.batch.console.shared.view.ConsoleSecretVersionResponse;
import java.util.List;

public interface ConsoleConfigApplicationService {

  List<ConsoleConfigReleaseResponse> configReleases(ConfigReleaseQueryRequest request);

  List<ConfigGovernanceItemResponse> configGovernanceCatalog();

  Long createConfigRelease(ConfigReleaseUpsertRequest request);

  String rollbackConfigRelease(Long releaseId, ConfigReleaseActionRequest request);

  List<ConsoleSecretVersionResponse> secretVersions(SecretVersionQueryRequest request);

  Long rotateSecretVersion(SecretVersionRotateRequest request);

  List<ConsoleConfigChangeLogResponse> configChangeLogs(ConfigChangeLogQueryRequest request);

  ConsoleConfigReleaseResponse configReleaseDetail(String tenantId, Long releaseId);

  ConsoleSecretVersionResponse secretVersionDetail(String tenantId, Long secretVersionId);

  ConfigReleaseDiffResponse diffConfigReleases(String tenantId, Long releaseIdA, Long releaseIdB);

  /** 返回引用了 channel/template/calendar/window/queue 的 job 和 workflow。 */
  ConfigDependenciesResponse configDependencies(
      String tenantId, String configType, String configCode);
}
