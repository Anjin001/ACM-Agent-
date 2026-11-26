package cn.xunlian;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.xunlian.Models.*;

/**
 * 本地代码审查：先跑规则检查（离线可用），配置模型后再叠加语义审查。
 * 只接收用户在前端确认过的文件内容，落库时仅保存文件名、行数与结论。
 */
@Service
public class CodeReviewer {
    private static final int MAX_FILES = 5;
    private static final int MAX_FILE_CHARS = 20000;
    private static final int MAX_TOTAL_CHARS = 60000;

    private static final Pattern MID_SUM = Pattern.compile("\\(\\s*([A-Za-z_]\\w*)\\s*\\+\\s*([A-Za-z_]\\w*)\\s*\\)\\s*/\\s*2");
    private static final Pattern LONG_LONG = Pattern.compile("long\\s+long\\s+([A-Za-z_]\\w*)");
    private static final Pattern DOUBLE_VAR = Pattern.compile("\\bdouble\\s+([A-Za-z_]\\w*)");
    private static final Pattern ARRAY_SIZE = Pattern.compile("const\\s+int\\s+[A-Za-z_]\\w*\\s*=\\s*([0-9]{1,6})");
    private static final Pattern UPPER_BOUND = Pattern.compile("for\\s*\\([^;]*;[^;]*<=\\s*([A-Za-z_]\\w*)");

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final CoachStore store;
    private final CoachMemory memory;
    private final AiCoach ai;

    public CodeReviewer(JdbcTemplate db, ObjectMapper json, CoachStore store, CoachMemory memory, AiCoach ai) {
        this.db = db;
        this.json = json;
        this.store = store;
        this.memory = memory;
        this.ai = ai;
    }

    public CodeReview review(long userId, CodeReviewRequest request) {
        List<SourceFile> files = clean(request.files());
        Problem problem = request.problemId() == null ? null : store.problems().stream()
                .filter(item -> item.id.equals(request.problemId())).findFirst().orElse(null);

        CodeReview review = new CodeReview();
        review.id = UUID.randomUUID().toString();
        review.createdAt = Instant.now().toString();
        review.taskId = request.taskId();
        review.problemId = request.problemId();
        review.problemTitle = problem == null ? request.problemId() : problem.title;
        files.forEach(file -> review.files.add(new ReviewedFile(file.name, countLines(file.content), file.content.length())));

        review.findings.addAll(rules(files));
        review.engine = "rules";
        if (ai.configured()) {
            review.engine = ai.reviewCode(review, problem, files) ? "model" : "rules";
        }
        count(review);
        review.summary = summarize(review);

        persist(userId, review);
        memory.observeReview(userId, review);
        return review;
    }

    public List<CodeReview> history(long userId, int limit) {
        return db.query("SELECT * FROM code_reviews WHERE user_id=? ORDER BY created_at DESC LIMIT ?", (rs, row) -> {
            CodeReview review = new CodeReview();
            review.id = rs.getString("id");
            review.taskId = rs.getString("task_id");
            review.problemId = rs.getString("problem_id");
            review.engine = rs.getString("engine");
            review.createdAt = rs.getString("created_at");
            review.files = decode(rs.getString("files"), new TypeReference<List<ReviewedFile>>() {});
            review.findings = decode(rs.getString("findings"), new TypeReference<List<Finding>>() {});
            count(review);
            review.summary = summarize(review);
            return review;
        }, userId, limit);
    }

    // ------------------------------------------------------------ 规则检查

    private List<Finding> rules(List<SourceFile> files) {
        List<Finding> findings = new ArrayList<>();
        for (SourceFile file : files) {
            String[] lines = file.content.split("\n", -1);
            Set<String> longLongs = names(file.content, LONG_LONG);
            Set<String> doubles = names(file.content, DOUBLE_VAR);
            int[] loopDepth = loopDepth(lines);
            boolean fastIo = file.content.contains("sync_with_stdio");
            boolean usesCin = file.content.contains("cin");
            boolean usesScanf = file.content.contains("scanf");
            boolean reportedSize = false;

            for (int i = 0; i < lines.length; i++) {
                String code = strip(lines[i]);
                if (code.isBlank()) continue;
                int line = i + 1;

                Matcher mid = MID_SUM.matcher(code);
                if (mid.find() && !code.replace(" ", "").contains("(r-l)")) {
                    findings.add(finding(file.name, line, "medium", "边界", code,
                            "整数中点用 (" + mid.group(1) + " + " + mid.group(2) + ") / 2 计算，端点接近 2^31 时会溢出",
                            "改成 " + mid.group(1) + " + (" + mid.group(2) + " - " + mid.group(1) + ") / 2，或改用 long long"));
                }

                if (code.contains("memset(") && doubles.stream().anyMatch(code::contains)) {
                    findings.add(finding(file.name, line, "high", "实现", code,
                            "对 double 数组使用 memset 只会按字节写 0，其他值会得到错误结果", "改用 fill(begin, end, value) 赋值"));
                }

                if (usesScanf && code.contains("%d")) {
                    for (String name : longLongs) {
                        if (code.contains("&" + name)) {
                            findings.add(finding(file.name, line, "high", "实现", code,
                                    name + " 声明为 long long，却用 %d 读入", "把格式串改成 %lld，否则只读到低 32 位"));
                        }
                    }
                }

                if (code.contains("==") && doubles.stream().anyMatch(name -> code.matches(".*\\b" + Pattern.quote(name) + "\\s*==.*"))) {
                    findings.add(finding(file.name, line, "medium", "实现", code,
                            "浮点数直接用 == 比较，精度误差会让判断失效", "改成 fabs(a - b) < 1e-9"));
                }

                if (line > 0 && code.contains("endl") && loopDepth[i] > 0) {
                    findings.add(finding(file.name, line, "low", "复杂度", code,
                            "循环内使用 endl 会反复刷新缓冲区，数据量大时明显变慢", "改用 '\\n'"));
                }

                Matcher upper = UPPER_BOUND.matcher(code);
                if (upper.find() && code.contains("[") && !code.contains("<= 0")) {
                    findings.add(finding(file.name, line, "low", "边界", code,
                            "循环上界使用 <= " + upper.group(1) + "，同时用循环变量访问数组，注意越界",
                            "确认数组按 0..n-1 存储时，循环应写成 < " + upper.group(1)));
                }

                Matcher size = ARRAY_SIZE.matcher(code);
                if (size.find() && !reportedSize) {
                    int value = Integer.parseInt(size.group(1));
                    if (value < 1000) {
                        reportedSize = true;
                        findings.add(finding(file.name, line, "medium", "边界", code,
                                "数组常量上限只有 " + value + "，若题目 n 更大就会越界",
                                "按题目数据范围放大到 n + 5，或用 vector 动态开"));
                    }
                }

                if (code.matches(".*\\bint\\s+[A-Za-z_]\\w*\\s*=\\s*0\\s*;.*") && code.contains("+=")) {
                    findings.add(finding(file.name, line, "medium", "实现", code,
                            "累加变量声明为 int，n 较大时可能溢出", "求和改用 long long"));
                }
            }

            int maxDepth = Arrays.stream(loopDepth).max().orElse(0);
            if (maxDepth >= 2) {
                int at = 0;
                for (int i = 0; i < loopDepth.length; i++) if (loopDepth[i] == maxDepth) { at = i + 1; break; }
                findings.add(finding(file.name, at, maxDepth >= 3 ? "medium" : "low", "复杂度", lines[at - 1].trim(),
                        "最深层循环嵌套 " + maxDepth + " 层，n 达到 1e5 时约 " + "O(n^" + maxDepth + ")",
                        maxDepth >= 3 ? "检查是否可以拆解或用前缀和、单调栈等降到 O(n log n)" : "确认 n 的实际量级与常数，必要时加剪枝"));
            }
            if (!fastIo && usesCin && !usesScanf) {
                findings.add(finding(file.name, 1, "low", "复杂度", "使用 cin 未关闭同步",
                        "输入量较大时 cin 会明显变慢", "在 main 开头加 ios::sync_with_stdio(false); cin.tie(nullptr);"));
            }
            if (file.content.contains("bits/stdc++.h")) {
                findings.add(finding(file.name, lineOf(file.content, "#include <bits/stdc++.h>"), "low", "实现",
                        "#include <bits/stdc++.h>", "非标准头文件，MSVC 等编译器不提供", "课程验收若需跨编译器，改成显式头文件"));
            }
        }
        findings.sort(Comparator.comparingInt((Finding item) -> severityRank(item.severity)).reversed()
                .thenComparingInt(item -> item.line));
        return findings.stream().limit(40).toList();
    }

    private int severityRank(String severity) {
        return switch (severity) {
            case "high" -> 3;
            case "medium" -> 2;
            default -> 1;
        };
    }

    private Finding finding(String file, int line, String severity, String kind, String code, String message, String suggestion) {
        Finding item = new Finding();
        item.file = file;
        item.line = Math.max(1, line);
        item.severity = severity;
        item.kind = kind;
        item.message = message;
        item.suggestion = suggestion;
        String flat = code.replaceAll("\\s+", " ").trim();
        item.excerpt = flat.length() > 120 ? flat.substring(0, 120) + "…" : flat;
        return item;
    }

    private int[] loopDepth(String[] lines) {
        int[] depth = new int[lines.length];
        Deque<Boolean> stack = new ArrayDeque<>();
        boolean pendingLoop = false;
        for (int i = 0; i < lines.length; i++) {
            String line = strip(lines[i]);
            if (line.matches(".*\\b(for|while)\\s*\\(.*")) pendingLoop = true;
            for (char ch : line.toCharArray()) {
                if (ch == '{') {
                    stack.push(pendingLoop);
                    pendingLoop = false;
                } else if (ch == '}' && !stack.isEmpty()) {
                    stack.pop();
                }
            }
            depth[i] = (int) stack.stream().filter(Boolean::booleanValue).count();
        }
        return depth;
    }

    // ------------------------------------------------------------ 工具

    private List<SourceFile> clean(List<SourceFile> files) {
        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先生成并确认要审查的代码片段");
        }
        if (files.size() > MAX_FILES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次最多审查 " + MAX_FILES + " 个文件");
        }
        List<SourceFile> result = new ArrayList<>();
        int total = 0;
        for (SourceFile file : files) {
            if (file == null || file.content == null || file.content.isBlank()) continue;
            String name = file.name == null || file.name.isBlank() ? "main.cpp" : file.name;
            if (name.length() > 120) name = name.substring(name.length() - 120);
            if (file.content.length() > MAX_FILE_CHARS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 超过 20000 字符，请只提交需要审查的片段");
            }
            total += file.content.length();
            SourceFile item = new SourceFile();
            item.name = name;
            item.content = file.content;
            result.add(item);
        }
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "选中的文件内容为空");
        if (total > MAX_TOTAL_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提交内容超过 60000 字符，请缩小审查范围");
        }
        return result;
    }

    private Set<String> names(String content, Pattern pattern) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private String strip(String line) {
        int at = line.indexOf("//");
        String code = at >= 0 ? line.substring(0, at) : line;
        return code.replaceAll("\\s+$", "");
    }

    private int countLines(String content) {
        return content.split("\n", -1).length;
    }

    private int lineOf(String content, String needle) {
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) if (lines[i].contains(needle)) return i + 1;
        return 1;
    }

    private void count(CodeReview review) {
        if (review.files == null) review.files = new ArrayList<>();
        if (review.findings == null) review.findings = new ArrayList<>();
        review.high = (int) review.findings.stream().filter(item -> "high".equals(item.severity)).count();
        review.medium = (int) review.findings.stream().filter(item -> "medium".equals(item.severity)).count();
        review.low = (int) review.findings.stream().filter(item -> "low".equals(item.severity)).count();
    }

    private String summarize(CodeReview review) {
        if (review.findings.isEmpty()) return "规则检查没有发现明显问题。建议再对照题目数据范围检查一遍边界。";
        String top = review.findings.get(0).message;
        return "共 " + review.findings.size() + " 处提示（高 " + review.high + " / 中 " + review.medium + " / 低 " + review.low
                + "），优先处理：" + top;
    }

    private void persist(long userId, CodeReview review) {
        try {
            db.update("INSERT INTO code_reviews(id,user_id,task_id,problem_id,engine,files,findings,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    review.id, userId, review.taskId, review.problemId, review.engine,
                    json.writeValueAsString(review.files), json.writeValueAsString(review.findings), review.createdAt);
        } catch (Exception ex) {
            throw new IllegalStateException("审查结果保存失败", ex);
        }
    }

    private <T> T decode(String payload, TypeReference<T> type) {
        try { return json.readValue(payload, type); }
        catch (Exception ex) { return null; }
    }
}
