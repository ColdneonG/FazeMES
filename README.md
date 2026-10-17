# FazeMES

<img src="frontend/public/fazemes-logo.svg" alt="FazeMES" width="64" height="64">

FazeMES 是基于 Spring Boot、Vue 3 和 TypeScript 的生产执行管理系统，供 Windows 本地开发和工厂业务适配使用。

## 功能范围

- **基础资料**：物料、产品、BOM、工艺路线、车间与工作站。
- **生产管理**：工单、排产、领料、报工、工序流转与完工。
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
| Python     | ≥3.10                       |
| PowerShell | 7.2+，使用 `pwsh` 命令      |
| MySQL      | 8.0，安装服务端和客户端程序 |

MySQL 程序需位于 `C:\Program Files\MySQL\MySQL Server 8.0\bin`，当前脚本按此位置查找程序。首次安装和启动需要访问 npm、Maven 仓库及 GitHub；Redis 由启动脚本自动下载。

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

启动脚本会创建本地 MySQL 实例、导入仓库中的初始化 SQL 和示例数据，并启动 Redis、后端及 Vite 前端开发服务。

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

本项目处于本地开发与适配阶段。真实质检规则、设备联动、生产退料与销售退货、现场终端、打印及 ERP 接口仍需结合工厂需求完善。当前线边库允许负库存，Redis 使用无持久化的开发配置。

## 来源与许可

后端基于 [ruoyi-vue-pro](https://github.com/YunaiV/ruoyi-vue-pro)，前端基于 [yudao-ui-admin-vue3](https://github.com/yudaocode/yudao-ui-admin-vue3)，上游代码分别保留 [后端 MIT 许可](backend/LICENSE)和[前端 MIT 许可](frontend/LICENSE)。Logo 等第三方素材的来源见[资源声明](frontend/public/ASSET-SOURCES.json)。

本 README 的原创说明内容采用 [CC BY-NC-SA 4.0（署名—非商业性使用—相同方式共享）](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)。引用、转载或改编时，请署名 **FazeMES**，附上[本仓库链接](https://github.com/ColdneonG/FazeMES)和协议链接，标明修改，并遵守非商业性使用及相同方式共享要求。其他文件按各自的许可声明使用。
