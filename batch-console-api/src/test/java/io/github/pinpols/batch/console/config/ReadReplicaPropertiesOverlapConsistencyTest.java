package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.ConsoleReadReplicaProperties;
import java.beans.PropertyDescriptor;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

/**
 * 钉住 {@code batch.console.read-replica} 的两个绑定方之间的键重叠契约。
 *
 * <p>该前缀是仓库内唯一「共享前缀且子键重叠」的案例：console-api 的 {@link ReadReplicaProperties} 持有完整连接池
 * 配置，batch-common 的 {@link ConsoleReadReplicaProperties} 是启动守护用的只读视图。模块依赖方向决定了二者不能
 * 合并，重叠键因此只能靠契约维持一致——本测试就是那份契约。
 *
 * <p>本测试能捕获两类回归：
 *
 * <ul>
 *   <li>任一侧新增/删除字段导致重叠键集合变化 → {@link #overlappingLeafKeysAreExactlyTheDocumentedSet()} 失败，
 *       迫使作者确认新重叠键的默认值是否也一致；
 *   <li>{@code enabled} 的 Java 默认值在两侧被改成不同值 → {@link #overlappingKeysShareTheSameDefault()} 失败
 *       （这是历史隐患：{@code check-config-defaults-sync.py} 只比对 yml 与 compose 的环境变量默认值，
 *       <b>不覆盖 Java 字段默认值</b>，且该脚本的立项动机正是本前缀出过一次默认值静默不一致）。
 * </ul>
 *
 * <p>重叠键集合变化时，除本测试外还需同步更新 {@link ConsoleReadReplicaProperties} 的类注释。
 */
class ReadReplicaPropertiesOverlapConsistencyTest {

  /** 当前已知的重叠叶子键；任何增减都必须是有意为之并同步更新类注释。 */
  private static final Set<String> EXPECTED_OVERLAP =
      new TreeSet<>(Set.of("enabled", "primary.password", "replica.password"));

  @Test
  void overlappingLeafKeysAreExactlyTheDocumentedSet() {
    Set<String> overlap = new TreeSet<>(leafPaths(new ConsoleReadReplicaProperties()));
    overlap.retainAll(leafPaths(new ReadReplicaProperties()));

    assertThat(overlap)
        .as("重叠键集合变化必须同步更新 ConsoleReadReplicaProperties 类注释与本测试")
        .isEqualTo(EXPECTED_OVERLAP);
  }

  @Test
  void overlappingKeysShareTheSameDefault() {
    ConsoleReadReplicaProperties view = new ConsoleReadReplicaProperties();
    ReadReplicaProperties full = new ReadReplicaProperties();

    assertThat(view.isEnabled())
        .as("batch.console.read-replica.enabled 的 Java 默认值必须两侧一致")
        .isEqualTo(full.isEnabled());

    // 密码在两侧都不得有 Java 默认值：留空才由 yml / Secret 提供，避免把凭据默认值写进代码。
    assertThat(view.getPrimary().getPassword())
        .as("只读视图 primary.password 不应有默认值")
        .isNull();
    assertThat(full.getPrimary().getPassword())
        .as("完整配置 primary.password 不应有默认值")
        .isNull();
    assertThat(view.getReplica().getPassword())
        .as("只读视图 replica.password 不应有默认值")
        .isNull();
    assertThat(full.getReplica().getPassword())
        .as("完整配置 replica.password 不应有默认值")
        .isNull();
  }

  /** 递归收集叶子属性路径；嵌套可绑定对象继续下钻。 */
  private static Set<String> leafPaths(Object bean) {
    Set<String> paths = new LinkedHashSet<>();
    collect(new BeanWrapperImpl(bean), "", paths);
    return paths;
  }

  private static void collect(BeanWrapper wrapper, String prefix, Set<String> out) {
    for (PropertyDescriptor descriptor : wrapper.getPropertyDescriptors()) {
      if (descriptor.getReadMethod() == null || "class".equals(descriptor.getName())) {
        continue;
      }
      String path = prefix.isEmpty() ? descriptor.getName() : prefix + "." + descriptor.getName();
      Object value = wrapper.getPropertyValue(descriptor.getName());
      if (isNested(value)) {
        collect(new BeanWrapperImpl(value), path, out);
      } else {
        out.add(path);
      }
    }
  }

  private static boolean isNested(Object value) {
    return value != null
        && !(value instanceof CharSequence)
        && !(value instanceof Number)
        && !(value instanceof Boolean)
        && !(value instanceof Enum<?>)
        && !(value instanceof Collection<?>)
        && !(value instanceof Map<?, ?>)
        && !value.getClass().isArray()
        && !value.getClass().isPrimitive();
  }
}
