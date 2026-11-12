package cn.xunlian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static cn.xunlian.Models.*;

/** SQLite 里的教练记忆：观察（有出处的判断）与训练事件。 */
@Repository
@DependsOnDatabaseInitialization
public class MemoryStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String upsertSql;

    public MemoryStore(JdbcTemplate db, ObjectMapper json,
                       @Value("${spring.datasource.driver-class-name:org.sqlite.JDBC}") String driver) {
        this.db = db;
        this.json = json;
        this.upsertSql = upsertSql(driver == null || !driver.toLowerCase().contains("mysql"));
    }

    /** SQLite 用 ON CONFLICT，MySQL 用 ON DUPLICATE KEY UPDATE，两者语义一致但语法不通用。 */
    private static String upsertSql(boolean sqlite) {
        String insert = """
                INSERT INTO observations(id,user_id,kind,statement,detail,source_type,source_id,weight,active,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,1,?,?)
                """;
        if (sqlite) {
            return insert + """
                    ON CONFLICT(user_id,kind,statement) DO UPDATE SET
                      weight = observations.weight + excluded.weight,
                      detail = COALESCE(excluded.detail, observations.detail),
                      source_type = excluded.source_type,
                      source_id = excluded.source_id,
                      active = 1,
                      updated_at = excluded.updated_at
                    """;
        }
        return insert + """
                ON DUPLICATE KEY UPDATE
                  weight = weight + VALUES(weight),
                  detail = COALESCE(VALUES(detail), detail),
                  source_type = VALUES(source_type),
                  source_id = VALUES(source_id),
                  active = 1,
                  updated_at = VALUES(updated_at)
                """;
    }

    public List<Observation> list(long userId, boolean activeOnly) {
        String sql = "SELECT * FROM observations WHERE user_id=?" + (activeOnly ? " AND active=1" : "")
                + " ORDER BY active DESC, weight DESC, updated_at DESC";
        return db.query(sql, (rs, row) -> {
            Observation item = new Observation();
            item.id = rs.getString("id");
            item.kind = rs.getString("kind");
            item.statement = rs.getString("statement");
            item.detail = rs.getString("detail");
            item.sourceType = rs.getString("source_type");
            item.sourceId = rs.getString("source_id");
            item.weight = rs.getDouble("weight");
            item.active = rs.getBoolean("active");
            item.createdAt = rs.getString("created_at");
            item.updatedAt = rs.getString("updated_at");
            return item;
        }, userId);
    }

    public Observation upsert(long userId, String kind, String statement, String detail, String sourceType, String sourceId, double weight) {
        String now = Instant.now().toString();
        String text = statement.length() > 400 ? statement.substring(0, 400) : statement;
        db.update(upsertSql, UUID.randomUUID().toString(), userId, kind, text, detail, sourceType, sourceId, weight, now, now);
        return list(userId, false).stream()
                .filter(item -> item.kind.equals(kind) && item.statement.equals(text))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "记忆写入失败"));
    }

    public Observation correct(long userId, String id, String statement, Boolean active) {
        Observation current = list(userId, false).stream()
                .filter(item -> item.id.equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "这条观察已不存在"));
        String text = statement == null || statement.isBlank() ? current.statement
                : (statement.length() > 400 ? statement.substring(0, 400) : statement);
        boolean enabled = active == null ? current.active : active;
        db.update("UPDATE observations SET statement=?,active=?,updated_at=? WHERE id=? AND user_id=?",
                text, enabled ? 1 : 0, Instant.now().toString(), id, userId);
        return list(userId, false).stream().filter(item -> item.id.equals(id)).findFirst().orElseThrow();
    }

    public void remove(long userId, String id) {
        db.update("DELETE FROM observations WHERE id=? AND user_id=?", id, userId);
    }

    public void event(long userId, String type, String summary, Object payload) {
        db.update("INSERT INTO training_events(id,user_id,type,summary,payload,created_at) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(), userId, type,
                summary.length() > 400 ? summary.substring(0, 400) : summary,
                payload == null ? null : encode(payload), Instant.now().toString());
    }

    public List<Event> events(long userId, int limit) {
        return db.query("SELECT * FROM training_events WHERE user_id=? ORDER BY created_at DESC LIMIT ?", (rs, row) -> {
            Event item = new Event();
            item.id = rs.getString("id");
            item.type = rs.getString("type");
            item.summary = rs.getString("summary");
            item.createdAt = rs.getString("created_at");
            return item;
        }, userId, limit);
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { return null; }
    }
}
