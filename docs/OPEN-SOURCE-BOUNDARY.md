# AutoMate·小艾 开源边界说明

> 一句话：**源码全部开源（Apache-2.0）；所有 APK 均不含任何 API Key（无 Key 构建）；
> 仅"受许可约束的派生数据"不属于开源范围**。以下逐项说明，供评审核查合规性。

---

## 一、开源内容（Apache License 2.0）

| 内容 | 说明 |
|------|------|
| 全部源代码 | `app/` 下 Android 工程源码，Apache-2.0 |
| 构建脚本 / 配置文件 | Gradle 配置、`local.default.properties` 配置模板（不含真实 Key） |
| 评测脚本 | `eval/` 下检索/视觉评测、数据审计脚本 |
| 文档 | README、docs/ 下全部技术文档 |
| 落地页 | `docs/index.html`（GitHub Pages） |

## 二、不随仓库公开的内容

| 内容 | 说明 | 为什么不开源 |
|------|------|-------------|
| `local.properties` | 含真实 API Key 的本地配置 | 安全要求，已在 `.gitignore` 中排除 |
| 商业 API Key 本身 | 用户在 `local.properties` / App「设置」页填写的模型 Key | 属个人凭据，不随仓库/APK 提交；**所有 APK 均为无 Key 构建** |
| CAGUI 原始数据与截图 | 含 App 界面截图的原始轨迹数据 | 受 CAGUI **CC-BY-NC 4.0** 许可约束，且原始截图含第三方 App 界面 |

## 三、知识库数据的许可边界（重要）

- 知识库 **545 条 SOP 为 CAGUI 数据集的后处理结果**（后处理流程见 DEPENDENCIES.md 3.2 节）。
- CAGUI 原始数据集许可为 **CC-BY-NC 4.0**（非商业研究用途）。
- 因此：
  - ✅ **非商业场景**（学术竞赛、科研、个人学习）可自由使用本知识库数据；
  - ⚠️ **商业场景**需替换数据来源或另行获得许可。
- 说明：本项目参赛性质为**非商业研究/开源竞赛**，符合 NC 条款；`sop_raw/*.json` 已去除
  截图等受限内容，仅保留文本化的操作步骤描述，进一步降低了合规风险。
- 已按要求保留对 CAGUI 数据集的**署名引用**（引用信息见下）。

### 引用

> CAGUI: Chinese Android GUI Benchmark. OpenBMB. https://huggingface.co/datasets/openbmb/CAGUI
> (License: CC-BY-NC 4.0)

## 四、模型与 API 调用边界

- 本项目使用**公开商业 API**（DeepSeek、阿里云百炼、智谱、高德、博查），
  属于正常第三方服务调用，不构成对模型的开源要求。
- 端侧嵌入模型 bge-small-zh-v1.5（BAAI）与 ONNX Runtime 均为宽松许可，可随项目分发。
- 未使用任何需授权/闭源推理框架。

## 五、对评审的说明

1. **开源 ≠ 全部公开**：按大赛"开源并不等于必须将项目所有内容完全公开"的规则，
   本项目开源源码、文档、评测脚本与落地页；所有 APK 均为**无 Key 构建**，不包含任何
   商业 API Key，仅对"受限许可的原始数据"设置边界。
2. **可复现性不受影响**：评委可用公开源码 + 自己申请的 Key（均可免费/低成本获取）完整复现
   全部功能与评测数据，无需依赖任何内置 Key 的 APK。

---

## 相关文档

- [DEPLOY.md](DEPLOY.md)：部署引导
- [DEPENDENCIES.md](DEPENDENCIES.md)：依赖清单、数据来源与合规
- [VERIFICATION-CHECKLIST.md](VERIFICATION-CHECKLIST.md)：评委验证清单
