package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import org.junit.jupiter.api.Test;

class SftpDispatchChannelAdapterTest {

  @Test
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
    verify(sftp, org.mockito.Mockito.times(2)).rename(tempRemotePath, remotePath);
  }

  @Test
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
