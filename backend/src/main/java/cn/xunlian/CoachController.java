package cn.xunlian;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static cn.xunlian.Models.*;

@RestController
@RequestMapping("/api")
public class CoachController {
    private static final Set<String> TOPICS = Set.of("二分", "搜索", "动态规划", "贪心", "图论", "数学", "字符串", "数据结构", "双指针", "模拟");
    private final CoachStore store;
    private final Planner planner;
    private final AiCoach ai;
    private final CodeforcesClient cf;

    public CoachController(CoachStore store, Planner planner, AiCoach ai, CodeforcesClient cf) {
        this.store = store; this.planner = planner; this.ai = ai; this.cf = cf;
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("status", "ok", "version", "0.1.0"); }

    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap(HttpServletRequest request) {
        if (!(request.getSession().getAttribute("userId") instanceof Long)) authenticate(request, store.accountByName("demo"));
        return state(request.getSession());
    }

    @GetMapping("/state")
    public Map<String, Object> state(HttpSession session) {
        long id = userId(session);
        Account account = store.account(id);
        Snapshot snapshot = store.snapshot(id);
        return Map.of("revision", snapshot.revision(), "workspace", snapshot.workspace(), "username", account.username(), "demo", account.demo(),
                "problems", store.problems(), "solvedIds", store.excluded(id, snapshot.workspace()), "aiConfigured", ai.configured(), "syncJob", cf.status(id));
    }

    @PostMapping("/auth/demo")
    public Map<String, Object> demo(HttpServletRequest request) {
        authenticate(request, store.accountByName("demo"));
        return state(request.getSession());
    }

    @PostMapping("/auth/register")
    public Map<String, Object> register(@RequestBody Credentials credentials, HttpServletRequest request) {
        validateCredentials(credentials);
        if (credentials.username().equalsIgnoreCase("demo")) bad("请使用其他用户名");
        authenticate(request, store.createAccount(credentials.username(), credentials.password(), false));
        return state(request.getSession());
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Credentials credentials, HttpServletRequest request) {
        validateCredentials(credentials);
        Account account = store.accountByName(credentials.username());
        if (account == null || !store.verifyPassword(account, credentials.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码有误");
        }
        authenticate(request, account);
        return state(request.getSession());
    }

    @PutMapping("/profile")
    public Map<String, Object> profile(@RequestBody ProfileRequest body, HttpSession session) {
        Profile profile = body.profile();
        if (profile == null || profile.name == null || profile.name.isBlank() || profile.name.length() > 30) bad("昵称需要 1～30 个字符");
        if (profile.handle == null || !profile.handle.matches("[A-Za-z0-9_.-]{0,24}")) bad("请填写有效的 CF handle");
        if (profile.goal == null || profile.goal.length() > 300) bad("训练目标最多 300 个字符");
        if (profile.topics == null || profile.topics.isEmpty() || profile.topics.size() > 10 || !TOPICS.containsAll(profile.topics)) bad("请至少选择一个有效训练专题");
        if (profile.level < 800 || profile.level > 1600) bad("首版训练起点需在 800～1600 之间");
        if (profile.dailyMinutes == null || profile.dailyMinutes.size() != 7 || profile.dailyMinutes.stream().anyMatch(minutes -> minutes == null || minutes < 0 || minutes > 480)) bad("每天可用时间需在 0～480 分钟之间");
        long id = userId(session);
        Snapshot snapshot = checked(id, body.revision());
        snapshot.workspace().profile = profile;
        snapshot.workspace().proposal = null;
        store.save(id, snapshot.revision(), snapshot.workspace());
        return state(session);
    }

    @PostMapping("/plan/generate")
    public Map<String, Object> generate(@RequestBody RevisionRequest body, HttpSession session) {
        long id = userId(session);
        Snapshot snapshot = checked(id, body.revision());
        Workspace workspace = snapshot.workspace();
        Plan plan = planner.generate(id, workspace, "");
        archive(workspace);
        workspace.plan = plan;
        workspace.proposal = null;
        store.save(id, snapshot.revision(), workspace);
        return state(session);
    }

    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestBody FeedbackRequest body, HttpSession session) {
        if (!Set.of("independent", "editorial", "stuck").contains(Objects.toString(body.outcome(), ""))) bad("请选择完成情况");
        if (body.minutes() < 1 || body.minutes() > 600) bad("训练用时需在 1～600 分钟之间");
        if (body.note() == null || body.note().length() > 1500) bad("复盘最多 1500 个字符");
        long id = userId(session);
        Snapshot snapshot = checked(id, body.revision());
        Workspace workspace = snapshot.workspace();
        if (workspace.plan == null) bad("请先生成训练计划");
        Task task = workspace.plan.tasks.stream().filter(item -> item.id.equals(body.taskId())).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "训练任务已变化，请刷新"));
        Feedback feedback = new Feedback();
        feedback.id = UUID.randomUUID().toString();
        feedback.taskId = task.id;
        feedback.problemId = task.problemId;
        feedback.outcome = body.outcome();
        feedback.minutes = body.minutes();
        feedback.note = body.note().trim();
        feedback.createdAt = Instant.now().toString();
        workspace.feedback.removeIf(item -> item.taskId.equals(task.id) && item.problemId.equals(task.problemId));
        workspace.feedback.add(feedback);
        task.status = body.outcome();
        workspace.proposal = null;
        store.save(id, snapshot.revision(), workspace);
        return state(session);
    }

    @PostMapping("/plan/proposal")
    public Map<String, Object> propose(@RequestBody AdjustRequest body, HttpSession session) {
        if (body.instruction() == null || body.instruction().length() > 1500) bad("调整要求最多 1500 个字符");
        long id = userId(session);
        Snapshot snapshot = checked(id, body.revision());
        Workspace workspace = snapshot.workspace();
        if (workspace.plan == null) bad("请先生成训练计划");
        Proposal proposal = new Proposal();
        proposal.id = UUID.randomUUID().toString();
        proposal.baseVersion = workspace.plan.version;
        proposal.plan = planner.generate(id, workspace, body.instruction());
        proposal.reason = proposal.plan.explanation;
        ai.refine(proposal, workspace, planner.candidates(id, workspace), body.instruction());
        workspace.proposal = proposal;
        store.save(id, snapshot.revision(), workspace);
        return state(session);
    }

    @PostMapping("/plan/accept")
    public Map<String, Object> accept(@RequestBody AcceptRequest body, HttpSession session) {
        long id = userId(session);
        Snapshot snapshot = checked(id, body.revision());
        Workspace workspace = snapshot.workspace();
        Proposal proposal = workspace.proposal;
        if (proposal == null || !proposal.id.equals(body.proposalId()) || proposal.baseVersion != workspace.plan.version) bad("方案已失效，请重新生成");
        archive(workspace);
        workspace.plan = proposal.plan;
        workspace.profile.dailyMinutes = new ArrayList<>(proposal.plan.dailyMinutes);
        workspace.proposal = null;
        store.save(id, snapshot.revision(), workspace);
        return state(session);
    }

    @PostMapping("/plan/undo")
    public Map<String, Object> undo(@RequestBody RevisionRequest body, HttpSession session) {
        long id = userId(session);
        Snapshot snapshot = checked(id, body.revision());
        Workspace workspace = snapshot.workspace();
        if (workspace.history.isEmpty()) bad("暂无可撤销的计划修改");
        Plan previous = workspace.history.remove(workspace.history.size() - 1);
        Map<String, Task> recorded = new LinkedHashMap<>();
        if (workspace.plan != null && previous.weekStart.equals(workspace.plan.weekStart)) {
            workspace.plan.tasks.stream().filter(task -> !task.status.equals("todo")).forEach(task -> recorded.put(task.id, task));
        }
        for (int i = 0; i < previous.tasks.size(); i++) {
            Task current = recorded.remove(previous.tasks.get(i).id);
            if (current != null) previous.tasks.set(i, current);
        }
        previous.tasks.addAll(recorded.values());
        previous.tasks.sort(Comparator.comparingInt(task -> task.day));
        previous.version = workspace.plan.version + 1;
        previous.explanation = "已撤销上次计划修改，并保留之后产生的训练反馈。";
        workspace.plan = previous;
        if (!previous.dailyMinutes.isEmpty()) workspace.profile.dailyMinutes = new ArrayList<>(previous.dailyMinutes);
        workspace.proposal = null;
        store.save(id, snapshot.revision(), workspace);
        return state(session);
    }

    @PostMapping("/sync")
    public CodeforcesClient.Job sync(HttpSession session) {
        long id = userId(session);
        String handle = store.snapshot(id).workspace().profile.handle;
        if (handle.isBlank()) bad("请先在个人设置中填写 CF handle");
        return cf.start(id, handle);
    }

    @GetMapping("/sync")
    public CodeforcesClient.Job syncStatus(HttpSession session) { return cf.status(userId(session)); }

    private void archive(Workspace workspace) {
        if (workspace.plan != null) workspace.history.add(store.copy(workspace.plan, Plan.class));
        if (workspace.history.size() > 15) workspace.history.remove(0);
    }

    private Snapshot checked(long userId, long revision) {
        Snapshot snapshot = store.snapshot(userId);
        if (revision != snapshot.revision()) throw new ResponseStatusException(HttpStatus.CONFLICT, "数据已有更新，请刷新后重试");
        return snapshot;
    }

    private long userId(HttpSession session) {
        Object value = session.getAttribute("userId");
        if (!(value instanceof Long)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请登录或进入演示空间");
        return (Long) value;
    }

    private void authenticate(HttpServletRequest request, Account account) {
        HttpSession session = request.getSession();
        request.changeSessionId();
        session.setAttribute("userId", account.id());
    }

    private void validateCredentials(Credentials credentials) {
        if (credentials.username() == null || !credentials.username().matches("[A-Za-z0-9_]{3,24}")) bad("用户名需为 3～24 位字母、数字或下划线");
        if (credentials.password() == null || credentials.password().length() < 8 || credentials.password().getBytes(StandardCharsets.UTF_8).length > 72) bad("密码至少 8 个字符，最多 72 字节");
    }

    private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
