package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews.FileRecordRow;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("关联文件引用的租户边界")
class RelatedFileReferenceValidatorTest {

  @Mock
  private FileGovernanceRepository repository;

  @InjectMocks
  private RelatedFileReferenceValidator validator;

  @Test
  @DisplayName("缺少文件 ID 时不访问仓库")
  void shouldIgnoreAbsentFileId() {
    assertThatCode(() -> validator.validate("tenant-a", null)).doesNotThrowAnyException();
    verify(repository, never()).loadFileRecord("tenant-a", null);
  }

  @Test
  @DisplayName("当前租户查不到文件时统一返回资源不存在")
  void shouldRejectMissingFileInTenant() {
    when(repository.loadFileRecord("tenant-a", 7L)).thenReturn(null);

    assertThatThrownBy(() -> validator.validate("tenant-a", 7L))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.NOT_FOUND);
          assertThat(failure.getMessageKey()).isEqualTo("error.file.record_not_found");
        });
    verify(repository).loadFileRecord("tenant-a", 7L);
  }

  @Test
  @DisplayName("当前租户查到文件时允许引用")
  void shouldAcceptExistingFileInTenant() {
    when(repository.loadFileRecord("tenant-a", 7L))
        .thenReturn(new FileRecordRow(7L, null, null, null));

    assertThatCode(() -> validator.validate("tenant-a", 7L)).doesNotThrowAnyException();
  }
}
