CREATE TABLE IF NOT EXISTS accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    demo INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS workspaces (
    user_id INTEGER PRIMARY KEY,
    revision INTEGER NOT NULL DEFAULT 0,
    payload TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES accounts(id)
);
CREATE TABLE IF NOT EXISTS problems (
    id TEXT PRIMARY KEY,
    payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS cf_submissions (
    user_id INTEGER NOT NULL,
    handle TEXT NOT NULL,
    submission_id INTEGER NOT NULL,
    problem_id TEXT NOT NULL,
    verdict TEXT NOT NULL,
    submitted_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, submission_id)
);
CREATE INDEX IF NOT EXISTS idx_submissions_solved ON cf_submissions(user_id, problem_id, verdict);

-- 教练记忆：一条观察 = 一个有出处的判断，用户可更正或停用。
CREATE TABLE IF NOT EXISTS observations (
    id TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    kind TEXT NOT NULL,
    statement TEXT NOT NULL,
    detail TEXT,
    source_type TEXT NOT NULL,
    source_id TEXT,
    weight REAL NOT NULL DEFAULT 1,
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_observations_key ON observations(user_id, kind, statement);
CREATE INDEX IF NOT EXISTS idx_observations_user ON observations(user_id, active, updated_at);

-- 训练事件：计划生成、复盘、调整、代码审查等动作留痕，供成长记录回溯。
CREATE TABLE IF NOT EXISTS training_events (
    id TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    type TEXT NOT NULL,
    summary TEXT NOT NULL,
    payload TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_user ON training_events(user_id, created_at);

-- 代码审查只保存文件名、行数与结论，不保存源码。
CREATE TABLE IF NOT EXISTS code_reviews (
    id TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    task_id TEXT,
    problem_id TEXT,
    engine TEXT NOT NULL,
    files TEXT NOT NULL,
    findings TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reviews_user ON code_reviews(user_id, created_at);

CREATE TABLE IF NOT EXISTS code_workspaces (
    user_id BIGINT PRIMARY KEY,
    id VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL,
    payload TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES accounts(id)
);
