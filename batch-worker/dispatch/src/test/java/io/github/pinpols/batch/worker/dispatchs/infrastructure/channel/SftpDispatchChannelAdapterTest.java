package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SFTP 分发适配器:目标已存在时按覆盖语义重试重命名,临时文件缺失时不误删目标")
class SftpDispatchChannelAdapterTest {

  @Test
  @DisplayName("远端目标已存在时先删除目标再重命名,重试一次后发布成功")
  void publishRemoteFile_retriesWithOverwriteSemanticsWhenTargetExists() throws Exception {
    ChannelSftp sftp = mock(ChannelSftp.class);
    String tempRemotePath = "/upload/file.dat.tmp-1";
    String remotePath = "/upload/file.dat";
    doThrow(new SftpException(ChannelSftp.SSH_FX_FAILURE, "target exists"))
        .doNothing()
        .when(sftp)
        .rename(tempRemotePath, remotePath);
    doNothing().when(sftp).rm(remotePath);

    SftpDispatchChannelAdapter.publishRemoteFile(sftp, tempRemotePath, remotePath);

    verify(sftp).rm(remotePath);
    verify(sftp, times(2)).rename(tempRemotePath, remotePath);
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

    assertThrows(
        SftpException.class,
        () -> SftpDispatchChannelAdapter.publishRemoteFile(sftp, tempRemotePath, remotePath));

    verify(sftp, never()).rm(remotePath);
  }
}
