package cn.xunlian;

import java.util.*;
import java.util.regex.Pattern;

import static cn.xunlian.Models.*;

final class AbilityDetails {
    private record Skill(String name, Pattern clue) {}
    record Detail(String name, int recent, int recentIndependent, int previous, int previousIndependent,
                  int accepted, String source) {}
    record Group(String name, List<Detail> skills) {}
    record Mistake(String name, int count, String lastProblem, String lastNote, String reminder) {}
    private record Check(String name, Pattern clue, String reminder) {}
    private record Sample(Feedback feedback, Set<String> skills) {}

    private static final Map<String, List<Skill>> TREE = new LinkedHashMap<>();
    static {
        add("基础实现", "数组与下标", "下标|索引|index", "模拟与状态", "模拟|状态更新", "枚举与暴力", "枚举|暴力", "输入输出", "输入输出|读入");
        add("查找与决策", "二分答案", "二分答案|单调判定|binary search on", "边界二分", "lower_bound|upper_bound|边界二分|第一个满足",
                "双指针", "双指针|two pointers", "滑动窗口", "滑动窗口|sliding window", "贪心与交换论证", "交换论证|贪心",
                "区间调度", "区间调度|线段覆盖|区间选择", "前缀和与差分", "前缀和|差分数组|prefix sum");
        add("搜索与图论", "DFS 与回溯", "回溯|DFS|深度优先", "BFS 与分层", "BFS|广度优先|分层搜索",
                "最短路", "Dijkstra|Bellman|Floyd|SPFA|最短路", "拓扑排序", "拓扑排序|拓扑序",
                "最小生成树", "最小生成树|Kruskal|Prim", "连通分量", "Tarjan|强连通|连通分量",
                "树上查询", "LCA|最近公共祖先|树链剖分", "匹配与网络流", "网络流|Dinic|二分图匹配", "状态压缩搜索", "IDA|剪枝|记忆化搜索");
        add("动态规划", "背包", "背包|装箱", "线性与序列 DP", "线性DP|序列DP|最长上升子序列|数字三角形",
                "区间 DP", "区间\\s*DP|区间动态规划", "树形 DP", "树形\\s*DP|树上动态规划",
                "状压 DP", "状压|状态压缩\\s*DP", "数位 DP", "数位\\s*DP", "计数与概率 DP", "计数\\s*DP|概率\\s*DP|期望\\s*DP",
                "DP 优化", "斜率优化|单调队列优化|决策单调性");
        add("数据结构", "并查集", "并查集|DSU", "树状数组", "树状数组|Fenwick", "线段树", "线段树|segment tree",
                "堆与优先队列", "优先队列|priority_queue|二叉堆", "单调栈与单调队列", "单调栈|单调队列",
                "哈希表与映射", "哈希表|unordered_map", "稀疏表与 RMQ", "稀疏表|sparse table|RMQ",
                "平衡树与有序集合", "平衡树|Treap|Splay|有序集合", "可持久化结构", "可持久化|主席树");
        add("数学", "整除与最大公约数", "最大公约数|gcd|欧几里得|整除", "质数与筛法", "筛素数|线性筛|埃氏筛|质因数",
                "模运算与逆元", "逆元|快速幂|模运算|同余", "组合计数", "组合数|排列组合|容斥|卡特兰",
                "概率与期望", "概率|期望", "线性代数", "矩阵快速幂|高斯消元|线性基", "多项式与卷积", "NTT|FFT|卷积",
                "博弈论", "SG函数|Sprague|Nim");
        add("字符串", "模式匹配（KMP / Z）", "KMP|Z\\s*函数|前缀函数", "字符串哈希", "字符串哈希|滚动哈希|rolling hash",
                "字典树", "Trie|字典树", "多模式匹配", "AC自动机|Aho.Corasick", "回文", "Manacher|回文树",
                "后缀结构", "后缀数组|后缀自动机|suffix array", "字符串构造与比较", "最小表示法|字典序构造|最小循环表示");
        add("计算几何", "向量与方向判定", "向量|叉积|点积|方向判定", "线段相交", "线段相交|segment intersection",
                "三角形与距离", "三角形|点到直线|距离公式", "凸包", "凸包|convex hull", "旋转卡壳", "旋转卡壳|凸包直径|rotating calipers",
                "圆与覆盖", "最小圆覆盖|圆相交|圆覆盖|enclosing circle", "多边形与面积", "多边形面积|鞋带公式|点在多边形",
                "扫描线与半平面", "扫描线|半平面交", "几何精度", "浮点精度|几何精度|epsilon");
    }

    private static final Map<String, List<String>> VERIFIED = Map.ofEntries(
            Map.entry("LG-P2249", List.of("边界二分")), Map.entry("CF-706-B", List.of("边界二分")),
            Map.entry("LG-P1873", List.of("二分答案")), Map.entry("LG-P2440", List.of("二分答案")),
            Map.entry("LG-P1048", List.of("背包")), Map.entry("LG-P1049", List.of("背包")),
            Map.entry("LG-P1216", List.of("线性与序列 DP")), Map.entry("LG-P3371", List.of("最短路")),
            Map.entry("LG-P3367", List.of("并查集")), Map.entry("LG-P3375", List.of("模式匹配（KMP / Z）")),
            Map.entry("LG-P3383", List.of("质数与筛法")), Map.entry("LG-P2742", List.of("凸包")),
            Map.entry("LG-P1452", List.of("凸包", "旋转卡壳")), Map.entry("LG-P1742", List.of("圆与覆盖")));

    private static final List<Check> CHECKS = List.of(
            check("边界与空输入", "越界|空数组|空串|单元素|边界|off.by.one", "动手前写清下标区间，手推空、单元素和最大规模。"),
            check("溢出与类型", "溢出|爆int|类型转换|long long", "算出中间值上界，检查乘法前是否已提升类型。"),
            check("精度与比较", "精度|浮点|误差|epsilon", "先确定误差要求，再检查相等比较和临界输入。"),
            check("初始化与状态清理", "初始化|没清空|未清空|没重置|状态残留", "用两组不同输入连续运行，检查数组和全局状态。"),
            check("复杂度与超时", "超时|复杂度|TLE", "按最大输入估算总操作数，检查嵌套循环和重复计算。"),
            check("审题与约束", "审题|读错题|漏看|看错|题意", "动手前复述输入约束和输出条件，检查重复值与负数。"),
            check("算法选择", "思路错误|选错|误用|想不到|不会建模", "先写出要维护的性质，并用反例检查算法适用条件。"),
            check("实现与调试", "实现写错|写反|漏写|调试失败", "用最小反例定位第一次偏离预期的状态。"),
            check("时间分配", "时间不够|调试太久|时间分配|超出预计", "给建模和调试分别设时间点，卡住后先写最小反例。"));

    private AbilityDetails() {}

    static List<Group> details(List<Problem> problems, List<Feedback> feedback, Set<String> accepted) {
        Map<String, Problem> byId = new HashMap<>();
        problems.forEach(problem -> byId.put(problem.id, problem));
        var cutoff = java.time.Instant.now().minusSeconds(30L * 86400);
        var previousCutoff = cutoff.minusSeconds(30L * 86400);
        Map<List<Object>, Feedback> latest = new LinkedHashMap<>();
        feedback.stream().filter(row -> timestamp(row) != null && !timestamp(row).isBefore(previousCutoff))
                .sorted(Comparator.comparing(AbilityDetails::timestamp))
                .forEach(row -> latest.put(List.of(row.problemId, !timestamp(row).isBefore(cutoff)), row));
        Map<String, Set<String>> tagged = new HashMap<>();
        problems.forEach(problem -> tagged.put(problem.id, classify(problem, "")));
        Map<String, Integer> cfCounts = new HashMap<>();
        accepted.forEach(id -> tagged.getOrDefault(id, Set.of()).forEach(skill -> cfCounts.merge(skill, 1, Integer::sum)));
        List<Sample> samples = latest.values().stream().map(row ->
                new Sample(row, classify(byId.get(row.problemId), row.editorialNotes))).toList();
        List<Group> groups = new ArrayList<>();
        for (var group : TREE.entrySet()) {
            List<Detail> details = new ArrayList<>();
            for (Skill skill : group.getValue()) {
                int recent = 0, recentIndependent = 0, previous = 0, previousIndependent = 0;
                boolean editorial = false;
                for (Sample sample : samples) {
                    if (!sample.skills().contains(skill.name())) continue;
                    Feedback row = sample.feedback();
                    editorial |= !tagged.getOrDefault(row.problemId, Set.of()).contains(skill.name());
                    var when = timestamp(row);
                    boolean independent = "independent".equals(row.outcome);
                    if (!when.isBefore(cutoff)) { recent++; if (independent) recentIndependent++; }
                    else if (!when.isBefore(previousCutoff)) { previous++; if (independent) previousIndependent++; }
                }
                int cf = cfCounts.getOrDefault(skill.name(), 0);
                details.add(new Detail(skill.name(), recent, recentIndependent, previous, previousIndependent, cf,
                        editorial ? "含题解关键词推断" : recent + previous + cf > 0 ? "题目标签或人工分类" : ""));
            }
            groups.add(new Group(group.getKey(), details));
        }
        return groups;
    }

    static List<Mistake> mistakes(List<Feedback> feedback) {
        List<Feedback> recent = feedback.stream()
                .sorted(Comparator.comparing((Feedback row) -> Objects.toString(row.createdAt, "")).reversed()).limit(30).toList();
        List<Mistake> result = new ArrayList<>();
        for (Check check : CHECKS) {
            List<Feedback> matches = recent.stream().filter(row -> check.clue().matcher(mistakeText(row)).find()).toList();
            if (matches.isEmpty()) continue;
            Feedback latest = matches.get(0);
            result.add(new Mistake(check.name(), matches.size(), latest.problemId, mistakeText(latest), check.reminder()));
        }
        result.sort(Comparator.comparingInt(Mistake::count).reversed().thenComparing(Mistake::name));
        return result;
    }

    private static java.time.Instant timestamp(Feedback feedback) {
        try { return java.time.Instant.parse(Objects.toString(feedback.createdAt, "")); }
        catch (java.time.format.DateTimeParseException ex) { return null; }
    }

    private static String mistakeText(Feedback feedback) {
        if (feedback.mistakeNote != null && !feedback.mistakeNote.isBlank()) return feedback.mistakeNote;
        if (feedback.mistakeNote == null && !"independent".equals(feedback.outcome)) return Objects.toString(feedback.note, "");
        return "";
    }

    private static Set<String> classify(Problem problem, String editorial) {
        if (problem == null) return Set.of();
        Set<String> result = new HashSet<>(VERIFIED.getOrDefault(problem.id, List.of()));
        for (List<Skill> skills : TREE.values()) {
            for (Skill skill : skills) {
                if (problem.tags.contains(skill.name())) result.add(skill.name());
            }
        }
        if (!result.isEmpty() || editorial == null || editorial.isBlank()) return result;
        for (List<Skill> skills : TREE.values()) {
            for (Skill skill : skills) {
                if (skill.clue().matcher(editorial).find()) result.add(skill.name());
            }
        }
        return result;
    }

    private static void add(String group, String... pairs) {
        List<Skill> skills = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            skills.add(new Skill(pairs[i], Pattern.compile(pairs[i + 1], Pattern.CASE_INSENSITIVE)));
        }
        TREE.put(group, List.copyOf(skills));
    }

    private static Check check(String name, String clue, String reminder) {
        return new Check(name, Pattern.compile(clue, Pattern.CASE_INSENSITIVE), reminder);
    }
}
