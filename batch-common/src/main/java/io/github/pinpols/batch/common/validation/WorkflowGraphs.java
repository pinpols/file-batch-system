package io.github.pinpols.batch.common.validation;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流图的共享算法：编排器的图校验与控制台的 Excel 拓扑校验都需要“从一组节点出发的可达集合”和“节点参数里的 DSL 节点引用”，
 * 这里只放两端完全一致的部分。环检测、祖先计算两端上报方式不同，不在此共享。
 */
public final class WorkflowGraphs {

  /** 节点参数里引用其他节点输出的写法：{@code $.nodes.<nodeCode>.}。 */
  private static final Pattern DSL_NODE_REF = Pattern.compile("\\$\\.nodes\\.(\\w+)\\.");

  private WorkflowGraphs() {}

  /** 从所有 roots 出发，沿邻接表能到达的节点，包括 roots 本身；邻接表缺少的节点视为没有出边，遇到环不会死循环。roots 与邻接表中的节点编码必须非 null（调用方先过滤空白），不支持 null。 */
  public static Set<String> reachableFrom(
      Collection<String> roots, Map<String, List<String>> adjacency) {
    Set<String> visited = new HashSet<>();
    Deque<String> pending = new ArrayDeque<>();
    for (String root : roots) {
      pending.push(root);
      while (EmptyChecks.isNotEmpty(pending)) {
        String node = pending.pop();
        if (!visited.add(node)) {
          continue;
        }
        for (String next : adjacency.getOrDefault(node, List.of())) {
          pending.push(next);
        }
      }
    }
    return visited;
  }

  /** 按出现顺序返回节点参数里引用的节点编码，保留重复；参数为空白时返回空列表。 */
  public static List<String> dslNodeReferences(String nodeParams) {
    List<String> references = new ArrayList<>();
    if (!Texts.hasText(nodeParams)) {
      return references;
    }
    Matcher matcher = DSL_NODE_REF.matcher(nodeParams);
    while (matcher.find()) {
      references.add(matcher.group(1));
    }
    return references;
  }
}
