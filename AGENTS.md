# AGENTS.md

AutoMate·小艾：Android GUI 智能助手（Kotlin），端侧 RAG + 云端多模型编排。单模块 Gradle 工程，代码全部在 `app/`。

## 构建 / 测试 / Lint
- 构建（Windows 环境，用 `.bat`）：`./gradlew.bat assembleDebug`，APK 输出 `app/build/outputs/apk/debug/app-debug.apk`
- 单元测试（纯 JVM，无需设备）：`./gradlew.bat :app:testDebugUnitTest`（`app/src/test` 下 22 个测试文件）
- 插桩测试：`./gradlew.bat :app:connectedDebugAndroidTest`（需真机/模拟器 + 无障碍授权）
- Lint：`./gradlew.bat lint`（默认 Android lint，无自定义规则文件；**未配置 ktlint/detekt，没有格式校验命令**）
- 工具链：Gradle 8.13 / AGP 8.13.2 / Kotlin 2.2.21 / KSP / Hilt / Room。版本统一在 `gradle/libs.versions.toml`；直接用坐标声明的依赖（Timber、onnxruntime-android、security-crypto、recyclerview 等）不在目录中，不要把它们搬进 toml。
- **UI 是经典 View 体系（`ComponentActivity` + RecyclerView + XML 布局），不是 Compose**——`libs.versions.toml` 里虽有 compose BOM，但 `app/build.gradle.kts` 未引入任何 compose 依赖，改动 UI 走 XML + View 路线。

## 配置（最容易踩坑）
- API Key 全部在 `local.properties`（已 gitignore）中，从 `local.default.properties` 复制模板。`app/build.gradle.kts` 会在文件缺失时**自动回退读 `local.default.properties`**，因此缺配置也能编译通过，但运行时 Key 为空串、功能不可用——排查"编译成功但没反应"先查这个。
- Key 经 `buildConfigField` 注入 BuildConfig；`DASHSCOPE_API_KEY` 为空时自动回退 `LLM_API_KEY`（同百炼账号额度）；`COMPACT_*`（上下文压缩）未配置时运行时回退 Planner 配置；`BOCHA_API_KEY` 未配置时 WebSearchService 降级 DuckDuckGo。
- 配置入口统一走 `utils/KVUtils.kt`（SharedPreferences + 加密存储），`config/Config.kt` **已废弃**（新代码用 KVUtils）。启动时 `forceImportFromBuildConfig()` 无条件用 BuildConfig 覆盖 SharedPreferences，local.properties 清空后界面会正确显示"未配置"。
- 模式开关（KVUtils）：`isComplexModeEnabled`（复杂/简单模式，默认 true）、`isLocalKbEnabled`（端侧知识库，默认 true）、`isVisionModeEnabled`（视觉模式，默认 false）、`isGuiOwlEnabled`（GUI-Plus，默认 true）、`isExecutionSearchEnabled`（联网搜索，默认 true）、`isAmapMcpEnabled`（高德 MCP，默认看 Key）。
- `settings.gradle.kts` 依赖仓库优先阿里云镜像（国内网络），新增依赖注意解析源与 `FAIL_ON_PROJECT_REPOS` 约束。

## 架构
- 根包 `com.palmagent.app`：
  - `agent/`：执行编排与决策（DefaultAgentService、ActionExecutor、ContextManager、FailureCompactor、TaskProgressTracker 等）
  - `kb/`：端侧 RAG（ONNX 嵌入 + SQLite BLOB + 内存检索，完全本地无 HTTP）
  - `service/`：服务层（无障碍 GUIAccessibilityService、AIService、决策 DecisionDialogService、GUI-Plus GuiOwlService、VLM VlmService、WebSearchService、WebMCPService、保活等）
  - `tool/impl/`：动作工具，继承 `BaseTool` 并注册进 `ToolRegistry`
  - `channel/`：消息通道（微信机器人 WeChatChannelHandler 等）
  - `floating/`：悬浮窗（AskUserManager、FloatingProgressManager、UserActionManager）
  - `ui/`：经典 View 界面（guide / home / settings / log / chat）
  - `framework/`：DI（AgentModule）、EventBus、协程调度
  - `domain/` + `data/`：UseCase / Repository 分层；`model/`：数据模型
  - `utils/KVUtils.kt`：配置中心
- 双模式编排：复杂模式 = 决策模型生成 Plan → 执行模型按 Plan 操作；简单模式跳过决策层。改动模型角色/工具时对照 `agent/AgentConfig.kt`、`utils/KVUtils.kt`、`service/PromptBuilder.kt`（System Prompt 统一入口，按 执行模式×复杂模式 四分）。
- 决策模型工具链（DecisionDialogService）：kb_read / list_apps / amap_search / amap_nearby / amap_directions / amap_weather / web_search / 追问。
- DI 用 Hilt（`framework/di/AgentModule.kt`）+ KSP；知识库 SQLite 用 Room；kb.db 为自建 SQLite（KbDbAccessor 管理，BLOB 存向量）。
- 模型角色（local.default.properties 默认值）：LLM=deepseek-v4-flash（执行）、PLANNER=deepseek-v4-flash（决策）、VLM=qwen3-vl-flash（屏幕描述）、KEYBOARD_VLM=glm-4v-flash（键盘检测）、COMPACT=glm-4.5-flash（上下文压缩）、GUI-Plus=gui-plus-2026-02-26（视觉执行，百炼 DashScope）。

## 工具清单（ToolRegistry，21 个）
tap / long_press / swipe（方向滚动+direction参数 / scroll_until / back / home / wait / finish / open_app / list_apps / auto_input / locate / get_screen_info / visual_describe / user_action / select_spec / amap_search / amap_nearby / amap_directions / amap_weather / web_search

新增工具只需在 `ToolRegistry` 的 `toolClasses` 加一行；参数在工具类 `getParameters()` 声明，AI 描述由 `getToolDescriptionsForAI()` 自动生成。

## Gotchas
- 注释与日志以中文为主，保留既有中文注释，不翻译不删改。
- `aaptOptions.noCompress("tflite", "onnx")`：ONNX/TFLite 模型必须保持未压缩（运行时 mmap 加载），勿改。
- 知识库资产在 `app/src/main/assets/kb/`（约 23MB ONNX 模型 + **514 条** SOP JSON，共约 26MB）；首次启动后台建库 30–60s，用 Logcat 标签 `LocalKbEngine` 排查；设置页可触发"重新入库"（`LocalKbEngine.rebuild`）。
- 检索管线：embed(query) → 向量检索 + 关键词检索（各取 50 候选）→ RRF 融合（RRF_K=60）→ 阈值过滤 0.3 → top_k（1-5，默认 3）。置信度：score≥0.55 high / ≥0.45 medium / 其余 low。
- 单元测试配置了 `unitTests.isReturnDefaultValues = true`，JVM 测试中 Android 框架调用返回默认值，断言不要依赖真实系统行为；测试用 mockito-inline + coroutines-test。
- 保活：Manifest 声明 `WRITE_SECURE_SETTINGS`（ADB 授权）→ BootReceiver / ScreenStateReceiver / KeepAliveJobService / A11yTileService（通知栏快捷开关）负责恢复无障碍服务。
- 无用户明确要求时不做 git commit/push。
