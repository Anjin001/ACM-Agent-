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
    private static final Set<String> MEMORY_KINDS = Set.of("goal", "error_pattern", "code_issue", "strength", "constraint");

    private final CoachStore store;
    private final Planner planner;
    private final AiCoach ai;
    private final LocalAiSettings aiSettings;
    private final CodeforcesClient cf;
    private final CoachMemory memory;
    private final MemoryStore memoryStore;
    private final ProfileModeler modeler;
    private final CodeReviewer reviewer;
    private final CodeWorkspaceService codeWorkspaces;
    private final LocalCodeFiles localFiles;
    private final AssessmentService assessments;

    public CoachController(CoachStore store, Planner planner, AiCoach ai, CodeforcesClient cf,
                           CoachMemory memory, MemoryStore memoryStore, ProfileModeler modeler, CodeReviewer reviewer,
                           LocalAiSettings aiSettings, CodeWorkspaceService codeWorkspaces, LocalCodeFiles localFiles,
                           AssessmentService assessments) {
        this.store = store;
        this.planner = planner;
        this.ai = ai;
        this.aiSettings = aiSettings;
        this.cf = cf;
        this.memory = memory;
        this.memoryStore = memoryStore;
        this.modeler = modeler;
        this.reviewer = reviewer;
        this.codeWorkspaces = codeWorkspaces;
        this.localFiles = localFiles;
        this.assessments = assessments;
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("status", "ok", "version", "0.2.0"); }

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
        LocalAiSettings.Status modelSettings = aiSettings.status(account);
        return Map.of("revision", snapshot.revision(), "workspace", snapshot.workspace(), "username", account.username(), "demo", account.demo(),
                "problems", store.problems(), "solvedIds", store.excluded(id, snapshot.workspace()), "aiConfigured", ai.configured(modelSettings),
                "aiSettings", modelSettings,
                "syncJob", cf.status(id));
    }

    @PutMapping("/settings/ai")
    public Map<String, Object> saveAiSettings(@RequestBody LocalAiSettings.Update update, HttpServletRequest request) {
        Account account = localAiAccount(request);
        aiSettings.save(account, update);
        return state(request.getSession());
    }

    @DeleteMapping("/settings/ai")
    public Map<String, Object> clearAiSettings(HttpServletRequest request) {
        aiSettings.clear(localAiAccount(request));
        return state(request.getSession());
    }

    private Account localAiAccount(HttpServletRequest request) {
        Account account = store.account(userId(request.getSession()));
        String address = request.getRemoteAddr();
        if (account.demo() || !Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(address)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请从本机登录个人账号后使用此功能");
        }
        return account;
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
        profile.achievements = Objects.toString(profile.achievements, "").strip();
        if (profile.achievements.length() > 800) bad("荣誉和参赛经历最多 800 个字符");
        profile.competitionDate = Objects.toString(profile.competitionDate, "").strip();
        if (!profile.competitionDate.isEmpty()) {
            try {
                if (!profile.competitionDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) bad("请输入有效的比赛日期");
                java.time.LocalDate.parse(profile.competitionDate);
            } catch (java.time.format.DateTimeParseException ex) {
                bad("请输入有效的比赛日期");
            }
        }
        snapshot.workspace().profile = profile;
        snapshot.workspace().proposal = null;
        store.save(id, snapshot.revision(), snapshot.workspace());
        memory.observeGoal(id, profile);
        memory.event(id, "profile", "更新训练目标与时间预算");
        return state(session);
    }

    @PostMapping("/plan/generate")
    public Map<String, Object> generate(@RequestBody RevisionRequest body, HttpSession session) {
        long id = userId(session);
        Snapshot snapshot = checked(id, body.revision());
        Workspace workspace = snapshot.workspace();
        Plan plan = planner.generate(id, workspace, "", memory.activeStatements(id, 3));
        archive(workspace);
        workspace.plan = plan;
        workspace.proposal = null;
        store.save(id, snapshot.revision(), workspace);
        memory.event(id, "plan", "生成本周计划 v" + plan.version + "，共 " + plan.tasks.size() + " 个任务");
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
        Task task = workspace.plan.tasks.stream().filter(item -> item.id.equals(body.taskId())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "训练任务已变化，请刷新"));
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

        Problem problem = store.problems().stream().filter(item -> item.id.equals(feedback.problemId)).findFirst().orElse(null);
        feedback.causes = memory.observeFeedback(id, feedback, problem);
        memory.event(id, "feedback", "记录复盘：" + (problem == null ? feedback.problemId : problem.code) + "（" + outcomeLabel(body.outcome()) + "）");
        return state(session);
    }

    @PostMapping("/plan/proposal")
    public Map<String, Object> propose(@RequestBody AdjustRequest body, HttpSession session) {
        if (body.instruction() == null || body.instruction().length() > 1500) bad("调整要求最多 1500 个字符");
        long id = userId(session);
        Snapshot snapshot = checked(id, body.revision());
        Workspace workspace = snapshot.workspace();
        if (workspace.plan == null) bad("请先生成训练计划");
        List<String> statements = memory.activeStatements(id, 6);
        Proposal proposal = new Proposal();
        proposal.id = UUID.randomUUID().toString();
        proposal.baseVersion = workspace.plan.version;
        proposal.plan = planner.generate(id, workspace, body.instruction(), statements);
        proposal.reason = proposal.plan.explanation;
        proposal.basis = new ArrayList<>(proposal.plan.basis);
        ProfileModel model = modeler.build(workspace, store.problems());
        ai.refine(store.account(id), proposal, workspace, planner.candidates(id, workspace), body.instruction(), statements, model);
        if (proposal.basis == null || proposal.basis.isEmpty()) proposal.basis = new ArrayList<>(statements.stream().limit(2).toList());
        workspace.proposal = proposal;
        store.save(id, snapshot.revision(), workspace);
        memory.event(id, "proposal", "生成调整方案（" + ("model".equals(proposal.engine) ? "模型" : "规则") + "）");
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
        memory.event(id, "accept", "接受调整方案，计划更新到 v" + proposal.plan.version);
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
        memory.event(id, "undo", "撤销上次计划修改，保留新增复盘");
        return state(session);
    }

    @PostMapping("/sync")
    public CodeforcesClient.Job sync(HttpSession session) {
        long id = userId(session);
        String handle = store.snapshot(id).workspace().profile.handle;
        if (handle.isBlank()) bad("请先在个人设置中填写 CF handle");
        memory.event(id, "sync", "开始同步 CF 公开提交记录");
        return cf.start(id, handle);
    }

    @GetMapping("/sync")
    public CodeforcesClient.Job syncStatus(HttpSession session) { return cf.status(userId(session)); }

    // ---------------------------------------------------------------- 能力画像

    @GetMapping("/profile/model")
    public ProfileModel profileModel(HttpSession session) {
        long id = userId(session);
        return modeler.build(store.snapshot(id).workspace(), store.problems());
    }

    // ---------------------------------------------------------------- 教练记忆

    @GetMapping("/memory")
    public Map<String, Object> memory(HttpSession session) {
        long id = userId(session);
        return Map.of("observations", memoryStore.list(id, false), "events", memoryStore.events(id, 12));
    }

    @PostMapping("/memory")
    public Map<String, Object> addMemory(@RequestBody MemoryRequest body, HttpSession session) {
        long id = userId(session);
        if (body.statement() == null || body.statement().isBlank() || body.statement().length() > 200) bad("观察内容需要 1～200 个字符");
        String kind = body.kind() != null && MEMORY_KINDS.contains(body.kind()) ? body.kind() : "goal";
        Observation item = memoryStore.upsert(id, kind, body.statement().trim(), body.detail(), "manual", null, 1.0);
        memory.event(id, "memory", "手动补充观察：" + item.statement);
        return Map.of("observations", memoryStore.list(id, false), "events", memoryStore.events(id, 12));
    }

    @PatchMapping("/memory/{id}")
    public Map<String, Object> correctMemory(@PathVariable String id, @RequestBody MemoryCorrection body, HttpSession session) {
        long userId = userId(session);
        if (body.statement() != null && body.statement().length() > 200) bad("观察内容最多 200 个字符");
        Observation item = memoryStore.correct(userId, id, body.statement(), body.active());
        memory.event(userId, "memory", (Boolean.FALSE.equals(body.active()) ? "停用观察：" : "更正观察：") + item.statement);
        return Map.of("observations", memoryStore.list(userId, false), "events", memoryStore.events(userId, 12));
    }

    @DeleteMapping("/memory/{id}")
    public Map<String, Object> deleteMemory(@PathVariable String id, HttpSession session) {
        long userId = userId(session);
        memoryStore.remove(userId, id);
        return Map.of("deleted", id);
    }

    // ---------------------------------------------------------------- 代码审查

    @GetMapping("/assessment")
    public AssessmentService.State assessment(HttpSession session) {
        return assessments.state(codeWorkspaceAccount(session).id());
    }

    @PostMapping("/assessment")
    public AssessmentService.State startAssessment(HttpSession session) {
        return assessments.start(codeWorkspaceAccount(session).id());
    }

    @PostMapping("/assessment/{id}/end")
    public AssessmentService.State endAssessment(@PathVariable String id, HttpSession session) {
        return assessments.end(codeWorkspaceAccount(session).id(), id);
    }

    @PostMapping("/assessment/{id}/settle")
    public AssessmentService.State settleAssessment(@PathVariable String id, HttpSession session) {
        return assessments.settle(codeWorkspaceAccount(session).id(), id);
    }

    @PostMapping("/code/local")
    public LocalCodeFiles.FolderInfo openLocalCode(@RequestBody LocalCodeFiles.OpenFolder body, HttpServletRequest request) {
        return localFiles.open(localAiAccount(request).id(), body.path());
    }

    @GetMapping("/code/local")
    public Map<String, Object> currentLocalCode(HttpServletRequest request) {
        return Collections.singletonMap("folder", localFiles.current(localAiAccount(request).id()));
    }

    @GetMapping("/code/local/search")
    public List<LocalCodeFiles.Hit> searchLocalCode(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "false") boolean content, @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return localFiles.search(localAiAccount(request).id(), q, content, offset);
    }

    @GetMapping("/code/local/file")
    public LocalCodeFiles.Source viewLocalCode(@RequestParam String file, @RequestParam(defaultValue = "1") int line,
            HttpServletRequest request) {
        return localFiles.view(localAiAccount(request).id(), file, line);
    }

    @PostMapping("/code/local/scan")
    public LocalCodeFiles.JobState scanLocalCode(@RequestParam(defaultValue = "false") boolean useModel,
            HttpServletRequest request) {
        return localFiles.start(localAiAccount(request), useModel);
    }

    @GetMapping("/code/local/scan")
    public LocalCodeFiles.JobState localCodeScanStatus(HttpServletRequest request) {
        return localFiles.status(localAiAccount(request).id());
    }

    @GetMapping("/code/workspace")
    public Map<String, Object> codeWorkspace(HttpSession session) {
        return Collections.singletonMap("report", codeWorkspaces.current(codeWorkspaceAccount(session).id()));
    }

    @PostMapping("/code/workspace")
    public CodeWorkspaceService.Report analyzeWorkspace(@RequestBody CodeWorkspaceService.Analyze body, HttpSession session) {
        return codeWorkspaces.analyze(codeWorkspaceAccount(session), body);
    }

    @PostMapping("/code/workspace/scans")
    public CodeWorkspaceService.ScanProgress startCodeScan(@RequestBody CodeWorkspaceService.StartScan body, HttpSession session) {
        return codeWorkspaces.startFolderScan(codeWorkspaceAccount(session).id(), body);
    }

    @PostMapping("/code/workspace/scans/{id}/batches")
    public CodeWorkspaceService.ScanProgress scanCodeBatch(@PathVariable String id, @RequestBody CodeWorkspaceService.ScanBatch body, HttpSession session) {
        return codeWorkspaces.scanBatch(codeWorkspaceAccount(session).id(), id, body);
    }

    @PostMapping("/code/workspace/scans/{id}/finish")
    public CodeWorkspaceService.Report finishCodeScan(@PathVariable String id, HttpSession session) {
        return codeWorkspaces.finishFolderScan(codeWorkspaceAccount(session), id);
    }

    @DeleteMapping("/code/workspace/scans/{id}")
    public void cancelCodeScan(@PathVariable String id, HttpSession session) {
        codeWorkspaces.cancelFolderScan(codeWorkspaceAccount(session).id(), id);
    }

    @PostMapping("/code/workspace/chat")
    public CodeWorkspaceService.Report chatAboutWorkspace(@RequestBody CodeWorkspaceService.Ask body, HttpSession session) {
        return codeWorkspaces.ask(codeWorkspaceAccount(session), body);
    }

    @DeleteMapping("/code/workspace/{id}")
    public Map<String, Object> clearCodeWorkspace(@PathVariable String id, @RequestParam long revision, HttpSession session) {
        codeWorkspaces.clear(codeWorkspaceAccount(session).id(), id, revision);
        return Collections.singletonMap("report", null);
    }

    private Account codeWorkspaceAccount(HttpSession session) {
        Account account = store.account(userId(session));
        if (account.demo()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请登录个人账号后使用代码工作区");
        return account;
    }

    @PostMapping("/code/review")
    public CodeReview review(@RequestBody CodeReviewRequest body, HttpSession session) {
        return reviewer.review(userId(session), body);
    }

    @GetMapping("/code/review")
    public List<CodeReview> reviews(@RequestParam(defaultValue = "6") int limit, HttpSession session) {
        return reviewer.history(userId(session), Math.max(1, Math.min(20, limit)));
    }

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
        Workspace workspace = store.snapshot(account.id()).workspace();
        memory.observeGoal(account.id(), workspace.profile);
        // 演示空间首次进入时，用示例复盘补上观察，方便课堂直接看到记忆与引用效果。
        if (account.demo() && memory.active(account.id()).stream().noneMatch(item -> "error_pattern".equals(item.kind))) {
            for (Feedback item : workspace.feedback) {
                Problem problem = store.problems().stream().filter(problem2 -> problem2.id.equals(item.problemId)).findFirst().orElse(null);
                memory.observeFeedback(account.id(), item, problem);
                memory.event(account.id(), "feedback", "记录复盘：" + (problem == null ? item.problemId : problem.code) + "（" + outcomeLabel(item.outcome) + "）");
            }
        }
    }

    /** 与前端复盘列表保持同一套说法，事件时间线里不出现原始枚举值。 */
    private static String outcomeLabel(String outcome) {
        if ("independent".equals(outcome)) return "独立完成";
        if ("editorial".equals(outcome)) return "参考题解";
        return "仍在探索";
    }

    private void validateCredentials(Credentials credentials) {
        if (credentials.username() == null || !credentials.username().matches("[A-Za-z0-9_]{3,24}")) bad("用户名需为 3～24 位字母、数字或下划线");
        if (credentials.password() == null || credentials.password().length() < 8 || credentials.password().getBytes(StandardCharsets.UTF_8).length > 72) bad("密码至少 8 个字符，最多 72 字节");
    }

    private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
