package io.github.pinpols.batch.common.storage.ingress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.mapper.ImportIngressOwnershipMapper;
import io.github.pinpols.batch.common.mapper.ImportIngressOwnershipMapper.OwnershipRow;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository.Binding;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository.Scope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("入口归属仓储的范围与绑定组装")
class ImportIngressOwnershipRepositoryTest {

  private final ImportIngressOwnershipMapper mapper = mock(ImportIngressOwnershipMapper.class);
  private final ImportIngressOwnershipRepository repository =
      new ImportIngressOwnershipRepository(mapper);

  @Test
  @DisplayName("活动范围的多行绑定保持顺序并合并到同一范围")
  void findActiveScope_shouldGroupBindings_inMapperOrder() {
    OwnershipRow channel = scope("active", "ACTIVE", "incoming/");
    channel.setBindingType("CHANNEL");
    channel.setTenantId("tenant-a");
    channel.setBindingPrefix("incoming/tenant-a/");
    channel.setChannelCode("sftp-a");
    channel.setEnabled(true);
    OwnershipRow manifest = scope("active", "ACTIVE", "incoming/");
    manifest.setBindingType("MANIFEST");
    manifest.setTenantId("tenant-a");
    manifest.setBindingPrefix("incoming/tenant-a/");
    manifest.setFileGroupCode("group-a");
    manifest.setManifestKeyTemplate("manifests/group-{bizDate}.json");
    manifest.setEnabled(true);
    when(mapper.findActiveScope("active")).thenReturn(List.of(channel, manifest));

    Scope result = repository.findActiveScope("active").orElseThrow();

    assertThat(result.scopeId()).isEqualTo("active");
    assertThat(result.bindings())
        .containsExactly(
            new Binding("CHANNEL", "tenant-a", "incoming/tenant-a/", null, null, "sftp-a", true),
            new Binding(
                "MANIFEST",
                "tenant-a",
                "incoming/tenant-a/",
                "group-a",
                "manifests/group-{bizDate}.json",
                null,
                true));
    verify(mapper).findActiveScope("active");
  }

  @Test
  @DisplayName("退休范围的停用清单仍受保护，无绑定范围不会被丢弃")
  void listProtectedScopes_shouldKeepRetiredManifestAndEmptyScope() {
    OwnershipRow retired = scope("retired", "RETIRED", "incoming/retired/");
    retired.setBindingType("MANIFEST");
    retired.setTenantId("tenant-a");
    retired.setBindingPrefix("incoming/retired/");
    retired.setManifestKeyTemplate("manifests/retired-{bizDate}.json");
    retired.setEnabled(false);
    OwnershipRow withoutBindings = scope("empty", "ACTIVE", "incoming/empty/");
    when(mapper.listProtectedScopes("storage-a")).thenReturn(List.of(retired, withoutBindings));

    List<Scope> result = repository.listProtectedScopes("storage-a");

    assertThat(result).extracting(Scope::scopeId).containsExactly("retired", "empty");
    assertThat(result.get(0).state()).isEqualTo("RETIRED");
    assertThat(result.get(0).bindings())
        .singleElement()
        .satisfies(binding -> assertThat(binding.enabled()).isFalse());
    assertThat(result.get(0).protects("bucket-a", "manifests/retired-2026-11-10.json"))
        .isTrue();
    assertThat(result.get(0).protects("other-bucket", "manifests/retired-2026-11-10.json"))
        .isFalse();
    assertThat(result.get(1).bindings()).isEmpty();
    assertThat(result.get(1).protects("bucket-a", "incoming/empty/data.csv")).isTrue();
    verify(mapper).listProtectedScopes("storage-a");
  }

  private static OwnershipRow scope(String id, String state, String prefix) {
    OwnershipRow row = new OwnershipRow();
    row.setScopeId(id);
    row.setStorageId("storage-a");
    row.setBucketName("bucket-a");
    row.setScopePrefix(prefix);
    row.setState(state);
    return row;
  }
}
