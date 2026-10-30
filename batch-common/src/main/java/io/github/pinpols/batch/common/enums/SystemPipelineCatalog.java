package io.github.pinpols.batch.common.enums;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.List;
import java.util.Optional;

/** Job 类型到平台内置文件 Pipeline 的唯一映射来源。 */
public final class SystemPipelineCatalog {

  private static final SystemPipelineDefinition IMPORT = new SystemPipelineDefinition(
      "IMPORT", 1, List.of("RECEIVE", "PREPROCESS", "PARSE", "VALIDATE", "LOAD", "FEEDBACK"));
  private static final SystemPipelineDefinition EXPORT = new SystemPipelineDefinition(
      "EXPORT", 1, List.of("PREPARE", "GENERATE", "STORE", "REGISTER", "COMPLETE"));
  private static final SystemPipelineDefinition PROCESS = new SystemPipelineDefinition(
      "PROCESS", 1, List.of("PREPARE", "COMPUTE", "VALIDATE", "COMMIT", "FEEDBACK"));
  private static final SystemPipelineDefinition DISPATCH = new SystemPipelineDefinition(
      "DISPATCH", 1, List.of("PREPARE", "DISPATCH", "ACK", "RETRY", "COMPENSATE", "COMPLETE"));

  private SystemPipelineCatalog() {}

  /** 非文件作业（GENERAL、ATOMIC）没有文件 Pipeline。未知类型同样不做猜测映射。 */
  public static Optional<SystemPipelineDefinition> forJobType(String jobType) {
    JobType resolvedJobType = DictEnum.fromCode(JobType.class, jobType);
    return forJobType(resolvedJobType);
  }

  public static Optional<SystemPipelineDefinition> forJobType(JobType jobType) {
    if (EmptyChecks.isNull(jobType)) {
      return Optional.empty();
    }
    return switch (jobType) {
      case IMPORT, BUNDLE_IMPORT -> Optional.of(IMPORT);
      case EXPORT, BUNDLE_EXPORT -> Optional.of(EXPORT);
      case PROCESS -> Optional.of(PROCESS);
      case DISPATCH, BUNDLE_DISPATCH -> Optional.of(DISPATCH);
      case GENERAL, ATOMIC -> Optional.empty();
    };
  }

  public static List<SystemPipelineDefinition> definitions() {
    return List.of(IMPORT, EXPORT, PROCESS, DISPATCH);
  }
}
