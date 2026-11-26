# ACM-Agent- · 循练编程与算法成长平台

面向软件项目管理课程的本地迭代版。覆盖专题学习、理解题诊断、代码验证、公开做题记录、周计划和复盘调整。

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
4. 首次同步自动分页读取完整公开提交历史。后续快速同步刷新最新一页及新增记录，完整重同步用于刷新较早的重判结果。支持进度查看与取消，失败或取消保留上次成功结果。
5. 生成本周计划，按专题、难度范围和本周剩余日期排期，排除已知通过题与已记录完成题。已有反馈任务保留。
6. 跳转 CF 或洛谷原题练习，在任务中记录独立完成、参考题解或仍然卡住，以及实际用时和文字复盘。
7. 生成调整方案，比较前后题量、时间及具体新任务。接受后保存计划版本，可以撤销，同时保留新增复盘。
8. 题库支持搜索、分组专题、CF 难度及完成状态筛选。题目行提供明确的“打开原题”按钮，专题练习和 CF 已通过列表同样支持跳转。
9. 新增专题与路线页面，提供前置知识、学习检查点及对应练习，可将路线或细分专题加入训练方向。
10. 新增两组共 12 道理解题诊断，逐级提示使用情况随结果保存，并可据此设置训练专题。
11. 新增二分边界、整数溢出和整数除法三组预设代码验证挑战，支持提交反例、比较预设程序与参考结果，并保存解释。

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

- 前端使用 Vue 3、TypeScript、Vite 与 Lucide 图标。八个页面覆盖概览、诊断验证、专题路线、题库、CF 已做题、周计划、复盘与设置。
- 训练起点支持 800～3500，结合原平台评分与人工精选练习安排任务。不同平台的难度分别保留。
- 后端使用 Spring Boot 3.5.7、Java 17 API、JdbcTemplate。默认使用 H2 文件库，并附 MySQL 驱动。
- `accounts` 保存账号，`problems` 保存题目，`cf_submissions` 保存提交记录。`workspaces` 将偏好、计划、反馈与版本作为 JSON 聚合保存，使用 revision 乐观锁防止并发覆盖。
- 计划、反馈、诊断与挑战结果采用聚合存储。新增字段兼容已有工作区数据，题库更新会补充和刷新元数据。题目查询使用内存目录和后端分页，工作区接口仅返回当前计划及复盘引用的题目。
- 题库包含 2026-11-12 的 11511 道 CF 官方公开题目元数据和 69 道洛谷精选题，共 11580 道。84 个专题覆盖基础、搜索、动态规划、数据结构、图论、树算法、数学、计算几何、字符串和进阶。洛谷名称为人工整理的训练主题，难度与详情以原题为准。CF 标签只映射到有依据的算法类别，细分算法主要由精选练习补充。
- CF 难度与洛谷难度分别保存。训练用时是排期估计，实际用时来自反馈。CF AC 状态单独展示，独立完成情况由本人反馈。
- 同步可补充快照外的题目，因此本机目录总数可能高于内置快照数量。旧版已通过记录缺少题目元数据时，已做题列表仍显示题号、通过日期和原题入口；完整同步后补齐详情。
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

洛谷采用原题跳转和手动反馈。教练端、集训队管理和学习效果长期评价可以纳入后续迭代。当前学习路线用于设置训练方向，知识点建议和诊断结果以理解题记录呈现。预设代码验证依据固定程序执行结果，文字解释作为个人复盘保存。真实模型仍需配置服务后联调，完整工具调用 Agent、内容管理后台和自由代码评测属于方案中的后续工作。

## 更新题库

运行 `node scripts/seed-catalog.mjs` 可从 CF 官方 API 刷新快照。也可以传入已下载的官方 JSON 文件路径。`scripts/topics.mjs` 是专题和洛谷精选练习的统一数据源。刷新后重新构建，已有个人记录会保留。
