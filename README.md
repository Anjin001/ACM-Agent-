# ACM-Agent- · 循练 ACM 训练工作台

面向软件项目管理课程的可运行首版。围绕训练目标、公开做题记录、周计划和复盘调整建立完整流程。

## 快速启动

在 Windows 双击根目录的 `启动.bat`，然后访问 [训练工作台](http://127.0.0.1:18080)。首次进入课程演示空间，可以从左下角创建独立账号。

首次构建需要联网，要求 Node.js 22.12+、npm、JDK 17+。脚本会寻找 `JAVA_HOME` 或常见安装目录中的 JDK，优先使用已有 Maven，缺少 Maven 时下载到用户缓存。首次构建会安装前端依赖、构建页面并运行后端测试。

```powershell
# 构建并运行测试
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build.ps1

# 启动已构建的版本，默认仅监听本机
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start.ps1
```

修改源代码后重新执行构建。运行窗口中按 Ctrl+C 停止。数据库保存在项目根目录的 `data/`，请保留该目录以继续使用已有账号和计划。

## 已实现的流程

1. 默认演示空间包含虚构的学员、训练安排和两条复盘，页面明确标记演示来源。
2. 注册账号或登录后，拥有独立的训练目标、CF handle、时间预算、计划和复盘。密码使用 BCrypt，登录状态使用 HttpOnly 会话 Cookie。
3. 在个人设置保存 CF handle，点击同步。后端通过 CF 官方 API 读取公开信息和提交记录，按两秒以上的间隔请求。
4. 同步最多最近 10000 条提交，页面标明截断范围。结果按提交 ID 去重，并在一次数据库事务中保存。完整同步失败时保留上次成功结果。
5. 生成本周计划，按专题、难度范围和本周剩余日期排期，排除已知通过题与已记录完成题。已有反馈任务保留。
6. 跳转 CF 或洛谷原题练习，在任务中记录独立完成、参考题解或仍然卡住，以及实际用时和文字复盘。
7. 生成调整方案，比较前后题量、时间及具体新任务。接受后保存计划版本，可以撤销，同时保留新增复盘。
8. 题库可搜索、按平台和专题筛选。计划可导出 JSON，方便课堂检查。

## AI 配置

默认规则教练可直接使用，界面会显示“规则生成”。规则模式依据保存的复盘确定巩固专题，并支持“周六30分钟”“周四没时间”“每天30分钟”等时间条件。

要启用大模型，在启动服务的终端中设置以下环境变量。使用提供 Chat Completions 兼容接口的服务，密钥只进入后端进程。

```powershell
$env:AI_BASE_URL = 'https://your-provider.example/v1'
$env:AI_API_KEY = 'your-key'
$env:AI_MODEL = 'your-model'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start.ps1
```

模型会收到训练目标、近期复盘、时间预算和候选题。后端逐项检查候选 ID、重复题、日期、最少用时和日预算。超时、接口错误、格式错误或校验失败时展示规则方案及回退状态。“已配置”只代表环境变量存在，成功调用由每次方案的生成状态体现。

## 数据与技术结构

- 前端使用 Vue 3、TypeScript、Vite 与 Lucide 图标。五个页面覆盖概览、周计划、题库、复盘与设置。
- 首版面向基础训练，支持 800～1600 的 CF 难度起点，结合人工精选题安排练习。
- 后端使用 Spring Boot 3.5.7、Java 17 API、JdbcTemplate。默认使用 H2 文件库，并附 MySQL 驱动。
- `accounts` 保存账号，`problems` 保存题目，`cf_submissions` 保存提交记录。`workspaces` 将偏好、计划、反馈与版本作为 JSON 聚合保存，使用 revision 乐观锁防止并发覆盖。
- 计划和反馈采用聚合存储，后续可以按课程数据库设计要求拆成独立表。
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

## 使用 MySQL

先创建 UTF-8 数据库，再设置连接信息。首次启动自动建立四张表。切换数据库后会使用新数据库中的记录，原 H2 数据需要另行迁移。

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/xunlian?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:DB_USER = 'your-db-user'
$env:DB_PASSWORD = 'your-db-password'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start.ps1
```

## 对接资料

- [CF API 方法与字段](https://codeforces.com/apiHelp/methods?locale=en)
- [CF API 请求频率与公开数据说明](https://codeforces.com/apiHelp/?locale=en)
- [洛谷开放平台官方介绍](https://help.luogu.com.cn/about-us)

洛谷采用原题跳转和手动反馈。完整做题历史自动同步、教练端、集训队管理和学习效果长期评价可以纳入后续迭代。
