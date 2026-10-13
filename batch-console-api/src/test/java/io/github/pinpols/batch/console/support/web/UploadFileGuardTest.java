package io.github.pinpols.batch.console.support.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

@DisplayName("上传文件守卫：文件名校验")
class UploadFileGuardTest {

  @Test
  @DisplayName("原始文件名为空时返回业务校验异常")
  void shouldRejectMissingFilenameWithoutNullPointerException() {
    MultipartFile file = mock(MultipartFile.class);
    when(file.isEmpty()).thenReturn(false);
    when(file.getOriginalFilename()).thenReturn(null);

    assertThatThrownBy(() -> UploadFileGuard.requireCsv(file)).isInstanceOf(BizException.class);
  }
}
