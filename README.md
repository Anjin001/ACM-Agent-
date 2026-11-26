# ACM-Agent- · 循练 ACM 训练工作台

面向软件项目管理课程的可运行首版。围绕训练目标、公开做题记录、周计划、复盘调整与本地代码审查建立完整流程。

## 快速启动

在 Windows 双击根目录的 `启动.bat`，然后访问 [训练工作台](http://127.0.0.1:18080)。首次进入课程演示空间，可以从左下角创建独立账号。

首次构建需要联网，要求 Node.js 22.12+、npm、JDK 17+。脚本会寻找 `JAVA_HOME` 或常见安装目录中的 JDK，优先使用已有 Maven，缺少 Maven 时下载到用户缓存。首次构建会安装前端依赖、构建页面，并运行 15 项后端测试（10 项接口流程、5 项中文自然语言处理）。`frontend/node_modules` 存在且 `package-lock.json` 未变时会跳过依赖安装。

```powershell
# 构建并运行测试
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build.ps1

# 启动已构建的版本，默认仅监听本机
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start.ps1
```

修改源代码后重新执行构建。运行窗口中按 Ctrl+C 停止。数据保存在项目根目录的 `data/xunlian.db`，这是 SQLite 单文件库，同目录下还会出现 `xunlian.db-wal` 与 `xunlian.db-shm` 两个伴随文件。请保留整个 `data/` 目录以继续使用已有账号和计划；删掉它并重新启动会回到初始演示数据。

默认端口为 18080。如该端口已被占用，设置 `$env:XUNLIAN_PORT = '18081'` 后重新执行 `Start.ps1`；脚本会在启动前检查端口并给出占用提示。仅本机访问，需要局域网演示时设置 `$env:SERVER_ADDRESS = '0.0.0.0'`。

## 已实现的流程

1. 默认演示空间包含虚构的学员、训练安排和两条复盘，页面明确标记演示来源。
2. 注册账号或登录后，拥有独立的训练目标、CF handle、时间预算、计划和复盘。密码使用 BCrypt，登录状态使用 HttpOnly 会话 Cookie。
3. 在个人设置保存 CF handle，点击同步。后端通过 CF 官方 API 读取公开信息和提交记录，按两秒以上的间隔请求。
4. 同步最多最近 10000 条提交，页面标明截断范围。结果按提交 ID 去重，并在一次数据库事务中保存。完整同步失败时保留上次成功结果。
5. 生成本周计划，按专题、难度范围和本周剩余日期排期，排除已知通过题与已记录完成题。已有反馈任务保留。
6. 跳转 CF 或洛谷原题练习，在任务中记录独立完成、参考题解或仍然卡住，以及实际用时和文字复盘。
7. 生成调整方案，比较前后题量、时间及具体新任务。接受后保存计划版本，可以撤销，同时保留新增复盘。
8. 题库可搜索、按平台和专题筛选。计划可导出 JSON，方便课堂检查。
9. 中文自然语言直接说时间：`这周六抽半小时，周四没时间` 会解析成具体槽位，界面回显“周六 30 分钟；周四休息”。复盘文字会自动抽取错因（边界、复杂度、审题、实现、算法、时间），写入能力画像与教练记忆。
10. 能力画像按专题给出 1～5 级、样本数、独立完成率、用时偏差和逐条证据；难度分布按 200 分一档统计。
11. 教练记忆存在 SQLite 的 `observations` 表中，每条都带来源（复盘 / 审查 / 设置 / 手动）。用户可以更正、停用或删除；生成调整方案时会引用这些依据，并在方案里列出“依据”清单。
12. 代码审查支持在本机选 C++ 文件、预览行号、勾选要提交的片段。后端先跑规则检查（整数中点溢出、memset 误用、`%d` 读 long long、浮点相等、循环内 endl、数组上界、复杂度层数等），配置模型后再叠加语义审查。源码不入库，只保存文件名、行数与结论。

## AI 配置

默认规则教练可直接使用，界面会显示“规则生成”。规则模式依据保存的复盘确定巩固专题，并支持“周六30分钟”“周四没时间”“每天30分钟”等时间条件。

要启用大模型，在启动服务的终端中设置以下环境变量。使用提供 Chat Completions 兼容接口的服务，密钥只进入后端进程。

```powershell
$env:AI_BASE_URL = 'https://your-provider.example/v1'
$env:AI_API_KEY = 'your-key'
$env:AI_MODEL = 'your-model'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start.ps1
```

模型会收到训练目标、能力画像摘要、教练记忆中的有效观察、近期复盘、时间预算和候选题。后端逐项检查候选 ID、重复题、日期、最少用时和日预算，并校验模型给出的“依据”是否真的出自记忆或画像，编造的引用会被丢弃。超时、接口错误、格式错误或校验失败时展示规则方案及回退状态。代码审查走同一条通道，只发送用户勾选的片段。

## 数据与技术结构

- 前端使用 Vue 3、TypeScript、Vite 与 Lucide 图标。六个页面覆盖概览、周计划、题库、复盘、代码审查与设置。
- 首版面向基础训练，支持 800～1600 的 CF 难度起点，结合人工精选题安排练习。
- 后端使用 Spring Boot 3.5.7、Java 17 API、JdbcTemplate。默认使用 SQLite 单文件库（`data/xunlian.db`），另附 MySQL 结构。
- `accounts` 保存账号，`problems` 保存题目，`cf_submissions` 保存提交记录。`workspaces` 将偏好、计划、反馈与版本作为 JSON 聚合保存，使用 revision 乐观锁防止并发覆盖。
- `observations` 保存教练记忆（目标、易错点、代码问题、优势、约束），`training_events` 保存计划生成、复盘、调整、审查等事件，`code_reviews` 保存审查结论。
- 排期用时按同专题历史「实际用时 / 估计用时」的中位数校准，样本不足时回退到题库估计值。
- 中文处理是自实现的轻量方案：中文数字归一、星期与相对日期槽位、否定与程度修饰，配合正则与词典，不引入分词库。`backend/src/test/java/cn/xunlian/NlpTest.java` 是这部分语料的回归测试。
- CF 题库包含 2026-11-06 从官方 API 获取的 96 道公开元数据快照。18 道洛谷题为人工精选，阶段和用时由项目方整理，原题详情以原平台为准。
- CF 难度与洛谷难度分别保存。训练用时是排期估计，实际用时来自反馈。CF AC 状态单独展示，独立完成情况由本人反馈。
- handle 关联用于查询公开记录，尚未验证账号所有权。演示空间为本机共享示例，独立账号拥有独立工作区。

前端开发时分别启动后端和前端。

```powershell
# 终端一，在 backend 中运行，确保 JAVA_HOME 指向 JDK 17+
mvn spring-boot:run

# 终端二，在 frontend 中运行，Vite 将 /api 代理到 18080
npm ci
npm run dev
```

应用启动后，可以执行接口冒烟检查。每次检查创建独立的验收账号。

```powershell
node scripts/smoke.mjs

# 加上真实 CF 同步及重复同步去重检查
$env:SMOKE_CF = '1'
node scripts/smoke.mjs
```

当前验收证据见 `docs/验收记录.md`。该记录区分已执行检查和后续需要验证的内容。

## 接口一览

全部接口在 `/api` 下，返回 JSON。会改数据的接口都要求带上当前的 `revision`，不一致时返回 409 并提示刷新，避免两个标签页互相覆盖。

| 分组 | 接口 | 说明 |
| --- | --- | --- |
| 会话 | `GET /health` | 返回版本号，用于启动探活 |
| | `POST /auth/demo`、`/auth/register`、`/auth/login` | 登录后下发 HttpOnly 会话 Cookie |
| | `GET /bootstrap`、`GET /state` | 一次取回工作区、题库、已通过题与同步状态 |
| 设置与同步 | `PUT /profile` | 保存目标、handle、每日时间预算 |
| | `POST /sync`、`GET /sync` | 启动 CF 同步任务、查询任务进度 |
| 计划 | `POST /plan/generate` | 按专题与剩余日期生成本周计划 |
| | `POST /plan/proposal` | 按自然语言指令生成调整方案（不落库） |
| | `POST /plan/accept`、`POST /plan/undo` | 应用方案、撤销上一次计划修改 |
| 复盘与画像 | `POST /feedback` | 记录完成情况、用时与文字复盘，同时抽取错因 |
| | `GET /profile/model` | 按专题返回等级、样本、独立完成率、证据与难度分布 |
| 教练记忆 | `GET /memory`、`POST /memory` | 读取观察与事件、手动补充一条观察 |
| | `PATCH /memory/{id}`、`DELETE /memory/{id}` | 更正或停用、删除某条观察 |
| 代码审查 | `POST /code/review` | 提交选中片段，返回问题清单与审查记录 |
| | `GET /code/review` | 读取历史审查记录 |

`POST /memory` 与 `PATCH /memory/{id}` 返回的是整表观察加事件列表，前端拿到后直接覆盖，不需要本地拼装。这一点在 `scripts/smoke.mjs` 与 `CoachFlowTest` 里都有断言守着——早先返回单条时，界面做过一次操作就会把列表清空。

## 数据库

### 默认：SQLite 单文件库

不需要安装数据库服务，也不需要账号密码。首次启动自动建表并写入演示数据，之后的读写都在 `data/xunlian.db` 一个文件里完成。数据层只用 `JdbcTemplate` 与手写 SQL，没有引入 JPA 或 ORM。

连接参数在 `backend/src/main/resources/application.properties`，每一项都可以用环境变量覆盖：

```properties
spring.datasource.url=${DB_URL:jdbc:sqlite:./data/xunlian.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on}
spring.datasource.driver-class-name=${DB_DRIVER:org.sqlite.JDBC}
spring.datasource.hikari.maximum-pool-size=${DB_POOL:4}
spring.sql.init.schema-locations=${DB_SCHEMA:classpath:schema.sql}
```

连接串上三个参数各有用途，缺一项就会在特定场景出问题：

- `journal_mode=WAL`：写事务不再阻塞读。页面一边刷新列表一边点同步时，读请求不会卡住。
- `busy_timeout=5000`：写冲突时最多等待 5 秒再放弃。默认行为是立刻抛 `SQLITE_BUSY`，在“点得快一点”就会撞上。
- `foreign_keys=on`：SQLite 的外键约束默认是关闭的，必须每个连接显式打开。

`DB_POOL` 默认 4 而不是常见的 10：SQLite 同一时刻只允许一个写事务，连接开太多只会把冲突从数据库内部推成线程排队，反而更难排查。

SQLite 不会自动创建目录，所以在全新检出（`data/` 不存在）时直接连库会失败，报 `path to './data/xunlian.db' ... does not exist`。`CoachApplication.main` 在启动 Spring 之前先按 `DB_URL` 解析出文件路径、剥掉 `?` 后面的查询参数、跳过 `:memory:` 与 `file:` 形式，再用 `Files.createDirectories` 把父目录建好。`data/` 已被 `.gitignore` 排除，不会误提交个人数据。

表结构在 `backend/src/main/resources/schema.sql`，全部是 `CREATE TABLE IF NOT EXISTS`，可重复执行。七张表：

| 表 | 内容 |
| --- | --- |
| `accounts` | 账号、BCrypt 密码摘要、是否演示账号 |
| `workspaces` | 训练目标、计划、复盘、方案聚合为 JSON 保存，靠 `revision` 列做乐观锁 |
| `problems` | 题库元数据，CF 与洛谷字段分开存 |
| `cf_submissions` | CF 提交记录，按提交 ID 去重 |
| `observations` | 教练记忆，带出处（`source_type`/`source_id`）与权重 |
| `training_events` | 训练事件时间线 |
| `code_reviews` | 审查结论，只存文件名、行数与字符数，不存源码 |

教练记忆的写入用 upsert 而不是先查后插：

```sql
INSERT INTO observations(...) VALUES(...)
ON CONFLICT(user_id, kind, statement) DO UPDATE SET
  weight = observations.weight + excluded.weight,
  updated_at = excluded.updated_at
```

同一句判断再次出现时累加权重，`(user_id, kind, statement)` 上的唯一索引保证不会留下重复行。这是 SQLite 3.24 起支持的语法；`MemoryStore` 会看驱动名，MySQL 下自动换成语义等价的 `ON DUPLICATE KEY UPDATE`。

需要重置演示数据时，停止服务后删除 `data/` 目录再启动即可。

WAL 模式下最近的写入先落在 `xunlian.db-wal`，主库文件会长时间停留在很小的体积（刚启动时可能只有 4 KB，而 `-wal` 有 1 MB 以上）。所以要备份或换机器，请把三个文件一起拷，或者先停掉服务让 SQLite 做一次 checkpoint，只拷 `xunlian.db` 会丢掉最近的改动。

### 切换到 MySQL

先创建 UTF-8 数据库，再设置连接信息。首次启动自动建表，表结构在 `schema-mysql.sql`，与 SQLite 版字段一致，只是类型和 upsert 写法不同。

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/xunlian?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:DB_DRIVER = 'com.mysql.cj.jdbc.Driver'
$env:DB_SCHEMA = 'classpath:schema-mysql.sql'
$env:DB_USER = 'your-db-user'
$env:DB_PASSWORD = 'your-db-password'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start.ps1
```

`DB_SCHEMA` 必须跟着一起切，否则会把 SQLite 方言的 DDL（`INTEGER PRIMARY KEY AUTOINCREMENT` 等）发到 MySQL。两个驱动都在 `pom.xml` 里，不需要改代码。切换数据库后会使用新库中的记录，原 SQLite 数据需要另行迁移。


## 对接资料

- [CF API 方法与字段](https://codeforces.com/apiHelp/methods?locale=en)
- [CF API 请求频率与公开数据说明](https://codeforces.com/apiHelp/?locale=en)
- [洛谷开放平台官方介绍](https://help.luogu.com.cn/about-us)

洛谷采用原题跳转和手动反馈。完整做题历史自动同步、教练端、集训队管理和学习效果长期评价可以纳入后续迭代。
