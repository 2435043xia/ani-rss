# Orca 字幕翻译：选择 GPT 与实时模型目录

本次修改基于 `3.2.48` 字幕版。2026-10-24 从 fork `origin` 拉取并合并 `main` 时，远端 `main` 仍为 `e3f7fc6`（3.2.39），已包含在字幕分支中，Git 返回 `Already up to date`；没有降级，也没有用上游另一条主分支替换现有功能。

## 使用

1. 在设置的字幕翻译中选择“SSH（Orca）”，填写实际运行 ANI RSS 的服务器或容器能用的 SSH 主机、用户、端口及私钥路径。
2. 保留原有 Orca 路径与工作区。工作区需为执行端已有的工作区，所有字幕任务共用这一个配置；本次不会为每个任务注册新目录。
3. 点击“刷新模型”，在执行器中选择 `codex`，再从返回的列表选择 GPT 模型与对应强度，保存设置。
4. 后续字幕翻译使用保存的完整模型编号与强度。空模型表示继承 Orca 配置；空强度表示继承所选模型默认值。

目录从目标 SSH 主机的 Orca 实时读取，不维护固定的模型或强度名单。模型编号原样保存，例如执行端返回带 `/` 的编号时保留整个编号，不截去提供商前缀。切换执行器或模型会清除不再适用的选择；修改连接会使旧目录失效，迟到的旧请求不能覆盖新连接的结果。刷新不会新建翻译 worker。

只展示实际读取到的执行器及可用于启动任务的模型。每次翻译启动前还会重新查询所选执行器，核对模型与强度；读取失败、模型已失效或强度不匹配时拒绝执行，不自动改用其他模型。

## 兼容与运行条件

- 本次实测执行端为 macmini 上的 Orca **1.4.237**。目录读取复用该安装的运行时与启动能力检查；Orca 安装布局或接口不兼容时明确报错。
- 当前执行端的 Codex 支持任务级模型和强度，Grok 只支持继承自身设置。界面以实际能力显示，后续 Orca 改变能力时重新刷新即可。
- 旧配置继续默认使用 Grok 继承方式。旧的 `subtitleTranslateGrokModel` 和 `subtitleTranslateReasoningEffort` 字段原先没有传入任务，仍保留数据；新设置使用 `subtitleTranslateOrcaAgent`、`subtitleTranslateOrcaModel`、`subtitleTranslateOrcaEffort`，不把旧的未生效设置突然应用到任务。
- GPT 仍需执行端已有可用的 Codex 登录与模型配置。能读取目录不等于所有启动提示都已处理；更新提示、登录或权限提示可能阻止启动。实测中在本次专用启动窗口选择跳过当前 CLI 更新后继续，未升级 Codex 或更换账号。
- Worker 使用 `--agent`、`--model`、`--effort`，不修改 Orca 全局默认设置。结束后释放本次 worker 与协调终端，保留 Orca 内部历史；不关闭其他任务的窗口。SSH 回执不明确时不自动创建第二个任务；API 方式保留原有重试。
- 原有术语表、字幕行数与时间轴检查、已有中文字幕跳过逻辑及 API 翻译方式保持。

## 验证（2026-10-24）

- 47 项 Java 测试、9 项远端脚本流程、4 项前端选择逻辑测试通过，Vue 生产构建通过。新增检查包含带提供商前缀的模型、动态新增强度、消失的模型、错误强度、继承模式、启动失败回执的资源释放以及模型请求不携带其他 API 的密钥。
- 实际连接 macmini，读取当前 Orca 模型目录；用本次脚本发起 Codex + `ark/gpt-5.6-luna` + `low`，两句合成日文字幕成功得到两句中文及术语块。启动回执的 `effective` 与请求的执行器、模型和强度一致，完成后的 worker 为 `succeeded`，终端资源为 `released`、观察状态为 `exited`。
- 未使用真实番剧文件，未替换运行中的服务。初次验收后，提交 `a439998` 已推送到 fork 的 `codex/subtitle-orca-models`，镜像已发布到 `blumenkranz714/ani-rss` 与 `registry.888987.xyz/ani-rss`，同时保留 `latest` 和 `3.2.48-orca-models` 标签。生成的包、构建和执行证据放在 builder 的 `/home/builder/ani-rss-orca-models-20261024/`；本机也保留本轮源文件与验证证据。

构建需要 JDK 25；builder 的 Maven 容器还需 Node 对应的 `libatomic`。Git 工作树构建时需挂载实际的公共 `.git` 目录。测试通过 `CONFIG=/tmp/ani-rss-test-config` 隔离运行配置，避免将生成的配置当作源码提交。

## Orca 冷启动时的模型查询

Orca `1.4.237` 的 `agentSession.modelCatalog` 依赖已初始化的内部会话服务。服务尚未初始化时，接口会返回 `structured_agent_session_unsupported`，详情为 `hostDisabled`；这不表示机器没有模型，也不是安装包不同。

查询脚本只在收到上述明确错误时，执行一次只读的 `aiVault.listSessions({limit: 1})` 初始化服务，丢弃返回的目录元数据，再重试模型查询一次。此版本的 `limit: 0` 会使用默认数量，因此这里明确限制为一条。同一次刷新中的多个执行器共用初始化请求。已就绪的服务直接查询；其他权限、兼容性或连接错误继续按原逻辑返回，初始化或重试失败后结束本次恢复，不循环重试。该流程不创建 AI 对话或 worker，也不修改 Orca 的聊天模式、模型或强度设置。

新增回归测试覆盖冷启动、并发执行器、指定执行器、两种错误回执、权限拒绝、部分目录失败、初始化失败和重试仍失败。使用执行端的 Node 运行：

```sh
node --test ani-rss-application/src/test/node/catalog_rpc.test.cjs
```

排查时，开发 Mac 与 macmini 的版本和核心代码摘要一致；开发 Mac 在初始化前复现 `hostDisabled`，只读初始化后成功读取 7 个 Codex 模型和各模型的强度。
