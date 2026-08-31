# AutoMate·小艾 依赖与合规清单

> 本文档为官方要求的"README、部署说明、依赖清单、数据/模型/商业 API 来源及合规说明"汇总。
> 分四部分：① 云端模型与 API ② 第三方库 ③ 知识库数据来源 ④ 合规声明。

---

## 一、云端模型与商业 API

### 1.1 大语言模型（LLM / VLM）

| 角色 | 默认模型 | 提供方 | 调用方式 | 是否需要 Key | 缺失降级 |
|------|---------|--------|---------|-------------|---------|
| 文本执行模型 | DeepSeek-V4-Flash | DeepSeek | OpenAI 兼容 API | 是 | 无法执行 |
| 任务决策模型 | DeepSeek-V4-Flash | DeepSeek | OpenAI 兼容 API | 是 | 退化为简单模式 |
| 屏幕视觉描述 VLM | Qwen3-VL-Flash | 阿里云百炼 | DashScope OpenAI 兼容 API | 是（可复用百炼 Key） | 视觉描述降级 |
| 键盘弹出检测 VLM | GLM-4V-Flash | 智谱 AI | OpenAI 兼容 API | 可选 | 回退主 VLM |
| 上下文压缩模型 | GLM-4.5-Flash | 智谱 AI | OpenAI 兼容 API | 可选 | 回退决策模型 |

- 所有模型均为**公开商业 API**，按各平台条款合规使用；Key 仅存于本地 `local.properties`，**不随源码仓库提交**，**所有 APK 均为无 Key 构建**。
- 各平台均提供免费额度/低成本档位（DeepSeek、百炼 Qwen3-VL-Flash、智谱 GLM Flash 系列），个人开发者可低成本复现。

### 1.2 专用接口 / MCP

| 接口 | 提供方 | 用途 | 缺失行为 |
|------|--------|------|---------|
| 高德地图 MCP | 高德开放平台 | 位置/地图搜索/路线规划/天气 | 导航类任务不可用 |
| 博查 AI Search | 博查 | 联网搜索 | 降级 DuckDuckGo |

### 1.3 云端视觉执行（GUI-Plus）

| 接口 | 提供方 | 用途 | 缺失行为 |
|------|--------|------|---------|
| GUI-Plus（gui-plus-2026-02-26） | 阿里云百炼 | 截图 + 指令 → 动作/坐标，视觉定位执行 | 回退 `LLM_API_KEY`（同为百炼 Key） |

---

## 二、第三方开源库

| 组件 | 用途 | 开源协议 |
|------|------|---------|
| ONNX Runtime（Android） | 端侧嵌入模型推理 | MIT |
| bge-small-zh-v1.5（INT8 量化） | 中文文本向量嵌入 | MIT（BAAI，模型权重按其协议） |
| SQLite | 端侧向量持久化 | Public Domain |
| Room 2.7.2 | 会话/消息持久化 | Apache-2.0 |
| Hilt | 依赖注入 | Apache-2.0 |
| EventBus | 事件总线 | Apache-2.0 |
| Kotlin 协程 | 并发调度 | Apache-2.0 |
| Gson | JSON 序列化 | Apache-2.0 |

> 完整 Gradle 依赖以 `app/build.gradle.kts` 为准。

---

## 三、知识库数据来源（重点）

### 3.1 原始来源：CAGUI 数据集

- **数据集**：CAGUI（Chinese Android GUI Benchmark），由 **OpenBMB** 开源发布，用于评测 GUI 智能体模型的 Grounding 与 Agent 能力。
- **地址**：HuggingFace `openbmb/CAGUI`
- **许可**：**CC-BY-NC 4.0**（非商业研究用途）。
- **原始内容**：`CAGUI_agent/domestic/` 目录下每个 `episode_id` 对应一条真实 Android 任务轨迹，包含：
  - 每步截图（`<episode_id>_N.jpeg`）
  - 用户目标指令（`instruction`）
  - 每步操作信息：点击坐标（`result_touch_yx`）、长按/抬起坐标（`result_lift_yx`）、输入文字（`result_action_text`）、操作类型（`result_action_type`，含点击/长按/输入/滚动等）

### 3.2 后处理流程（本项目如何生成 514 条 SOP）

App 内置知识库（`app/src/main/assets/kb/sop_raw/`，514 条 JSON）为 CAGUI **后处理结果**，流程如下：

1. **取轨迹**：从 CAGUI `domestic/` 选取真实任务轨迹；
2. **视觉识图**：用**本地视觉模型**（Ollama 部署的 Qwen3-VL 系列）对轨迹每帧截图进行识别，
   理解界面内容与可交互元素；
3. **融合操作信息**：将视觉识别结果与 CAGUI 轨迹中记录的**真实操作信息**（点击坐标、长按坐标、
   输入文字、操作类型）对齐，由视觉模型输出**每步的具体操作描述**（如"点击搜索框"、"点击联系人头像"）；
4. **结构化**：按统一 schema 生成 SOP：`sop_id / original_task_name / task_name（泛化去实体名）/
   app_name / source / difficulty / domain / keywords / steps（goal + expected + action_type）`；
5. **清洗与质控**：去重、剔除无效条目、人工抽检修正，形成最终 514 条离线 SOP。

> 生成的 SOP 已将任务名**泛化**（如"点击品牌店铺名"而非具体店名），并在步骤描述中使用
> 通用词汇描述 UI 元素，避免绑定具体实体，保证对新 App 的通用性。

### 3.3 数据统计

| 项 | 值 |
|----|----|
| SOP 数量 | 514 条 |
| 嵌入模型 | bge-small-zh-v1.5 INT8 ONNX（端侧） |
| 检索 | 向量(task 0.7 + keyword 0.3) + 关键词 → RRF 融合(RRF_K=60)，阈值 0.3 |
| 评测 | 32 场景检索命中率 100%（top-1 / top-3） |

> **数量说明**：原始 CAGUI 轨迹经后处理后为 586 条候选，其中部分条目**操作路径完全一致、
> 仅个别实体（如具体店名/人名/商品名）不同**，综合考量予以合并删去，最终保留 **514 条**。
> 这也与"步骤描述使用通用词汇、避免绑定具体实体"的泛化设计一致。

---

## 四、合规声明

1. **模型/API 合规**：所有云端模型、商业 API（DeepSeek、阿里云百炼、智谱、高德、博查）均为公开服务，
   本项目按各自《服务协议》正常调用，**未做任何逆向、抓取或规避付费的行为**；Key 不随源码提交。
2. **数据合规**：知识库 SOP 派生自 CAGUI 数据集（**CC-BY-NC 4.0**），本项目用途为
   **学术竞赛（非商业研究）**，符合该许可的非商业条款；对 CAGUI 数据集已按要求保留署名引用。
   - 若本项目后续用于商业场景，需替换或另行获得许可的数据来源（详见 OPEN-SOURCE-BOUNDARY.md）。
3. **隐私合规**：端侧知识库完全本地运行，数据不出手机；聊天记录本地持久化（Room），
   不采集、不上传用户个人数据。
4. **代码合规**：第三方库均采用宽松/开源协议（见第二部分），无闭源依赖除商业 API 之外的情形。
5. **授权边界**：App 使用无障碍服务仅用于本地任务执行，不读取敏感信息；涉及支付等
   不可逆操作时需用户确认（`supervised` 机制）。

---

## 相关文档

- [DEPLOY.md](DEPLOY.md)：部署引导
- [OPEN-SOURCE-BOUNDARY.md](OPEN-SOURCE-BOUNDARY.md)：开源边界
- [VERIFICATION-CHECKLIST.md](VERIFICATION-CHECKLIST.md)：评委验证清单
