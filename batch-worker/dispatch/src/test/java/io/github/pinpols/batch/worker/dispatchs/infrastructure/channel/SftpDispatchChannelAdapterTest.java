package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("SFTP 分发适配器:发布失败时保留远端目标并明确失败")
class SftpDispatchChannelAdapterTest {

  @Test
  @DisplayName("断连线程卡住时，已取消的排队断连任务立即从队列移除")
  void shouldRemoveCancelledDisconnects_whenWorkersAreBlocked() throws Exception {
    SftpDispatchChannelAdapter adapter = new SftpDispatchChannelAdapter(
        mock(DispatchFileContentResolver.class),
        new BatchSecurityProperties(),
        mock(Environment.class));
    ScheduledThreadPoolExecutor executor =
        (ScheduledThreadPoolExecutor) ReflectionTestUtils.getField(adapter, "disconnectExecutor");
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    List<Future<?>> waiting = new ArrayList<>();
    try {
      for (int i = 0; i < 2; i++) {
        executor.submit(() -> {
          started.countDown();
          boolean released = false;
          while (!released) {
            try {
              release.await();
              released = true;
            } catch (InterruptedException ignored) {
              // 模拟底层断连不响应中断，直到远端操作自行返回。
            }
          }
        });
      }
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      for (int i = 0; i < 20; i++) {
        waiting.add(executor.submit(() -> {}));
      }
      waiting.forEach(future -> future.cancel(true));

      assertThat(executor.getQueue()).isEmpty();
    } finally {
      release.countDown();
      adapter.shutdownDisconnectExecutor();
    }
  }

  @Test
  @DisplayName("服务端不支持替换时保留已有目标且不降级为删除后重命名")
  void publishRemoteFile_preservesTarget_whenServerCannotReplace() throws Exception {
    ChannelSftp sftp = mock(ChannelSftp.class);
    String tempRemotePath = "/upload/file.dat.tmp-1";
    String remotePath = "/upload/file.dat";
    SftpException failure = new SftpException(ChannelSftp.SSH_FX_FAILURE, "target exists");
    doThrow(failure).when(sftp).rename(tempRemotePath, remotePath);

    assertThatThrownBy(
            () -> SftpDispatchChannelAdapter.publishRemoteFile(sftp, tempRemotePath, remotePath))
        .isSameAs(failure);

    verify(sftp, never()).rm(remotePath);
    verify(sftp).rename(tempRemotePath, remotePath);
  }

  @Test
  @DisplayName("临时文件缺失导致重命名失败时,不删除远端已有目标文件")
  void publishRemoteFile_doesNotRemoveTargetWhenTempIsMissing() throws Exception {
    ChannelSftp sftp = mock(ChannelSftp.class);
    String tempRemotePath = "/upload/file.dat.tmp-1";
    String remotePath = "/upload/file.dat";
    SftpException failure = new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "temp missing");
    doThrow(failure).when(sftp).rename(tempRemotePath, remotePath);
    doThrow(failure).when(sftp).stat(tempRemotePath);

    assertThatThrownBy(
            () -> SftpDispatchChannelAdapter.publishRemoteFile(sftp, tempRemotePath, remotePath))
        .isInstanceOf(SftpException.class);

    verify(sftp, never()).rm(remotePath);
  }

  @Test
  @DisplayName("服务端明确拒绝重命名时不得删除已有远端目标")
  void publishRemoteFile_preservesExistingTarget_whenRenameIsDenied() throws Exception {
    ChannelSftp sftp = mock(ChannelSftp.class);
    String temporaryPath = "/upload/file.dat.tmp-1";
    String targetPath = "/upload/file.dat";
    SftpException denied = new SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "denied");
    doThrow(denied).when(sftp).rename(temporaryPath, targetPath);
    doReturn(mock(SftpATTRS.class)).when(sftp).stat(temporaryPath);
    doReturn(mock(SftpATTRS.class)).when(sftp).stat(targetPath);

    assertThatThrownBy(
            () -> SftpDispatchChannelAdapter.publishRemoteFile(sftp, temporaryPath, targetPath))
        .isSameAs(denied);
    verify(sftp, never()).rm(targetPath);
  }

  @Test
  @DisplayName("SFTP 明确拒绝重命名时可安全重试,连接丢失时结果不明")
  void publicationFailureClassification_matchesSftpResponseCertainty() {
    SftpException rejected = new SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "denied");
    SftpException lost = new SftpException(ChannelSftp.SSH_FX_CONNECTION_LOST, "response lost");

    assertThat(SftpDispatchChannelAdapter.isAmbiguousPublicationFailure(rejected))
        .isFalse();
    assertThat(SftpDispatchChannelAdapter.isAmbiguousPublicationFailure(lost)).isTrue();
    assertThat(SftpDispatchChannelAdapter.isAmbiguousPublicationFailure(
            new IllegalStateException("transport failed")))
        .isTrue();
  }

  @Test
  @DisplayName("JSch 包装的读取连接故障为发布结果不明，服务端明确失败仍为确定失败")
  void shouldRecognizeWrappedTransportFailure_whenClassifyingPublication() {
    SftpException lostReply = new SftpException(
        ChannelSftp.SSH_FX_FAILURE, "inputstream is closed", new IOException("stream closed"));
    SftpException serverFailure = new SftpException(ChannelSftp.SSH_FX_FAILURE, "server rejected");

    assertThat(SftpDispatchChannelAdapter.isAmbiguousPublicationFailure(lostReply))
        .isTrue();
    assertThat(SftpDispatchChannelAdapter.isAmbiguousPublicationFailure(serverFailure))
        .isFalse();
  }

  @Test
  @DisplayName("SFTP 连接失败结果不保存主机名中的凭据")
  void dispatch_masksCredentialInConnectionFailure() {
    SftpDispatchChannelAdapter adapter = new SftpDispatchChannelAdapter(
        mock(DispatchFileContentResolver.class),
        new BatchSecurityProperties(),
        mock(Environment.class));
    Map<String, Object> channelConfig = Map.of(
        "sftp_host", "token=probe-secret.invalid",
        "sftp_user", "test-user",
        "sftp_password", "test-password");
    DispatchPayload payload = new DispatchPayload(
        "file-1", "FILE_A", "SFTP_CH", "target", "req-1", "rcpt-1", true, false, "NORMAL",
        Map.of());
    DispatchCommand command =
        new DispatchCommand("t1", "trace-1", Map.of("id", 1L), channelConfig, payload);

    try {
      DispatchResult result = adapter.dispatch(command);

      assertThat(result.success()).isFalse();
      assertThat(result.externalOutcomeUnknown()).isFalse();
      assertThat(result.message()).doesNotContain("probe-secret");
    } finally {
      adapter.shutdownDisconnectExecutor();
    }
  }
}
