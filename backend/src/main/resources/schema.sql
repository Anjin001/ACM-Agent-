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
    PRIMARY KEY (user_id, submission_id)
);
