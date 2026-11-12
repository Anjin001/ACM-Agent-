package cn.xunlian;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.*;

import static cn.xunlian.Models.*;

@Repository
@DependsOnDatabaseInitialization
public class CoachStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    public CoachStore(JdbcTemplate db, ObjectMapper json, TransactionTemplate tx) {
        this.db = db;
        this.json = json;
        this.tx = tx;
    }

    @PostConstruct
    void initialize() throws Exception {
        List<Problem> seed = json.readValue(new ClassPathResource("catalog.json").getInputStream(), new TypeReference<>() {});
        saveProblems(seed);
        if (accountByName("demo") == null) {
            createAccount("demo", UUID.randomUUID().toString(), true);
        }
    }

    public Account accountByName(String username) {
        return db.query("SELECT * FROM accounts WHERE username=?", (rs, row) ->
                new Account(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getBoolean("demo")), username)
                .stream().findFirst().orElse(null);
    }

    public Account account(long id) {
        return db.query("SELECT * FROM accounts WHERE id=?", (rs, row) ->
                new Account(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getBoolean("demo")), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录"));
    }

    public synchronized Account createAccount(String username, String password, boolean demo) {
        try {
            return tx.execute(status -> {
                db.update("INSERT INTO accounts(username,password_hash,demo) VALUES(?,?,?)", username, passwords.encode(password), demo);
                Account account = accountByName(username);
                Workspace state = demo ? demoWorkspace() : new Workspace();
                db.update("INSERT INTO workspaces(user_id,revision,payload) VALUES(?,0,?)", account.id(), encode(state));
                return account;
            });
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已被使用");
        } catch (org.springframework.dao.DataAccessException ex) {
            // SQLite 的唯一约束不一定被翻译成 DuplicateKeyException，这里兜住同一语义。
            if (String.valueOf(ex.getMessage()).contains("UNIQUE")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已被使用");
            }
            throw ex;
        }
    }

    public boolean verifyPassword(Account account, String password) {
        return !account.demo() && passwords.matches(password, account.passwordHash());
    }

    public Snapshot snapshot(long userId) {
        return db.queryForObject("SELECT revision,payload FROM workspaces WHERE user_id=?", (rs, row) ->
                new Snapshot(rs.getLong("revision"), decode(rs.getString("payload"), Workspace.class)), userId);
    }

    public void save(long userId, long revision, Workspace workspace) {
        int count = db.update("UPDATE workspaces SET payload=?,revision=revision+1 WHERE user_id=? AND revision=?", encode(workspace), userId, revision);
        if (count != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "数据已有更新，请刷新后重试");
    }

    public synchronized void saveProblems(List<Problem> problems) {
        Set<String> known = new HashSet<>(db.queryForList("SELECT id FROM problems", String.class));
        List<Problem> fresh = problems.stream().filter(problem -> !known.contains(problem.id)).toList();
        db.batchUpdate("INSERT INTO problems(id,payload) VALUES(?,?)", fresh, 500, (ps, problem) -> {
            ps.setString(1, problem.id);
            ps.setString(2, encode(problem));
        });
    }

    public List<Problem> problems() {
        return db.query("SELECT payload FROM problems", (rs, row) -> decode(rs.getString(1), Problem.class));
    }

    public Set<String> solved(long userId, String handle) {
        return new HashSet<>(db.queryForList("SELECT DISTINCT problem_id FROM cf_submissions WHERE user_id=? AND LOWER(handle)=LOWER(?) AND verdict='OK'", String.class, userId, handle));
    }

    public synchronized void saveSync(long userId, String handle, List<Submission> submissions, SyncInfo info) {
        tx.executeWithoutResult(status -> {
            Snapshot snapshot = snapshot(userId);
            if (!snapshot.workspace().profile.handle.equalsIgnoreCase(handle)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "CF handle 已更改，请重新同步");
            }
            Set<Long> known = new HashSet<>(db.queryForList("SELECT submission_id FROM cf_submissions WHERE user_id=?", Long.class, userId));
            List<Submission> fresh = submissions.stream().filter(submission -> !known.contains(submission.id())).toList();
            List<Submission> updates = submissions.stream().filter(submission -> known.contains(submission.id())).toList();
            // Merge the fetched window, retaining older records and refreshing rejudged verdicts.
            db.batchUpdate("INSERT INTO cf_submissions(user_id,handle,submission_id,problem_id,verdict,submitted_at) VALUES(?,?,?,?,?,?)", fresh, 500, (ps, submission) -> {
                ps.setLong(1, userId);
                ps.setString(2, handle);
                ps.setLong(3, submission.id());
                ps.setString(4, submission.problemId());
                ps.setString(5, submission.verdict());
                ps.setLong(6, submission.createdAt());
            });
            db.batchUpdate("UPDATE cf_submissions SET verdict=?,handle=? WHERE user_id=? AND submission_id=?", updates, 500, (ps, submission) -> {
                ps.setString(1, submission.verdict());
                ps.setString(2, handle);
                ps.setLong(3, userId);
                ps.setLong(4, submission.id());
            });
            Workspace workspace = snapshot.workspace();
            info.submissionCount = db.queryForObject("SELECT COUNT(*) FROM cf_submissions WHERE user_id=? AND LOWER(handle)=LOWER(?)", Integer.class, userId, handle);
            workspace.sync = info;
            Set<String> solved = solved(userId, handle);
            info.solvedCount = solved.size();
            if (workspace.plan != null) {
                for (Task task : workspace.plan.tasks) {
                    if (task.status.equals("todo") && solved.contains(task.problemId)) task.status = "synced";
                    else if (task.status.equals("synced") && !solved.contains(task.problemId)) task.status = "todo";
                }
            }
            workspace.proposal = null;
            save(userId, snapshot.revision(), workspace);
        });
    }

    public Set<String> excluded(long userId, Workspace workspace) {
        Set<String> result = solved(userId, workspace.profile.handle);
        for (Feedback feedback : workspace.feedback) {
            if (!feedback.outcome.equals("stuck")) result.add(feedback.problemId);
        }
        return result;
    }

    public <T> T copy(T value, Class<T> type) { return decode(encode(value), type); }

    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("无法保存数据", ex); }
    }

    private <T> T decode(String payload, Class<T> type) {
        try { return json.readValue(payload, type); }
        catch (Exception ex) { throw new IllegalStateException("无法读取数据", ex); }
    }

    private Workspace demoWorkspace() {
        Workspace workspace = new Workspace();
        workspace.profile.name = "演示学员";
        workspace.profile.level = 1200;
        workspace.profile.goal = "巩固二分与搜索，独立完成 1200 分左右的题目";
        LocalDate monday = LocalDate.now(ZoneId.of("Asia/Shanghai")).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Plan plan = new Plan();
        plan.weekStart = monday.toString();
        plan.dailyMinutes = new ArrayList<>(workspace.profile.dailyMinutes);
        plan.explanation = "本周先巩固二分判定，再练习搜索中的状态与边界。预留时间用于复盘。";
        String[] ids = {"CF-706-B", "LG-P2249", "CF-474-B", "LG-P1162", "CF-279-B", "LG-P1025"};
        int[] minutes = {30, 40, 40, 45, 50, 60};
        for (int i = 0; i < ids.length; i++) {
            Task task = new Task();
            task.id = UUID.randomUUID().toString();
            task.problemId = ids[i];
            task.day = i;
            task.minutes = minutes[i];
            task.reason = i < 3 || i == 4 ? "练习有序序列中的边界判断，巩固二分思路。" : "在搜索练习中关注状态表示与结束条件。";
            if (i < 2) task.status = i == 0 ? "independent" : "editorial";
            plan.tasks.add(task);
            if (i < 2) {
                Feedback feedback = new Feedback();
                feedback.id = UUID.randomUUID().toString();
                feedback.taskId = task.id;
                feedback.problemId = task.problemId;
                feedback.minutes = i == 0 ? 32 : 55;
                feedback.outcome = task.status;
                feedback.note = i == 0 ? "独立完成，注意 upper_bound 的边界。" : "参考题解才理清边界，想再做一道同类题。";
                feedback.causes = Nlp.causes(feedback.note).stream().map(Nlp.Cause::tag).toList();
                feedback.createdAt = monday.plusDays(i).atTime(20, 30).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toString();
                workspace.feedback.add(feedback);
            }
        }
        workspace.plan = plan;
        return workspace;
    }
}
