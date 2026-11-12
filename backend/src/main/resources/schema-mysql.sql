-- MySQL 版本结构，与 schema.sql 等价。切换时设置：
-- DB_URL=jdbc:mysql://127.0.0.1:3306/xunlian?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
-- DB_DRIVER=com.mysql.cj.jdbc.Driver  DB_SCHEMA=classpath:schema-mysql.sql
CREATE TABLE IF NOT EXISTS accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(40) NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    demo BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE IF NOT EXISTS workspaces (
    user_id BIGINT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    payload LONGTEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES accounts(id)
);
CREATE TABLE IF NOT EXISTS problems (
    id VARCHAR(40) PRIMARY KEY,
    payload LONGTEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS cf_submissions (
    user_id BIGINT NOT NULL,
    handle VARCHAR(40) NOT NULL,
    submission_id BIGINT NOT NULL,
    problem_id VARCHAR(40) NOT NULL,
    verdict VARCHAR(60) NOT NULL,
    submitted_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, submission_id),
    INDEX idx_submissions_solved (user_id, problem_id, verdict)
);
CREATE TABLE IF NOT EXISTS observations (
    id VARCHAR(40) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    kind VARCHAR(40) NOT NULL,
    statement VARCHAR(400) NOT NULL,
    detail VARCHAR(1000),
    source_type VARCHAR(40) NOT NULL,
    source_id VARCHAR(60),
    weight DOUBLE NOT NULL DEFAULT 1,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    UNIQUE KEY idx_observations_key (user_id, kind, statement),
    INDEX idx_observations_user (user_id, active, updated_at)
);
CREATE TABLE IF NOT EXISTS training_events (
    id VARCHAR(40) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    summary VARCHAR(400) NOT NULL,
    payload LONGTEXT,
    created_at VARCHAR(40) NOT NULL,
    INDEX idx_events_user (user_id, created_at)
);
CREATE TABLE IF NOT EXISTS code_reviews (
    id VARCHAR(40) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    task_id VARCHAR(40),
    problem_id VARCHAR(40),
    engine VARCHAR(20) NOT NULL,
    files LONGTEXT NOT NULL,
    findings LONGTEXT NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    INDEX idx_reviews_user (user_id, created_at)
);

CREATE TABLE IF NOT EXISTS code_workspaces (
    user_id BIGINT PRIMARY KEY,
    id VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL,
    payload LONGTEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES accounts(id)
);
