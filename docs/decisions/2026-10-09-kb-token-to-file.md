# KB 服务 token 配置方式决策（2026-10-09）

## 背景

代码审查发现 KB 桥接的服务 token 曾以默认值硬编码在 `application.yml` 和 `start-workbench.bat` 里，已提交进仓库。token 属于凭据，进仓库即等于对所有有读权限的人公开，且轮换时需要改代码。

## 备选方案

1. 直接删除默认值，只走环境变量：仓库干净，但本地启动脚本拿不到 token，KB 桥接当场断掉。
2. 沿用项目已有 `.aikey` 模式：token 放在项目根目录 git-ignored 的 `.kbtoken` 文件，`start-workbench.bat` 启动时读取注入环境变量；`application.yml` 默认值改为 `${WORKBENCH_KB_SERVICE_TOKEN:}`（空，缺省只打 WARN 不阻断）。

## 决策与原因

选方案 2。它与项目现有的 `.aikey` 约定一致，本地桥接不断，仓库不再含任何真实凭据。生产环境用系统环境变量注入，不依赖脚本。

## 回顾 / 防再犯检查

- 任何 token、密钥、连接串**不得**以默认值写进 `application.yml`、启动脚本或任何被 git 跟踪的文件；一律「环境变量 + git-ignored 本地文件」两件套。
- 新增凭据时同步在 `.gitignore` 加对应条目（本次已加 `.kbtoken` / `*.kbtoken`）。
- 交付前 `git grep` 一次真实凭据片段，确认只出现在 git-ignored 文件中。
- 遗留：`start-workbench.bat.bak` 内含旧 token，未被 git 跟踪，是否删除待 owner 确认。
