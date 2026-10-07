package io.github.pinpols.batch.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("分页请求:非法取值钳制与合法取值保留")
class PageRequestTest {

  @Test
  @DisplayName("页码与页大小非正数时钳制到默认值")
  void shouldClampInvalidPageNoAndPageSize() {
    PageRequest r = new PageRequest(0, 0);
    assertThat(r.pageNo()).isEqualTo(1);
    assertThat(r.pageSize()).isEqualTo(20);
  }

  @Test
  @DisplayName("合法页码与页大小原样保留")
  void shouldPreserveValidValues() {
    PageRequest r = new PageRequest(3, 50);
    assertThat(r.pageNo()).isEqualTo(3);
    assertThat(r.pageSize()).isEqualTo(50);
  }

  @Test
  @DisplayName("超大页码与页大小钳制到安全上界")
  void shouldClampPageNoAndPageSizeToSafeUpperBounds() {
    PageRequest r = new PageRequest(Integer.MAX_VALUE, Integer.MAX_VALUE);
    assertThat(r.pageNo()).isEqualTo(PageRequest.MAX_PAGE_NO);
    assertThat(r.pageSize()).isEqualTo(PageRequest.MAX_PAGE_SIZE);
  }
}
