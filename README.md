# FazeMES

<img src="frontend/public/fazemes-logo.svg" alt="FazeMES" width="64" height="64">

FazeMES 是基于 Spring Boot、Vue 3 和 TypeScript 的生产执行管理系统，供 Windows 本地开发和工厂业务适配使用。

## 功能范围

- **基础资料**：物料、产品、BOM、工艺路线、车间与工作站。
- **生产管理**：工单、排产、领料、报工、工序流转与完工。
- **生产决策中心**：交期风险预警、插单与加班方案仿真、质量关联、异常处置验证闭环、实时业务问答及流程瓶颈分析，详见[使用说明](deploy/PRODUCTION-INSIGHT.md)。
- **齐套缺料**：按工单 BOM、净领料及可用库存分析缺口，支持工单预占、释放和到料跟进。
- **受控作业**：SOP 审批发布、任务版本快照、步骤确认、参数及照片留痕，未完成必做步骤时拦截报工。
- **单件追溯**：SN 登记与工序过站，将已完成报工的实际耗料分摊到单件，支持按物料批次反查。
- **报表与大屏**：拖拽式 A4 报表画布、组件与白名单数据绑定、版本发布、Excel 导出与打印，以及带城市地图和 Three.js 动效的生产看板。
- **AI 与知识库**：文档上传解析、分块检索与来源引用、独立会话；支持配置 OpenAI 兼容 API Key 或本地 Ollama。
- **Jev 异常分流试点**：异常分类、实际部门建议、人工确认与采纳记录；使用独立的 TypeSafe 密钥，详见 [Jev 接入说明](deploy/JEV-TRIAGE.md)。
- **客户门户**：独立客户账号、发布产品目录、订购申请审核及本客户生产进度查询。
- **人脸登录**：密码确认后录入本人人脸，通过本地 SFace 一对一核验登录，可停用并清除特征。
- **仓库管理**：库存、入库、出库、调拨、盘点与装箱。
- **配套管理**：质量、设备、工装夹具、班组与排班。
- **系统管理**：账号、角色、菜单、租户及本地附件存储。

当前启用 MES、系统管理和基础设施模块，并提供虚构示例数据。

## 环境要求

当前启动脚本面向 Windows。以下工具需要安装并加入 `PATH`：

| 工具       | 版本要求                    |
| ---------- | --------------------------- |
| Git        | 可使用 `git` 命令           |
| JDK        | 17                          |
| Maven      | 3.9.x                       |
| Node.js    | ≥22.13.0，推荐 24 LTS       |
| pnpm       | 11.25.0，按下方命令安装     |
| Python     | 3.12+                       |
| PowerShell | 7.2+，使用 `pwsh` 命令      |
| MySQL      | 8.0，安装服务端和客户端程序 |

MySQL 程序需位于 `C:\Program Files\MySQL\MySQL Server 8.0\bin`，当前脚本按此位置查找程序。首次安装和启动需要访问 npm、Maven 仓库、PyPI 及 GitHub；脚本自动准备 Redis 和人脸模型运行时，并校验模型摘要。

## 首次启动

在 PowerShell 7 中依次执行：

```powershell
git clone https://github.com/ColdneonG/FazeMES.git
cd FazeMES

npm install --global pnpm@11.25.0
python -m pip install -r deploy/requirements.txt
pnpm --dir frontend install --frozen-lockfile

mvn -B -ntp -f backend/pom.xml -pl yudao-server -am package -DskipTests
pwsh -NoProfile -File deploy/start-local.ps1
```

启动脚本会创建本地 MySQL 实例、导入初始化 SQL 和示例数据，并启动 Redis、本地人脸服务、后端及 Vite 前端。客户账号与产品目录需在“客户门户管理”中开通、发布；模型 API Key 在“AI助手与知识库”的模型配置中填写。

| 项目     | 默认值                             |
| -------- | ---------------------------------- |
| 前端     | <http://127.0.0.1:5173>            |
| 后端 API | <http://127.0.0.1:48080/admin-api> |
| 租户     | 示例企业                           |
| 管理员   | `admin` / `MesLocal@2026`          |

以上账号仅用于本机开发环境。服务监听回环地址；MySQL、Redis 分别使用 3308、6381 端口。

## 日常开发

以下命令均在仓库根目录执行：

- 启动：`pwsh -NoProfile -File deploy/start-local.ps1`
- 停止：`pwsh -NoProfile -File deploy/stop-local.ps1`
- 后端修改后：先停止服务，再运行 `pwsh -NoProfile -File deploy/start-local.ps1 -Build`。

前端使用 Vite 热更新。本地运行配置、随机密码、MySQL 数据、附件和日志保存在 `.runtime/`，该目录不提交 Git。

## 目录结构

```text
backend/    Spring Boot 后端
frontend/   Vue 3 管理端
database/   初始化 SQL、示例数据与迁移脚本
deploy/     本地启动脚本及辅助工具
.runtime/   本地运行数据，启动后生成
```

## 当前限制

本项目处于本地开发与适配阶段。实际质检标准、设备采集、现场终端、打印及 ERP 接口仍需结合工厂需求适配。报表设计限于内置数据集；大屏按现有业务记录统计。库存出库禁止负数，线边库存和预占按工单隔离；Redis 使用无持久化的开发配置。

知识库当前使用 BM25 词法检索，生成回答需要配置模型服务；扫描 PDF 需要先做 OCR。人脸核验不含硬件活体检测，需要本人在页面授权摄像头并完成录入。

## 来源与许可

后端基于 [ruoyi-vue-pro](https://github.com/YunaiV/ruoyi-vue-pro)，前端基于 [yudao-ui-admin-vue3](https://github.com/yudaocode/yudao-ui-admin-vue3)，上游代码分别保留 [后端 MIT 许可](backend/LICENSE)和[前端 MIT 许可](frontend/LICENSE)。Logo 等第三方素材的来源见[资源声明](frontend/public/ASSET-SOURCES.json)。

云枢功能参考与适配范围见[来源记录](THIRD-PARTY-SOURCES.json)，人脸模型许可保存在 [face-auth/licenses](face-auth/licenses)。

本 README 的原创说明内容采用 [CC BY-NC-SA 4.0（署名—非商业性使用—相同方式共享）](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)。引用、转载或改编时，请署名 **FazeMES**，附上[本仓库链接](https://github.com/ColdneonG/FazeMES)和协议链接，标明修改，并遵守非商业性使用及相同方式共享要求。其他文件按各自的许可声明使用。
