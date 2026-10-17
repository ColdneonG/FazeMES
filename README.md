# FazeMES 本地适配底稿

本地部署日期：2026-10-17。MES 后端与 Vue 3 前端已完成本地适配，数据库与附件存储由本工程独立维护。

## 打开与登录

- 前端：http://127.0.0.1:5173
- 后端：http://127.0.0.1:48080/admin-api
- 系统名称：FazeMES
- 租户：示例企业（tenant-id 为 1）
- 管理员昵称：示例管理员
- 本地管理员：`admin` / `MesLocal@2026`

以上账号仅供此回环地址上的本地开发环境。数据库、Redis 随机密码和运行配置保存在被忽略的 `.runtime/`，不要提交该目录。

## 启动、停止和构建

需要 JDK 17、Maven、Node 20.19+、Python 3.10+、PowerShell 7 和 MySQL 8 服务端/客户端程序。独立 MySQL 实例监听 3308，数据目录为 `.runtime/mysql/data`，使用专用数据库 `yudao_mes` 与专用用户；独立 Redis 监听 6381。停止脚本只停止本工程的前端、后端、Redis 和 MySQL 实例。

```powershell
cd D:\indus_app\yudao-mes
pwsh -NoProfile -File deploy/start-local.ps1
pwsh -NoProfile -File deploy/stop-local.ps1
```

首次初始化和重新生成开发配置：

```powershell
python -m pip install -r deploy/requirements.txt
python -X utf8 database/tools/generate_mes_schema.py
python -X utf8 database/tools/generate_mes_seed.py
pwsh -NoProfile -File deploy/init-local.ps1
```

`init-local.ps1` 首次创建独立 MySQL 数据目录并生成随机管理密码，后续启动复用该目录。可通过 `-MysqlClient`、`-MysqlServer` 指定 MySQL 程序路径。系统原有的 3306 服务不受影响；不要删除本工程 `.runtime/mysql/data`。2026-10-17 原先依赖的外部开发数据库目录消失后，已改用独立实例并从本工程 SQL 恢复演示数据；旧验收报告仅作历史记录。

后台进程隐藏运行，日志在 `.runtime/logs`，PID 在 `.runtime/*.pid`。启动和停止均检查进程归属，端口被无关进程占用时会报错。

重新构建前先停止本工程后端，避免 Windows 锁定运行中的 JAR：

```powershell
pwsh -NoProfile -File deploy/stop-local.ps1
mvn -B -ntp -f backend/pom.xml -pl yudao-server -am package -DskipTests
cd frontend
pnpm install --frozen-lockfile
pnpm build:local
cd ..
pwsh -NoProfile -File deploy/start-local.ps1
```

本地开发前端通过同源 `/admin-api` 代理访问后端。`frontend/dist` 是已验证的构建产物。完整类型检查用 `pnpm ts:check`；首次安装后需先启动开发服务生成被 Git 忽略的自动导入类型声明。

## 源码与适配

后端来源：`https://gitee.com/zhijiantianya/ruoyi-vue-pro.git`，`master-jdk17`，提交 `7c397bbfae3e768b26e3ee962c5aa7064e8d7c96`；对应 GitHub 项目 `https://github.com/YunaiV/ruoyi-vue-pro`。

前端来源：`https://gitee.com/yudaocode/yudao-ui-admin-vue3.git`，`master`，提交 `050bac1eccfbe7393a1568d614151cc99501b949`；对应 GitHub 项目 `https://github.com/yudaocode/yudao-ui-admin-vue3`。GitHub 直连失败时使用了官方 Gitee 镜像。工程现以一个 Git 仓库统一管理 `backend/`、`frontend/`、`database/` 和 `deploy/`；两个上游目录的 MIT 许可保留，原 Git 元数据已在工程外完整备份。本仓库从完成本地适配后的源码快照开始记录版本。

`.runtime/`、`node_modules/`、`target/`、`dist/` 和 `output/` 均不提交；它们分别包含本机服务数据、依赖、构建产物和本地验收证据。提交的前端 `.env.local` 仅含可复现的本机示例配置，个人密钥与额外覆盖配置应放到被忽略的文件中。

上游外部服务凭据已从环境示例、测试代码和 SQL 示例中移除，接入微信、短信、云存储、AI 等服务时应自行配置环境变量或本地配置。未配置这些服务不影响本文的 `meslocal` 部署流程。

本轮适配包括：

- 启用后端 MES 模块，只展示 MES、系统管理、基础设施三个业务入口。
- 依据公开 DO、BaseDO 和 Mapper 独立生成 133 张 MES 开发表，补充租户字段；未使用另受条件约束的官方 `mes.sql.zip`。
- 提供虚构演示主数据、BOM、工艺、三级仓库、库存、草稿工单、42 个编码规则；补齐 3 类字典和 13 个权限按钮。
- 将附件切换为 `.runtime/uploads` 本地存储，SOP 和其他附件不依赖上游演示云存储。
- 新增真实数据的 MES 工作台，修复菜单根匹配、字典引用、登录异常及本地代理配置。
- 系统名称为 FazeMES，主 Logo、启动标识和浏览器图标使用本地 FaZe Clan 红色队标资源；来源记录见 `frontend/public/ASSET-SOURCES.json`。头像、演示图片和演示企业名称继续使用示例占位内容；初始化会重复应用 `database/migrations/20261017_example_placeholders.sql`，避免重新导入上游系统数据后恢复旧展示内容。技术包名、源码来源、许可证与版权声明保留。
- 修复非关键工序报工不更新任务进度、排产任务不回写工单已排产量的问题。已排产量按“同工序各工作站分配数合计”后取各工序最大值，避免两道工序各排 50 件被计为 100 件。
- 统一排产、报工、质检回调与完工的加锁顺序，任务取得锁后复核归属和状态，避免并发迁移任务时遗漏工单排产量回写。
- 依赖保留锁定版本和 integrity，仅规范镜像 tarball URL；前端必要依赖构建脚本有明确白名单。

## 演示与验收

保留 `DEMO-WO-001` 作为 50 件草稿工单，产品为“演示传动组件”，BOM 为每件 1 个毛坯、4 个螺栓。确认工单后需要在生产排产创建任务；确认动作本身不会自动派工。两道工序为下料和装配，仅装配设为关键工序。

```powershell
python -X utf8 deploy/verify-local.py
python -X utf8 deploy/verify-mes-flow.py --quantity 50 --run-id my-local-acceptance
python -X utf8 deploy/verify-mes-supporting.py --help
```

主流程脚本经真实 HTTP 验证工单、BOM、排产、领料拣货、两工序报工、原料扣减、产品产出、甘特进度和工单完成。重复相同 run-id 复用已执行单据，验证库存不会再次扣减；使用不同 run-id 会创建新的验收工单并真实消耗演示库存。

支持域脚本验证三层装箱递归查询、非法关联拦截、两个班组的三天两班倒及重复确认拦截。当前独立实例报告位于 `.runtime/mes-placeholders-acceptance.json`、`.runtime/mes-placeholders-idempotency.json`、`.runtime/mes-placeholders-supporting.json`；95 个分页接口和附件上传下载结果位于 `.runtime/local-verification.json`。

生产逻辑修复阶段的 47 项后端回归全部通过。本轮占位替换后，后端打包、前端完整构建、类型检查及样式检查通过；15 项数据库展示残留检查均为零，迁移可重复执行。当前独立实例的 95 个 MES 分页接口、附件读写、10 件工单完整流程、相同 run-id 复跑及装箱排班验证全部通过，停止再启动后数据与占位显示保持正常。汇总见 `output/verification-summary.json`，数据库审计见 `database/audit/example-placeholders.json`。

回归测试：

```powershell
mvn -B -ntp -f backend/pom.xml -pl yudao-module-mes -am test '-Dtest=MesCalPlanServiceImplTest,MesCalTeamShiftServiceImplTest,MesWmItemConsumeServiceImplTest,MesProFeedbackServiceImplTest,MesProFeedbackProgressTest,MesProTaskScheduleProgressTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

## 当前边界

这是可运行和继续二开的本地底稿。独立 DDL 的字符串长度、索引和部分默认值属于本地设计，详情见 `database/README.md`。当前流程验证不等于所有工厂业务和并发负载均已完成验收。

质检的真实抽样规则、设备点检到停机状态联动、生产退料与销售退货、现场触屏/PDA/打印机、ERP 接口尚需结合工厂业务逐项实施。上游允许线边库负库存的策略仍保留；上线前需明确业务规则。此前旧版本验收数据的非关键工序进度不自动回填，修复后验收使用新工单。
