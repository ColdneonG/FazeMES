# FazeMES 本地数据库

此目录是依据公开 Java/MyBatis 源码独立整理的本地开发数据库，**没有下载、复制或使用另行提供的 `mes.sql.zip`**。上游公开源码的版本号、每个 DO 的相对路径与 SHA-256 保存在 `init/mes_schema_manifest.json`。

## 初始化

使用 MySQL 8 和 `utf8mb4`，为本工程建立独立空库 `yudao_mes`。先导入上游 `backend/sql/mysql/ruoyi-vue-pro.sql`，再依次导入：

1. `init/mes_schema.sql`：133 张 MES 表。
2. `init/mes_seed.sql`：演示主数据、编码规则、字典和权限补充。

上游系统脚本包含 `DROP TABLE`，只可用于首次初始化本工程的空库。补充 DDL 使用 `CREATE TABLE IF NOT EXISTS`，不会覆盖已有表；演示脚本采用固定 ID 和主键重复时不更新的方式，不会重置已经执行的工单或库存。已有表的结构升级应另写显式迁移，重新执行 DDL 不会补字段。

本地默认租户为 `tenant_id=1`。虽然当前 MES DO 直接继承 `BaseDO`，芋道 `TenantProperties` 默认对所有表开启租户拦截，因此本目录对每张 MES 表额外添加 `tenant_id`。请求和数据库必须使用同一租户。

自动编码使用 Redis 原子递增和周期过期，因此需要后端连接的 Redis 可用。生成脚本自身无需连接 MySQL 或 Redis。

## 独立恢复依据

`tools/generate_mes_schema.py` 解析 `@TableName`、`@TableId`、`@TableField` 以及继承的 `BaseDO`/`TenantBaseDO` 字段，排除 `exist=false`。当前 133 个公开 DO 均已识别，遇到新字段类型、未审阅的父类或缺少主键会终止生成。

- ID 和关联 ID：`BIGINT`；主键为自增。
- `Integer`：`INT`；数量与金额类 `BigDecimal`：`DECIMAL(20,6)`。
- `Boolean`：`BIT(1)`；`LocalDateTime`：`DATETIME`。
- `BaseDO`：`creator/updater/create_time/update_time/deleted`。
- 质检模板 `types` 使用上游 `IntegerListTypeHandler`，保存为逗号分隔字符串，不能改成 JSON 类型。
- 为编码、关联 ID 和 `tenant_id/deleted` 添加普通索引，暂未添加业务唯一键或数据库外键。

三个自定义 MyBatis XML 已做物理表和带表别名列名的交叉检查；`UNION` 各分支独立处理别名。字符串长度、数据库默认值和索引属于本地开发选择，无法单凭 Java 反推出原始商业 SQL 的全部约束。后续适配应以业务流程验证和迁移为准。

重新生成：

```powershell
python D:\indus_app\yudao-mes\database\tools\generate_mes_schema.py
python D:\indus_app\yudao-mes\database\tools\generate_mes_seed.py
```

## 演示记录

全部记录是虚构本地样例。业务编码使用 `DEMO-` 前缀，固定 ID 位于 `900xxx` 范围；数据库表之间可以使用相同的固定 ID。

| 对象                     | ID / 编码                                                                               |
| ------------------------ | --------------------------------------------------------------------------------------- |
| 计量单位                 | `900001` / `DEMO-PCS`（件）                                                             |
| 原材料分类、产品分类     | `900001`、`900002`                                                                      |
| 毛坯、螺栓、组件         | `900101`、`900102`、`900103`                                                            |
| 产品 BOM                 | 每件组件 = 1 件毛坯 + 4 件螺栓                                                          |
| 客户、供应商、车间、路线 | 各表 `900001`                                                                           |
| 下料工序 / 工作站        | 各表 `900001`                                                                           |
| 装配工序 / 工作站        | 各表 `900002`                                                                           |
| 实体仓库 / 库区 / 库位   | 各表 `900001`，`DEMO-WH/DEMO-LOC/DEMO-A01`                                              |
| 虚拟线边仓 / 区 / 位     | 各表 `900002`，源码约定的 `WIP_VIRTUAL_WAREHOUSE/WIP_VIRTUAL_LOCATION/WIP_VIRTUAL_AREA` |
| 草稿工单                 | `900501` / `DEMO-WO-001`，50 件，附 50 件毛坯和 200 件螺栓的工单 BOM                    |

实体仓期初库存为 500 件毛坯、2000 件螺栓、40 件组件，库存 ID 分别 `900001/900002/900003`，对应批次 ID 相同。虚拟线边仓另有 100 件毛坯、400 件螺栓，库存 ID 为 `900004/900005`，沿用原料批次。每笔期初库存都有 `type=5` 的演示盘盈流水，业务编码 `DEMO-OPENING`。

路线为“下料 → 装配”。下料消耗 1 件毛坯，装配消耗 4 件螺栓；只有末道装配为关键工序，两道均不强制质检，避免两道都累计工单产量。所有演示库位允许物料和批次混放。产品开启批次管理，批次匹配的附加维度开关关闭，以便独立试运行。18 类业务对象补了二维码配置，模板内容为业务编码，打印模板可在页面选择。

确认工单仅变更状态；当前芋道源码不会在确认时自动创建生产任务。应继续在排产管理选择工单、工序、路线和工作站建立任务，再验证报工、线边扣料和关键工序产出。

## 上游系统数据补充

`tools/generate_mes_seed.py` 检查系统初始化 SQL 的字典类型、字典值和 Controller 权限。当前版本发现并补充：

- 3 个字典：`mes_barcode_format`、`mes_barcode_biz_type`、`mes_wm_transaction_type`。取值和标签直接来自公开枚举。
- 13 个权限按钮：车间导出、工序领料增删改查、四类质检完成、外协发料完成、供应商退货完成、盘点任务完成、产品产出查询。
- 42 个公开自动编码规则，每个由 `DEMO-业务前缀 + yyyyMMdd + 每日五位流水号` 组成。

详见 `init/mes_seed_audit.json`。补充按钮不删除旧权限，也不自行扩大普通角色授权；超级管理员沿用上游权限机制，普通角色可在角色管理中按需勾选。菜单和字典缓存需要在导入后重启后端或清理对应缓存。

本目录仅提供开发起点。结构精度、业务唯一性、并发事务、库存追溯和正式权限仍应随着真实生产需求逐项验证。

## 运维数据库备份

`tools/operational_backups.py` 负责独立的运维备份，通过 `deploy/operational-backup.ps1` 或正常启动时的托管进程运行。每日上海时间 02:00 执行完整逻辑备份，周日使用 `WEEKLY_FULL` 标记，其余使用 `DAILY_FULL`。初次启用或漏跑后补做最近到期时点，不伪造停机期间每天的历史快照。日备份本身也包含全部业务表、视图、触发器、存储过程及数据库事件。

导出只连接 `127.0.0.1:3308/yudao_mes`，执行前核对服务端数据目录确为本项目 `.runtime/mysql/data`，并要求所有业务基础表使用 InnoDB。MySQL 凭据通过已有的 `.runtime/mysql-admin.local.ini` 传递，命令行不包含口令；本机管理员账号提供完整导出存储过程所需的元数据权限，应用账号的权限保持不变。导出使用 `--single-transaction --quick`，与项目初始化、迁移和重建测试数据入口共享跨进程维护锁，防止在结构修改或重建过程中形成不一致备份。不能用此工具操作其他 MySQL 实例。

每份结果保存在 `.runtime/backups/operations`，由 `.sql.gz` 和同名 `.json` 清单组成。清单记录到期时间、实际完成时间、日/周类型、表数量、压缩前后字节数及 SHA-256。导出先写临时文件，核对表数量与完整结束标记，压缩后完整读取 gzip 校验 CRC 和原 SQL 摘要，再原子发布清单。中途失败不产生可用恢复点，也不清理旧备份；无法识别或校验失败的既有记录会阻止过期清理。成功后才移除完成时间超过 30 天且不是最新日/周恢复点的本工具备份，无关文件及测试数据恢复点不受影响。

`-Action Verify` 只检查备份文件和清单，无需启动数据库；最近调度状态位于 `.runtime/operational-backup-status.json`。默认后台进程的运行日志位于 `.runtime/logs/backup.stdout.log`、`backup.stderr.log`。无人登录执行方式使用显式安装的 Windows SYSTEM 任务，主机时区不会改变上海 02:00 的触发时刻；任务不会启动数据库，也不会执行恢复。

恢复时先在独立的 MySQL 8.0 环境解压导入并检查迁移记录、工单、库存流水及追溯关系，再在维护停机状态按配套版本恢复业务库。文件摘要和 SQL 完整性检查不能替代实际恢复演练。此备份涵盖数据库；附件、应用配置、SOP 文件及加密主密钥应另行配套备份并限制访问，以保留完整的恢复依据。

回归检查不连接真实数据库，也不注册 Windows 任务：

```powershell
python -m unittest discover -s database/tools -p test_operational_backups.py
pwsh -NoProfile -File deploy/verify-operational-backup.ps1
```
