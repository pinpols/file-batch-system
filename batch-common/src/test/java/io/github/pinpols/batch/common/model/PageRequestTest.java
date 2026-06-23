package io.github.pinpols.batch.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PageRequestTest {

  @Test
  void shouldClampInvalidPageNoAndPageSize() {
    PageRequest r = new PageRequest(0, 0);
    assertThat(r.pageNo()).isEqualTo(1);
    assertThat(r.pageSize()).isEqualTo(20);
  }

  @Test
  void shouldPreserveValidValues() {
    PageRequest r = new PageRequest(3, 50);
    assertThat(r.pageNo()).isEqualTo(3);
    assertThat(r.pageSize()).isEqualTo(50);
  }
}
