package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import java.util.Map;

/** Dispatch 出站 sidecar manifest 的目标引用和自身完整性信息。 */
public record DispatchManifestRef(String ref, String checksum, long sizeBytes) {

  public void putAttributes(Map<String, Object> target) {
    target.put("dispatchManifestRef", this);
    putPipelineOutputs(target);
  }

  public void putPipelineOutputs(Map<String, Object> target) {
    target.put("manifestRef", ref);
    target.put("manifestChecksum", checksum);
    target.put("manifestSizeBytes", sizeBytes);
  }

  public void putFileMetadata(Map<String, Object> target) {
    target.put("dispatchManifestRef", ref);
    target.put("dispatchManifestChecksum", checksum);
    target.put("dispatchManifestSizeBytes", sizeBytes);
  }
}
