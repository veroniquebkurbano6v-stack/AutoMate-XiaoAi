# AutoMate·小艾 — 一句话，帮不便操作手机的人完成手机上的事

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> Android 数字无障碍助手 · 端侧知识库（检索离线可用） · 多通道感知（无障碍树 + 视觉）
> 本项目采用 [Apache License 2.0](LICENSE) 开源协议。

## 📋 为什么做这个

中国有**数亿人**不会或不方便操作智能手机：视力下降的老年人、低视力/视障者、
行动不便者、以及不熟悉数字世界的用户。对他们而言，**点外卖、发消息、查路线**
这些"日常小事"，却是难以跨越的障碍。

AutoMate·小艾 用一句自然语言，帮他们完成整个手机操作流程：
**"帮我在淘宝点杯奶茶"、"用微信给家人发条消息"、"导航去最近的医院"** ——
AI 自主完成搜索、定位、输入、选择、下单等全部步骤，**无需看清屏幕、无需记住步骤、
无需精确点击**。

**多通道感知**同时融合三种"眼睛"，适配不同障碍人群：
- 🦯 **无障碍树**（文本通道）→ 读屏用户友好，App 未被屏蔽时优先
- 👁️ **屏幕视觉 + GUI-Plus**（坐标通道）→ 低视力用户友好，看不清时视觉兜底
- 🧠 **端侧知识库**（离线 SOP 指引）→ 检索离线可用，数据不出手机；**识图与操作需联网云端模型**

## 🎯 选题说明

本项目报名时选择「其他方向」赛道，说明如下：

**核心定位：数字无障碍 + 适老化的通用 AI 操作代理。** 它不是某个单一垂直场景
（如单一购物助手/单一导航工具），而是面向"不便操作手机的人群"的通用底座——
同一套"决策 + 执行 + 多通道感知 + 端侧知识库"架构可覆盖购物、社交、导航、
生活服务等全部日常场景。因此难以归入单一行业赛题，故选择「其他方向」。

**为什么适合放在一起评**：
- **社会价值**：中国 60 岁以上人口超 2.8 亿，加上视障、听障、手部不便人群，
  是"数字鸿沟"最严重的群体；本项目的目标是让 AI 替他们"看屏幕、点按钮"。
- **技术价值**：完整落地了"端侧 RAG（知识库数据不出手机）+ 双模型编排 + 无障碍感知"
  这一组合，工程上有 25 个动作工具、545 条离线 SOP、<80ms 端侧检索。
- **可验证性**：多段真实操作演示视频 + 可复现的检索/视觉消融评测数据
  （见 [docs/evaluation.md](docs/evaluation.md)），非概念 Demo。

**一句话总结**：AutoMate·小艾 是一个"技术通用、场景垂直"的数字无障碍项目——
技术栈可复用于任意 GUI 操作任务，而服务对象始终锚定最需要帮助的群体。

## ✨ 核心特性

| 特性 | 技术实现 | 对用户的意义 |
|------|---------|------------|
| 完全端侧知识库 | bge-small-zh INT8 ONNX + SQLite 向量 + RRF 混合检索 | 检索离线可用、数据不出手机；**识图与操作需联网** |
| 多通道感知 | 无障碍树 + OCR + VLM + GUI-Plus 四通道回退 | 视障/读屏总有一条"眼睛"可用 |
| 一句话任务路由 | 决策模型工具链（kb_read / list_apps / amap_* / web_search / 追问） | 用户只需"说"，无需"会" |
| 结构化 Plan | 决策模型生成分步 Plan（含完成标志、监督标记） | 复杂跨页流程拆解为可执行步骤 |
| 端到端执行 | 25 个动作工具（tap / auto_input / select_spec / open_app…） | 自动完成定位、输入、点击 |
| 敏感操作拦截 | `supervised` 标记 + 用户确认 | 支付/转账等不可逆操作不越权 |

## 📺 演示视频

> 代表性操作实录：均为真机拍摄，AI 端到端自动完成，点击即可播放。
> 完整场景故事见 [docs/use-cases.md](docs/use-cases.md)。

**📹 项目总览（3 分钟）** — 产品定位 · 端侧架构 · 多段任务实录

https://github.com/user-attachments/assets/718524ff-01a3-4450-b693-d55a2843d374

**用淘宝点奶茶** — 老年人 · 跨页搜索 → 选规格 → 加购，全程 AI 代操作

https://github.com/user-attachments/assets/641b225c-3f9b-43ad-be33-ecbedba84377

**用微信给联系人发消息** — 行动不便/低视力 · 一句话 + 自动定位输入/发送

https://github.com/user-attachments/assets/775abc78-c6f6-47c2-98ca-af06c21359e5

**用高德导航到目的地** — 老年人/视障 · 附近检索 → 导航，语音触发

https://github.com/user-attachments/assets/9db81a0d-4a39-4bb9-939f-031f8f49c6b8

**高德分享位置到微信** — 跨 App 协作 · 检索评分最高店铺 → 一键分享定位给微信联系人

https://github.com/user-attachments/assets/4da8022d-3aba-47f9-92cf-db198f330f7f

> 以上为代表性演示。底层是覆盖购物 / 社交 / 导航 / 生活服务的**通用底座**，
> 配合 545 条端侧离线 SOP，可进一步扩展到点外卖、打车、挂号、缴费等更多日常场景。
> 高清原片：可在 [GitHub Releases](https://github.com/veroniquebkurbano6v-stack/AutoMate-XiaoAi/releases) 下载。

## 🗺️ 端到端流程

```
用户一句话（语音/文本/远程通道）
  → 决策模型：意图路由 + 工具链（list_apps→kb_read→amap_*→追问）+ 生成 Plan
  → 执行模型：多通道感知（无障碍树/视觉/GUI-Plus）→ 25 个动作工具逐步执行
  → 端侧知识库：离线 SOP 指引
  → 完成 / 需确认 / 敏感操作拦截后向用户汇报
```

[完整架构见 docs/architecture.md](docs/architecture.md)

## 📊 评估结果（摘要）

| 指标 | 结果 |
|------|------|
| 知识库检索命中率（32 场景） | **100%**（top-3）/ **100%**（top-1） |
| 平均端到端检索延迟 | <80ms（端侧，含上下文组装） |
| 实际任务演示 | 端到端完成（见 Demo） |

[完整评估数据见 docs/evaluation.md](docs/evaluation.md)

## 🚀 快速开始

> **想直接体验？** 可在 [GitHub Releases](https://github.com/veroniquebkurbano6v-stack/AutoMate-XiaoAi/releases) 下载**预编译 APK**（不含任何 API Key，安全合规；安装后需在 App 内「设置」页自行填写 Key）。作者为学生个人开发者，暂无力承担大规模 API 消费。

### 0. 克隆与模型拉取（Git LFS）

仓库中的 ONNX 语音/知识库模型通过 **Git LFS** 管理。首次克隆后执行一次：

```bash
bash scripts/setup.sh
```

脚本会：检查/初始化 git-lfs → 配置 `core.hooksPath=.githooks` → 立即拉取模型文件。
配置后，后续 `git pull` / `git checkout` 会自动拉取更新的模型。

> ⚠️ 若未安装 Git LFS，`setup.sh` 与钩子会打印警告并提示安装（https://git-lfs.com）；
> 未拉取模型时，App 的端侧知识库/语音功能将不可用。

### 1. 环境准备
- Android Studio + JDK 17
- Android SDK Platform 36（`compileSdk`/`targetSdk` = 36；打开项目时 Android Studio 会自动提示下载缺失组件）
- 运行设备：Android 10+（`minSdk` = 29）

### 2. 配置
```bash
cp local.default.properties local.properties
# 编辑 local.properties，填入 API Key
```
关键配置项（详见 `local.default.properties`）：
- `LLM_API_KEY` / `LLM_API_URL` / `LLM_MODEL`：执行模型
- `PLANNER_API_KEY` / `PLANNER_API_URL` / `PLANNER_MODEL`：决策模型
- `DASHSCOPE_API_KEY`：GUI-Plus 视觉执行（为空自动回退 `LLM_API_KEY`）
- `VLM_API_KEY` / `VLM_MODEL`：VLM 屏幕描述
- `AMAP_API_KEY` / `AMAP_MCP_BASE_URL`：高德地图 MCP
- `BOCHA_API_KEY`：联网搜索（不配则降级 DuckDuckGo）

> 端侧知识库无需配置：App 内置 ONNX 模型 + 545 条 SOP，首次启动自动建库，**检索离线可用**（识图与操作需联网云端模型）。

### 3. 编译安装
```bash
./gradlew.bat assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```
首次启动端侧建库约 30-60s，完成后即可离线检索。

## 🏗️ 项目结构 & 🧠 技术架构

```
app/src/main/java/com/palmagent/app/
├── agent/     # 执行编排（DefaultAgentService / ActionExecutor / FailureCompactor / ContextManager）
├── kb/        # 端侧知识库（完全本地 RAG：ONNX 嵌入 + SQLite 向量 + 内存检索）
├── service/   # 服务层（无障碍 / OCR / VLM / GUI-Plus / 决策 / 搜索 / 保活）
├── tool/impl/ # 25 个动作工具（TapTool / AutoInputTool / SelectSpecTool…）
├── channel/   # 消息通道（微信机器人，可远程下发任务）
├── floating/  # 悬浮窗（追问 / 进度展示）
├── framework/ # DI(Hilt) / EventBus / 协程调度
├── ui/        # 界面（guide/home/settings/log/chat）
└── utils/     # KVUtils 配置中心
```

### 模型分工
| 角色 | 默认模型 | 部署方式 |
|------|---------|---------|
| 文本执行模型 | deepseek-v4-flash | 云端 API |
| 任务决策模型 | deepseek-v4-flash | 云端 API（独立配置） |
| 视觉执行 + 定位 | gui-plus-2026-02-26（百炼） | 云端 API |
| VLM 屏幕描述 | qwen3-vl-flash（百炼） | 云端 API |
| 知识库嵌入 | bge-small-zh-v1.5 INT8 | **端侧 ONNX Runtime** |

### 双模式任务编排
- **复杂模式**（默认）：用户请求 → 决策模型生成 Plan → 执行模型按 Plan 操作
- **简单模式**：用户请求 → 直接交执行模型（跳过决策层）

### 端侧知识库（完全本地 RAG）
545 条 SOP JSON → 端侧 bge-small-zh INT8 嵌入 → SQLite BLOB 向量 → 内存检索。
检索管线：关键词 + 向量（task 0.7 + keyword 0.3）→ RRF 融合（RRF_K=60）→ 阈值过滤 0.3。
**端侧知识库完全本地**：首次启动自动建库，知识库检索离线可用；屏幕识图与操作决策需联网云端模型。

## 🗓️ Roadmap（无障碍专项）

- [ ] **TalkBack/读屏兼容**：无障碍树通道适配系统读屏，读屏用户可直接使用
- [ ] **语音输入交互**：端侧 ASR，一句话直达（进一步降低门槛）
- [ ] **大字/高对比模式**：界面适配低视力
- [ ] **家人远程协助**：消息通道一键接管，子女远程帮老人操作
- [ ] **适老化场景包**：常用 App 高频任务一键预置

## 📢 News

- **决策模型上下文控制**：新增任务工作区（workspace_update），工具结果由框架自动清理，
  上下文有界，防膨胀（对齐 Anthropic Context Editing 做法）
- **执行引擎增强**：FailureCompactor 失败跨轮记忆 + 工具熔断，防重试风暴烧 token
- **视觉流程修复**：修复 OCR HARDWARE 位图崩溃、输入降级 instruction 丢失
- **端侧知识库完全本地**：知识库检索离线可用、数据不出手机；屏幕识图与操作决策需联网云端模型

## 📄 License

[Apache License 2.0](LICENSE)

---

**相关文档**：[使用场景](docs/use-cases.md) · [架构说明](docs/architecture.md) · [评估结果](docs/evaluation.md) · [部署引导](docs/DEPLOY.md) · [依赖与合规](docs/DEPENDENCIES.md) · [开源边界](docs/OPEN-SOURCE-BOUNDARY.md) · [评委验证清单](docs/VERIFICATION-CHECKLIST.md)
