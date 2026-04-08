package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * NAS 渠道分发适配器，将文件拷贝到 NAS 远程目录。
 */
@Component
@RequiredArgsConstructor
public class NasDispatchChannelAdapter implements DispatchChannelAdapter {

    private final DispatchFileContentResolver contentResolver;

    @Override
    public boolean supports(String channelType) {
        return channelType != null && "NAS".equalsIgnoreCase(channelType);
    }

    @Override
    public DispatchResult dispatch(DispatchCommand command) {
        return RemoteFilesystemDispatchSupport.dispatchNas(command, contentResolver);
    }
}
