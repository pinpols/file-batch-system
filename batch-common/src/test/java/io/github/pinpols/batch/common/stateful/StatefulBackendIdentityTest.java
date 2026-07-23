package io.github.pinpols.batch.common.stateful;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StatefulBackendIdentityTest {

  @Test
  void databaseIdentityRemovesAuthorityCredentialsQueryAndFragment() {
    String identity =
        StatefulBackendIdentity.database(
            "jdbc:postgresql://alice:p%40ss@DB.EXAMPLE:5432/batch_platform"
                + "?user=alice&password=top-secret&ssl=true#hidden");

    assertThat(identity).isEqualTo("jdbc=jdbc:postgresql://db.example:5432/batch_platform");
    assertThat(identity)
        .doesNotContain("alice")
        .doesNotContain("p%40ss")
        .doesNotContain("top-secret");
  }

  @Test
  void databaseIdentityDropsNonCredentialQueryForStableLocationComparison() {
    assertThat(
            StatefulBackendIdentity.database(
                "jdbc:postgresql://platform-db/batch_platform"
                    + "?currentSchema=batch&reWriteBatchedInserts=true"))
        .isEqualTo("jdbc=jdbc:postgresql://platform-db/batch_platform");
  }
}
