# AutoMate·小艾 评委验证清单

> 给评委的一页纸引导。按 1→2→3→4 顺序即可在最短时间内完成对项目
> **可运行性、可复现性、工程深度、合规性**的核查。

---

## 0. 先看（1 分钟）

- [ ] 仓库：GitHub 主仓库 https://github.com/veroniquebkurbano6v-stack/AutoMate-XiaoAi ｜ GitCode 镜像 https://gitcode.com/weigai666/AutoMate-XiaoAi
- [ ] 落地页（GitHub Pages）：`docs/index.html` —— 产品定位 + 端到端演示视频
- [ ] README：项目简介、核心特性、评估摘要
- [ ] 技术方案 PDF：`docs/tech-solution.pdf` / 报名材料 `03_技术方案`

## 1. 看演示视频（3 分钟）

| 视频 | 验证点 |
|------|--------|
| 项目总览（3min） | 产品定位 · 端侧架构 · 多段任务实录 |
| 用淘宝点奶茶 | 一句话 → 跨页搜索 → 选规格 → 加购（端到端闭环） |
| 用微信发消息 | 一句话 → 定位联系人 → 输入 → 发送 |
| 高德导航 | 附近检索 → 路线规划 → 导航 |
| 高德分享位置到微信 | 跨 App 协作：检索店铺 → 分享定位给微信联系人 |

## 2. 跑真机任务（约 5 分钟，可选）

- [ ] 安装 `AutoMate-XiaoAi-v1.0.apk`（不含任何 API Key），在 App「设置」页自行填写模型 Key
- [ ] 开启无障碍服务（必要时执行 `adb shell pm grant com.palmagent.app android.permission.WRITE_SECURE_SETTINGS`）
- [ ] 输入 `帮我在淘宝点杯奶茶` → 观察 AI 自主完成全流程
- [ ] 输入 `用微信给联系人发消息` → 观察定位/输入/发送
- [ ] （可选）断网测试：端侧知识库离线检索仍可用

## 3. 复现评测数据（约 10 分钟，无真机也可）

- [ ] `python eval/eval_retrieval.py` → 32/32 场景 100% 检索命中率（与 `docs/evaluation.md` 一致）
- [ ] `python eval/eval_vision.py` → 视觉鲁棒性/提示词消融（185 张真实截图）
- [ ] `python eval/audit_dedup.py` / `audit_sop_quality.py` → 知识库数据质量审计
- [ ] `./gradlew.bat :app:testDebugUnitTest` → 348 个用例全部通过（其中 3 个云端 VLM/LLM 集成用例因未配置 API Key 按设计跳过，属预期行为；在 `local.properties` 配置 `VLM_API_KEY` / `PLANNER_API_KEY` 后会自动运行）

> 检索评测与 App 端**同一 ONNX 模型、同一 RRF 参数**，结果可直接复现。

## 4. 核查工程与合规（5 分钟）

- [ ] **从源码可构建**：`cp local.default.properties local.properties` → 填 Key → `./gradlew assembleDebug`
- [ ] **依赖清单**：见 `docs/DEPENDENCIES.md`（模型/API/第三方库/数据来源）
- [ ] **开源边界**：见 `docs/OPEN-SOURCE-BOUNDARY.md`（哪些开源、哪些不公开及原因）
- [ ] **无 Key 泄露**：`git status` / 仓库与 APK 中均无 API Key（APK 为无 Key 构建）；Key 仅在本地 `local.properties` 配置
- [ ] **数据合规**：知识库派生自 CAGUI（CC-BY-NC 4.0），本项目为非商业研究用途，已保留署名引用
- [ ] **权限最小化**：无障碍服务仅本地任务执行；支付等不可逆操作需用户确认（supervised 机制）

---

## 结果记录（供评委勾选）

| 项 | 结论 |
|----|------|
| Demo 可运行性 | ☐ 通过 ☐ 未通过 |
| 评测可复现性 | ☐ 通过 ☐ 未通过 |
| 工程实现深度 | ☐ 通过 ☐ 未通过 |
| 合规性 | ☐ 通过 ☐ 未通过 |

---

## 相关文档

- [DEPLOY.md](DEPLOY.md)：详细部署引导
- [DEPENDENCIES.md](DEPENDENCIES.md)：依赖清单与合规
- [OPEN-SOURCE-BOUNDARY.md](OPEN-SOURCE-BOUNDARY.md)：开源边界
