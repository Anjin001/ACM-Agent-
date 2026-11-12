package cn.xunlian;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.xunlian.Models.*;

@Service
public class TopicService {
    private final List<Topic> topics;
    private final Map<String, Topic> byName = new LinkedHashMap<>();
    private final Map<String, Set<String>> included = new HashMap<>();

    public TopicService(ObjectMapper json) throws Exception {
        topics = json.readValue(new ClassPathResource("topics.json").getInputStream(), new TypeReference<>() {});
        topics.forEach(topic -> byName.put(topic.name, topic));
        family("动态规划", "线性DP", "背包DP", "区间DP", "树形DP", "状压DP", "数位DP", "概率与期望DP");
        family("搜索", "深度优先搜索", "广度优先搜索", "回溯与剪枝", "记忆化搜索", "双向搜索");
        family("二分", "二分答案");
        family("数据结构", "栈与队列", "堆与优先队列", "并查集", "树状数组", "线段树", "ST表", "单调栈", "单调队列", "平衡树", "可持久化数据结构");
        family("图论", "最短路", "Dijkstra", "Bellman-Ford与SPFA", "Floyd", "最小生成树", "拓扑排序", "强连通分量", "桥与割点", "二分图匹配", "网络流", "最小费用流", "2-SAT", "欧拉路");
        family("最短路", "Dijkstra", "Bellman-Ford与SPFA", "Floyd");
        family("网络流", "最小费用流");
        family("数学", "数论", "质数与筛法", "快速幂", "扩展欧几里得与逆元", "中国剩余定理", "组合计数", "博弈论", "矩阵快速幂", "高斯消元");
        family("数论", "质数与筛法", "快速幂", "扩展欧几里得与逆元", "中国剩余定理");
        family("字符串", "字符串哈希", "KMP", "Trie", "Manacher", "AC自动机", "后缀数组", "后缀自动机");
        family("树上遍历", "LCA与倍增", "树链剖分", "点分治");
        family("计算几何", "凸包");
        family("多项式与FFT", "NTT");
        family("后缀结构", "后缀数组", "后缀自动机");
        family("矩阵与线性代数", "矩阵快速幂", "高斯消元");
        family("概率与期望", "概率与期望DP");
        included.get("数学").addAll(List.of("矩阵与线性代数", "概率与期望", "概率与期望DP"));
        included.get("字符串").add("后缀结构");
        for (Topic topic : topics) {
            if (!byName.keySet().containsAll(topic.prerequisites)) throw new IllegalStateException("未知前置专题 " + topic.name);
        }
    }

    private void family(String name, String... children) {
        Set<String> names = new HashSet<>(List.of(children));
        names.add(name);
        included.put(name, names);
    }

    public List<Topic> topics() { return topics; }
    public Set<String> names() { return Collections.unmodifiableSet(byName.keySet()); }
    public boolean matches(Problem problem, String topic) {
        return problem.tags.stream().anyMatch(included.getOrDefault(topic, Set.of(topic))::contains);
    }
    public List<String> fromSourceTags(List<String> tags) {
        return topics.stream().filter(topic -> topic.cfTag != null && !topic.cfTag.isBlank() && tags.contains(topic.cfTag)).map(topic -> topic.name).toList();
    }
}
