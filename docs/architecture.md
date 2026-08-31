# 架构说明

AutoMate·小艾 是一个 **Android 原生 App**（Kotlin），把"看得懂屏幕的 AI"装进手机，
用户一句话即可完成手机操作。核心是 **决策-执行两层模型 + 多通道感知 + 端侧知识库**。

## 端到端流程

```
用户一句话（语音/文本/远程通道）
      │
      ▼
┌─────────────────────────────────────────────────────────┐
│ 决策模型（意图路由 + 规划 + 工具链）                        │
│   list_apps 确认应用 → kb_read 查端侧 SOP → amap_* 地理信息 │
│   → web_search 实时信息 → ask_questions 追问澄清            │
│   输出结构化 Plan（步骤 + 完成标志 + 是否需监督）             │
└─────────────────────────────────────────────────────────┘
      │ Plan
      ▼
┌─────────────────────────────────────────────────────────┐
│ 执行模型（逐步骤执行，最多 25 个动作工具）                   │
│   多通道感知（每轮）                                       │
│    ├─ 无障碍树（文本通道，读屏/无障碍用户友好）              │
│    ├─ 视觉截图 + GUI-Plus（坐标直出，视障用户友好）          │
│    └─ 端侧知识库 SOP（离线指引）                           │
│   tap / long_press / swipe / scroll / auto_input /        │
│   select_spec / open_app / locate / finish ...            │
└─────────────────────────────────────────────────────────┘
      │ 完成 / 需确认 / 敏感操作
      ▼
用户汇报（完成范围；支付等敏感步骤由用户确认，执行模型不越权）
```

## 两层模型分工

| 层 | 角色 | 关键能力 |
|----|------|---------|
| 决策模型 | 意图路由 + 规划 | 判断是操作任务还是闲聊/查询；通过工具链收集上下文；生成结构化 Plan |
| 执行模型 | 落地操作 | 读取屏幕（多通道感知）→ 决策下一步动作 → 调用工具 → 判定完成 |

- **复杂模式**（默认）：用户请求 → 决策生成 Plan → 执行按 Plan 操作
- **简单模式**：用户请求 → 直接交执行模型（跳过决策层）

## 多通道感知（无障碍核心）

一条指令，两条"眼睛"，总有一条能看到屏幕：

```
屏幕
 ├─ 无障碍树（AccessibilityNodeInfo）─── 文本通道：包名/节点/文本/坐标
 │     └─ 适用于读屏用户、无障碍树质量高时（低耗、低延迟）
 ├─ 截图 + VLM 描述 ─────────────────── 语义通道：屏幕"是什么"（GLM-5.3-Flash / qwen3-vl-flash）
 └─ 截图 + GUI-Plus Grounding ────────── 视觉通道：指令 → 精确动作坐标（gui-plus）
```

选择策略：`无障碍树 > VLM屏幕描述+GUI-Plus > GUI-Plus Grounding`（见 `DefaultAgentService` 信息获取策略）。
低视力用户看不清但截图视觉仍可用 → 视觉通道兜底；无障碍树被 App 屏蔽时 → 视觉优先。

## 端侧知识库（完全本地 RAG，隐私不出手机）

```
assets/kb/ 514 条 SOP JSON
      │ 首次启动
      ▼
bge-small-zh INT8 ONNX 嵌入（端侧推理）
      ▼
SQLite BLOB 持久化向量（后续启动直接读库）
      ▼
检索管线：关键词 + 向量(RRF 融合) → 阈值过滤 → top-k
      │
      ▼
执行模型获得"怎么做"的离线指引（端到端 <80ms）
```

- **无服务端、无网络依赖**：完全端侧，首次启动后台建库 30-60s
- 检索：向量(task 0.7 + keyword 0.3) + 关键词子串 → RRF 融合(RRF_K=60) → score<0.3 过滤
- 决策模型 `kb_read` 工具按 App 过滤查询，只取最高分 SOP 控制上下文

## 决策模型上下文控制（工作区 + 工具结果自动清理）

决策模型在工具循环中不再无限累积工具结果：

```
每轮：框架清理 → 只保留最近 1 轮工具结果（assistant.tool_calls + role=tool 成对）
     └ 模型通过 workspace_update 把关键信息（App+包名/SOP要点/用户确认项）写入工作区
       工作区作为 system 常驻块注入，生成 Plan 只依赖工作区
```

对齐业界做法（Anthropic Context Editing / OpenAI Responses context_management）：
**框架确定性清理工具结果 + 模型 Memory 式写入工作区**，控制上下文有界、防膨胀。

## 关键模型配置

| 角色 | 默认模型 | 部署 |
|------|---------|------|
| 执行模型 | deepseek-v4-flash | 云端 API |
| 决策模型 | deepseek-v4-flash | 云端 API |
| 视觉执行/GUI-Plus | gui-plus-2026-02-26（百炼） | 云端 |
| VLM 屏幕描述 | qwen3-vl-flash（百炼） | 云端 |
| 知识库嵌入 | bge-small-zh-v1.5 INT8 | **端侧 ONNX** |

## 代码分层

```
app/src/main/java/com/palmagent/app/
├── agent/     # 执行编排（DefaultAgentService / ActionExecutor / FailureCompactor / ContextManager）
├── kb/        # 端侧知识库（LocalKbEngine / OnnxEmbedder / KbDbAccessor ...）
├── service/   # 服务层（无障碍 / OCR / VLM / GUI-Plus / 决策 / 搜索 / 保活）
├── tool/impl/ # 25 个动作工具（TapTool / AutoInputTool / SelectSpecTool ...）
├── channel/   # 消息通道（微信机器人，可远程下发任务）
├── floating/  # 悬浮窗（追问 / 进度展示）
├── framework/ # DI(Hilt) / EventBus / 协程调度
├── ui/        # 界面（guide/home/settings/log/chat）
└── utils/     # KVUtils 配置中心
```
