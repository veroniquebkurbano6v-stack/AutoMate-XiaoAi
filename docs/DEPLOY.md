# AutoMate·小艾 部署引导（评委验证路径）

> 本文档给评委/开发者提供一条**从零到跑通任务**的最短路径。
> 建议按顺序：① 装 APK 直接体验 → ② 从源码构建 → ③ 复现评测。
> 三件事都不需要自己训练任何模型，也不需要本地 GPU。

---

## 一、两条验证路径总览

| 路径 | 前置条件 | 耗时 | 能验证什么 |
|------|---------|------|-----------|
| **A. 装 APK 直接体验**（推荐优先） | 一台 Android 10+ 真机 | 约 5 分钟 | 端到端真实任务闭环（端侧知识库 + 双模型编排 + 无障碍/视觉执行） |
| **B. 从源码构建** | Windows/Linux/macOS + JDK 17 + Android SDK | 约 15 分钟 | 工程可复现性：源码 → APK 全链路可构建 |
| **C. 复现评测（无真机也可）** | PC + Python 3.10+ | 约 10 分钟 | 检索命中率 / 视觉消融数据可复现 |

> 路径 A 安装作品附件 `AutoMate-XiaoAi-v1.0.apk`（**不含任何 API Key**，安全合规），安装后在
> App「设置」页自行填写模型 Key 即可使用；路径 B 构建出的为公开版（同样不内置 Key），
> 对应 GitHub Release 的 `AutoMate-XiaoAi-v1.0-noKey.apk`。

---

## 二、路径 A：装 APK 直接体验（约 5 分钟）

### A1. 安装

```bash
adb install AutoMate-XiaoAi-v1.0.apk
```

也可将 APK 拷贝到手机，点击安装（需允许"安装未知来源应用"）。

### A2. 授权无障碍服务（关键）

打开 App → 跟随引导进入「设置」开启 **AutoMate·小艾 无障碍服务**。
为让无障碍服务具备开机自动恢复能力，需一次性授予系统级写权限：

```bash
# 一次性授权（仅需执行一次；不执行不影响基本使用，仅影响服务崩溃后自动恢复）
adb shell pm grant com.palmagent.app android.permission.WRITE_SECURE_SETTINGS
```

### A3. 跑一个真实任务

1. 打开 App，在输入框输入（语音/文本均可）：
   - `帮我在淘宝点杯奶茶`
   - `用微信给联系人发消息`
   - `导航去最近的医院`
2. 观察：任务被自动路由到对应 App → 逐步完成搜索/定位/输入/点击 → 完成后汇报。
3. 涉及支付/转账等**不可逆操作**时，App 会先弹出确认（`supervised` 拦截），不会越权执行。

### A4. 验证端侧知识库（离线能力）

- 首次启动 App 自动建库约 30–60 秒，完成后**断网**再发任务，检索与执行仍可用。
- 知识库为完全端侧 RAG（ONNX 嵌入 + SQLite 向量），隐私不出手机。

> 说明：APK **不含任何 API Key**。需在 App「设置」页填写 Key 后，云端 LLM 推理（执行/决策/视觉定位）
> 才可用；未配置或网络不可用时，端侧知识库检索始终可用。

---

## 三、路径 B：从源码构建（约 15 分钟）

### B1. 环境要求

- JDK 17
- Android Studio（含 **Android SDK Platform 36**；`compileSdk`/`targetSdk` 均为 36。
  打开项目时 Android Studio 会自动提示下载缺失的 SDK 组件，同意安装即可）
- Git
- 运行设备：Android 10+（`minSdk` = 29）

### B2. 克隆并配置

```bash
git clone https://github.com/veroniquebkurbano6v-stack/AutoMate-XiaoAi.git
cd AutoMate-XiaoAi
cp local.default.properties local.properties
# 编辑 local.properties，填入 API Key + 修改 sdk.dir（见下方）
```

> ⚠️ **`sdk.dir` 是构建必改项**：模板默认值是 `sdk.dir=D\:\\Android\\SDK`，
> 必须改成**你自己电脑的 Android SDK 绝对路径**（可在 Android Studio
> `Settings → SDK Location` 查看），否则 `gradlew` 会因找不到 SDK 报错。
> 若你已配置 `ANDROID_HOME` 环境变量，也可删掉 `sdk.dir` 这一行。

> `local.properties` 已被 `.gitignore` 忽略，不会提交到仓库。

### B3. 配置项说明（API Key）

| 配置项 | 用途 | 获取 | 缺失时的行为 |
|--------|------|------|-------------|
| `LLM_API_KEY` | 文本执行模型（DeepSeek-V4-Flash） | https://platform.deepseek.com | 无法执行 |
| `PLANNER_API_KEY` | 任务决策模型（复杂任务生成 Plan） | https://platform.deepseek.com | 退化为简单模式（直接执行） |
| `VLM_API_KEY` | VLM 屏幕描述（Qwen3-VL-Flash，复用百炼 Key 即可） | https://bailian.console.aliyun.com | 视觉描述降级 |
| `DASHSCOPE_API_KEY` | GUI-Plus 视觉定位执行 | https://bailian.console.aliyun.com | 自动回退用 `LLM_API_KEY` |
| `KEYBOARD_VLM_API_KEY` | 键盘弹出检测（智谱 GLM-4V-Flash，可空） | https://open.bigmodel.cn | 回退用主 VLM |
| `COMPACT_API_KEY` | 上下文压缩（GLM-4.5-Flash，可空） | https://open.bigmodel.cn | 回退用决策模型 |
| `AMAP_API_KEY` | 高德地图 MCP（位置/导航） | https://lbs.amap.com | 导航类任务不可用 |
| `BOCHA_API_KEY` | 联网搜索（可空） | https://open.bochaai.com | 降级 DuckDuckGo |

**最小可用配置**：只需填 `LLM_API_KEY` 即可跑通"一句话 → 执行"链路；
其余 Key 缺失按上表降级。**端侧知识库无需任何配置**。

### B4. 编译安装

```bash
# Windows
./gradlew.bat assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
# macOS / Linux
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

构建产物默认不含 API Key（Key 通过构建时从 `local.properties` 注入 BuildConfig；若填写了 Key，
构建出的 APK 会内置该 Key）。**作品附件与 GitHub Release 提供的均为空 Key 构建的无 Key 版**，
从源头避免 API Key 泄露。

---

## 四、路径 C：无真机复现评测（约 10 分钟）

无需 Android 设备，在 PC 上直接复现知识库检索与视觉评测。

```bash
cd AutoMate-XiaoAi

# 0. 安装评测依赖（两个脚本分别需要）
pip install onnxruntime numpy        # eval_retrieval.py 所需
pip install Pillow requests          # eval_vision.py 所需

# 1. 知识库检索评测（32 场景，与论文/技术方案同口径）
python eval/eval_retrieval.py

# 2. 视觉鲁棒性 / 提示词消融评测（185 张真实截图）
#    需配置 VLM API Key（可用智谱 GLM-4V-Flash 免费额度）
python eval/eval_vision.py
```

- 检索评测使用与 App 端**同一 ONNX 嵌入模型、同一 RRF 融合参数**（`LocalKbEngine` 同口径），结果可直接复现 `docs/evaluation.md` 中的 32/32 = 100% 命中率。
- 更多数据审计脚本（去重/质量审计）：`eval/audit_dedup.py`、`eval/audit_sop_quality.py`。

---

## 五、知识库数据来源

App 内置的 **545 条离线 SOP**（`app/src/main/assets/kb/sop_raw/`）来源于
**CAGUI 数据集**（OpenBMB 开源的国内 Android GUI 智能体轨迹数据集，CC-BY-NC 4.0）
的**后处理结果**，生成方式为：

1. 取 CAGUI `CAGUI_agent/domestic/` 下真实任务轨迹（含每步截图、点击/长按坐标、输入文字、操作类型）；
2. 用**本地视觉模型逐帧识图**，结合轨迹中的真实操作信息（坐标/文字/动作类型），
   由视觉模型输出每步的具体操作描述（如"点击搜索框"、"点击联系人头像"）；
3. 人工/脚本抽检修正，去重、统一字段规范后形成 545 条 SOP（含 `task_name` 泛化、
   `app_name`、`domain`、`keywords`、分步 `steps`）。

详细合规说明（许可、边界）见 [DEPENDENCIES.md](DEPENDENCIES.md) 与 [OPEN-SOURCE-BOUNDARY.md](OPEN-SOURCE-BOUNDARY.md)。

---

## 六、常见问题（FAQ）

| 问题 | 排查 |
|------|------|
| 装 APK 后点击任务没反应 | 确认无障碍服务已开启；确认已授予悬浮窗权限 |
| 无障碍服务总被系统杀掉 | 执行 `adb shell pm grant ... WRITE_SECURE_SETTINGS` 一次性授权，App 会自动恢复服务 |
| 任务卡在"检索知识库" | 首次建库需 30–60s，等待完成；或检查 `设置 → 启用知识库` 开关 |
| 构建报错找不到 SDK | 在 `local.properties` 中设置 `sdk.dir` 指向本机 Android SDK |
| 想换模型/Key | 编辑 `local.properties` 的 `LLM_MODEL` / `VLM_MODEL` 等，重新构建即可 |
| 演示时网络差 | 端侧知识库离线可用；云端推理部分尽量用真机+稳定网络 |
| 跑 `testDebugUnitTest` 看到 6 个用例被跳过 | 属预期行为：这 6 个是云端 VLM/LLM 集成用例（`CloudVlmScreenDescTest` / `DecisionDialogAutonomousToolCallTest` / `VlmImageConfigIntegrationTest`），需真实 API Key 才能运行；无 Key 时按 `assumeTrue` 设计优雅跳过，配置 `VLM_API_KEY` / `PLANNER_API_KEY` 后会自动补跑 |

---

## 相关文档

- [README](../README.md)：项目简介与特性
- [DEPENDENCIES.md](DEPENDENCIES.md)：依赖清单、数据来源与合规
- [OPEN-SOURCE-BOUNDARY.md](OPEN-SOURCE-BOUNDARY.md)：开源边界
- [VERIFICATION-CHECKLIST.md](VERIFICATION-CHECKLIST.md)：评委验证清单
- [evaluation.md](evaluation.md)：评测数据
- [architecture.md](architecture.md)：技术架构
