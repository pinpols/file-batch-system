package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRecordView;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import io.github.pinpols.batch.worker.core.param.PlatformFileInsertParam;
import io.github.pinpols.batch.worker.core.param.PlatformPipelineInsertParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("平台仓储: 聚焦拆分的构造与事务契约, 查询过滤与文件记录写入")
class PlatformRepositoryTest {

  @Test
  @DisplayName("仓储拆分后各自保留构造函数, 写入方法使用独立事务与读已提交隔离级别")
  void shouldPreserveConstructorsAndTransactionBoundary_whenRepositoriesAreFocused()
      throws NoSuchMethodException {
    assertThat(PlatformFileTemplateRepository.class.getConstructor(PlatformFileRuntimeMapper.class))
        .isNotNull();
    assertThat(PlatformPipelineRunRepository.class.getConstructor(PlatformFileRuntimeMapper.class))
        .isNotNull();
    assertThat(PlatformFileRecordRepository.class.getConstructor(PlatformFileRuntimeMapper.class))
        .isNotNull();
    assertThat(PlatformFileRecordRepository.class
            .getMethod("createFileRecord", FileRecordParam.class)
            .getAnnotation(Transactional.class))
        .isNotNull();
    assertThat(PlatformPipelineRunRepository.class
            .getMethod("startStepRun", Long.class, String.class, String.class, Object.class)
            .getAnnotation(Transactional.class))
        .extracting(Transactional::isolation)
        .isEqualTo(Isolation.READ_COMMITTED);
    assertThat(PlatformPipelineRunRepository.class
            .getMethod("startStepRun", Long.class, String.class, String.class, Object.class)
            .getAnnotation(Transactional.class))
        .extracting(Transactional::propagation)
        .isEqualTo(Propagation.REQUIRES_NEW);
  }

  @Test
  @DisplayName("创建流水线实例时以作业编码写入实例表, 参数中不含流水线编码")
  void shouldUseJobCode_whenCreatingPipelineInstance() {
    PlatformFileRuntimeMapper mapper = mock(PlatformFileRuntimeMapper.class);
    PlatformPipelineRunRepository repository = new PlatformPipelineRunRepository(mapper);

    when(mapper.insertPipelineInstance(any())).thenAnswer(invocation -> {
      PlatformPipelineInsertParam param = invocation.getArgument(0);
      param.setId(5301L);
      return 1;
    });

    Long pipelineInstanceId = repository.createPipelineInstance(new CreatePipelineInstanceParam(
        "tenant-a", "IMPORT", 1, "job-a", "IMPORT", 5201L, 4001L, "VALIDATE", "trace-a"));

    assertThat(pipelineInstanceId).isEqualTo(5301L);
    verify(mapper)
        .insertPipelineInstance(argThat(params -> "tenant-a".equals(params.getTenantId())
            && "job-a".equals(params.getJobCode())
            && "IMPORT".equals(params.getSystemPipelineCode())));
  }

  @Test
  @DisplayName("命中同存储路径的已有记录时返回既有文件标识, 不改变最新标记或重复插入")
  void shouldPreserveDedupPrecheck_whenCreatingFileRecord() {
    PlatformFileRuntimeMapper mapper = mock(PlatformFileRuntimeMapper.class);
    PlatformFileRecordRepository repository = new PlatformFileRecordRepository(mapper);
    when(mapper.selectMaxFileGenerationNo(any(), any())).thenReturn(2);
    when(mapper.selectFileRecordByStoragePath(any(), any(), any()))
        .thenReturn(new PlatformFileRecordView(
            77L, null, null, null, null, null, null, null, null, null, null, null, null, "", null,
            null, null, null, null, null, null, null, null, null, null, null, null, null));

    Long fileId = repository.createFileRecord(FileRecordParam.builder()
        .tenantId("tenant-a")
        .fileCode("file-a")
        .fileCategory("INPUT")
        .fileName("orders.csv")
        .fileFormatType("DELIMITED")
        .storageType("S3")
        .storageBucket("batch")
        .storagePath("incoming/orders.csv")
        .sourceType("UPLOAD")
        .fileStatus(FileStatus.RECEIVED.code())
        .build());

    assertThat(fileId).isEqualTo(77L);
    verify(mapper).lockFileGeneration("tenant-a", "file-a");
    verify(mapper, never()).markHistoricalFileNotLatest(any(), any());
    verify(mapper, never()).insertFileRecord(any());
  }

  @Test
  @DisplayName("新增文件记录时按已有最大代号递增写入代号与版本号")
  void shouldPreserveGenerationParameters_whenCreatingFileRecord() {
    PlatformFileRuntimeMapper mapper = mock(PlatformFileRuntimeMapper.class);
    PlatformFileRecordRepository repository = new PlatformFileRecordRepository(mapper);
    when(mapper.selectMaxFileGenerationNo(any(), any())).thenReturn(2);
    when(mapper.insertFileRecord(any())).thenAnswer(invocation -> {
      PlatformFileInsertParam param = invocation.getArgument(0);
      param.setId(78L);
      return 1;
    });

    Long fileId = repository.createFileRecord(FileRecordParam.builder()
        .tenantId("tenant-a")
        .fileCode("file-a")
        .fileCategory("INPUT")
        .fileName("orders.csv")
        .fileFormatType("DELIMITED")
        .checksumValue("sha256-value")
        .storageType("S3")
        .storagePath("incoming/orders.csv")
        .sourceType("UPLOAD")
        .fileStatus(FileStatus.RECEIVED.code())
        .build());

    assertThat(fileId).isEqualTo(78L);
    verify(mapper).lockFileGeneration("tenant-a", "file-a");
    verify(mapper).markHistoricalFileNotLatest("tenant-a", "file-a");
    verify(mapper)
        .insertFileRecord(argThat(params -> Integer.valueOf(3).equals(params.getFileGenerationNo())
            && "v3".equals(params.getFileVersion())));
  }
}
