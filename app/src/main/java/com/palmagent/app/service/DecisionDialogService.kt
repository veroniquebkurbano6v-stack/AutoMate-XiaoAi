package com.palmagent.app.service

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.agent.Plan
import com.palmagent.app.agent.PlanFormatter
import com.palmagent.app.agent.PlanStep
import com.palmagent.app.model.Question
import com.palmagent.app.model.QuestionOption
import com.palmagent.app.tool.impl.KbReadTool
import com.palmagent.app.ui.chat.ChatMessage
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 决策对话服务
 *
 * 管理用户与决策模型的多轮对话。决策模型判断用户描述的手机操作任务
 * 是否有足够信息制定执行计划：信息不足时追问，信息足够时返回规划方案。
 *
 * 决策模型配置读取 KVUtils（getPlannerApiKey 等，沿用"任务决策模型"设置项），
 * 使用 OpenAI 兼容 API 格式。
 *
 * 支持高德地图 function calling：模型根据用户问题自主决定是否调用
 * amap_nearby/amap_search/amap_weather/amap_directions 工具，服务层
 * 执行后把结果回传给模型生成最终回复。
 */
class DecisionDialogService {

    /** 对话结果：密封类，表示追问 / 就绪 / 出错三种状态 */
    sealed class DialogResult {
        /** 信息不足，需要追问用户。questions 非空时为结构化批量追问，message 为兜底纯文本 */
        data class NeedMoreInfo(val message: String, val questions: List<Question>? = null) : DialogResult()

        /** 信息足够，返回结构化任务计划 */
        data class Ready(
            val plan: Plan,
            val userSummary: String = ""
        ) : DialogResult()

        /** 调用出错 */
        data class Error(val message: String) : DialogResult()
    }

    companion object {
        private const val TAG = "DecisionDialogService"
        // P0 修复演进：1024 → 4096 → 16384。
        // deepseek-v4-flash 的多步骤结构化 Plan（JSON 对象，复杂任务可超 10 步）+ user_summary
        // 可能超过 4096 token 导致 JSON 截断（MalformedJsonException: Unterminated object at $.plan）。
        // 不限制 plan 长度/步骤数（复杂任务需要），改为显式给足输出空间：16384 覆盖 10-20 步长 plan。
        private const val MAX_TOKENS = 16384
        private const val TEMPERATURE = 0.3
        // 工具循环上限：覆盖 "调 list_apps → 逐个候选 App 调 kb_read → amap/web_search → 追问 → 输出 ready" 路径。
        // 决策侧 kb_read 按 app_filter 一次一 App，多候选 App 场景轮次增加，5 轮易触顶导致硬失败，放宽到 9 轮。
        private const val MAX_TOOL_ROUNDS = 9
        // 观察掩码：只保留最近 N 轮工具结果的原文（assistant.tool_calls 参数始终完整保留以满足
        // OpenAI tool_calls/tool 严格配对校验），更早轮次的 role=tool 结果内容被掩码为占位符。
        // 工具真实结果由「事实台账」承载（同参数去重、跨请求持久化），不随轮次线性堆积，
        // 从源头控制决策上下文膨胀。
        private const val MASK_KEEP_ROUNDS = 1
        // 台账聚合注入预算（字符/轮）：防"单条不超但并发多条"撑爆上下文，超预算最旧条目直接淘汰
        private const val MAX_LEDGER_BLOCK_CHARS = 3000
        // 台账历史 token 预算：台账注入系统提示词，取上下文窗口 ~10-20%（保守）
        private const val LEDGER_MAX_BUDGET_TOKENS = 8000
        // 保护最近 N 条：即使超预算也不淘汰（参考 Claude Code MicroCompact keep_recent）
        private const val LEDGER_PROTECTED_RECENT = 5
        // fetch_result 单次取回显示上限（字符）：防止撑爆本轮上下文，需要更多可多次调用
        private const val FETCH_OUTPUT_MAX_CHARS = 4000
        // 被观察掩码替换的占位文本
        private const val OBSERVATION_MASK_PLACEHOLDER = "[工具结果已掩码，见下方『事实台账』]"
        // 工作区最大字符数（约 800 token 上限的兜底）：防止模型超长写入导致工作区自身膨胀
        private const val WORKSPACE_MAX_CHARS = 2000

        // 操作任务格式违规纠错提示：模型在操作任务中输出裸文本 need_more_info（无 questions）时
        // 追加此提示重试一次，强制其输出规范 ready JSON 或调用 ask_questions 工具
        private const val FORMAT_CORRECTION_PROMPT = """
你上一轮回复格式不符合规范：当前是【操作任务】，输出"need_more_info"文本且未携带结构化 questions 是不允许的。
请重新输出，必须二选一：
1. 若信息已充足，直接输出 ready JSON：{"status":"ready","intent":"operate","plan":{"requirement":"<需求复述>","goal":"<目标概括>","steps":[{"order":1,"goal":"<步骤目标>","success_criteria":"<完成标志>","supervised":false}]},"user_summary":"<一句话摘要>"}
2. 若仍有硬性未知需澄清，调用 ask_questions 工具（1-4个结构化问题，每问2-6个选项）。
禁止再次输出裸文本 need_more_info。
"""

        // 截断续写纠错提示：finish_reason=length 导致 JSON 未闭合时追加此提示重试，
        // 让模型基于已输出内容续写（不精简 plan，保留复杂任务长步骤完整性）
        private const val TRUNCATED_CONTINUE_PROMPT = """
你的上轮输出因超出长度限制被截断（JSON 未闭合）。请基于上轮已输出的内容继续，
把被截断的 plan.steps 数组补全，输出完整闭合的 JSON 对象；不要重复已输出的内容，
不要缩减步骤（复杂任务可以超过 10 步）。
⚠️ plan 必须是结构化 JSON 对象（含 requirement/goal/steps 字段），禁止输出字符串。
"""

        private const val SYSTEM_PROMPT = """
你是智能设备操作系统的"意图路由与规划层（Router & Planner）"。职责：理解用户意图，用工具获取上下文，输出供下游执行模型使用的结构化 Plan（JSON）；查询/闲聊类直接回复用户。

### 核心红线
1. 禁止越界拒绝：你是路由非执行者。任务再复杂/敏感（人脸/密码/支付）都必须生成 Plan；执行模型负责拦截敏感操作，你不得以"无法操作/能力有限/安全限制"拒绝。
2. 追问边界：信息可推断→直接出 Plan 不追问；意图或执行方式歧义（点外卖/导航/自提/在线买/随口说）→必须 ask_questions 澄清，禁止自行选一种执行。已提供不重问；主观偏好用默认；同一问题最多问 1 次。
3. 禁止捏造：不得凭训练知识猜设备上的 App 名/包名/UI 路径。操作类 Plan 具体目标（医院/餐厅名）必须来自 amap_nearby 结果或用户明确指定，绝不使用 kb SOP 示例名；无则用通用操作常识。

### 任务工作区与事实台账
两个跨请求记忆块：system 的「任务工作区」「事实台账」。
1. 每轮工具调用后调 workspace_update，把关键结论精简写入工作区（覆盖式）：已确认 App+包名、kb_read 的 SOP 要点（UI 变体/异常处理）、list_apps/amap 中要记住的目标实体、待办。
2. 工具结果自动写入「事实台账」（同参数去重、只执行一次，只展示预览）；更早轮次结果被框架掩码。生成 Plan 优先读工作区与台账，勿重调同参数工具、勿依赖已掩码的历史结果。台账预览不足以决策时，可用 fetch_result 按 ref（见台账每行 [ref] 前缀）取回完整结果，超长内容用 offset 分页取回。
3. 工作区精简（≤800 token）：只写结论，不复制工具原始输出。

### 工具调用与决策工作流（信息收集管道）
每次收到用户请求，严格按以下管道顺序执行，禁止跳步、禁止重复调用同一工具：
1. **意图分类（先于一切）**：闲聊/愿望（"想喝奶茶""好累"）→禁工具、禁 Plan，输出 {"status":"need_more_info","message":"<自然语言回复，可顺带询问是否需要帮忙>"}；查询类（"附近有什么医院""天气"）→用 amap_*/web_search 获取答案后同样 need_more_info 回答，不执行。操作-明确（"导航到X""发微信给张三"）→进 2-6；操作-模糊（"买杯奶茶"未说外卖/自提/导航）→先 ask_questions 澄清执行方式，禁不澄清直接选一种执行。
2. **应用环境确认（list_apps，操作必调）**：先调 list_apps 确认已装相关 App，一次传入多个关键词（"点奶茶"→["美团","淘宝闪购"]）。⚠️指定 App 未装红线：用户明确指定了 App（"用淘宝闪购…""打开微信…"）但 list_apps 显示未装——禁止擅用同类型替代、禁止 Plan 声称"已确认替代"，必须调 ask_questions 告知"XX未安装"并给替代项（同类型/网页版/换方式/放弃）；仅泛化表述（"点个外卖"）才可自选。未装就在 Plan 如实描述，绝不瞎编包名。
3. **知识库校验（kb_read，仅操作）**：按 list_apps 已装候选 App 逐个查，一次一 App，query=意图+App名，app_filter=该 App，候选最多 3 个。⚠️三禁：禁跳过 list_apps 直查；禁不带 app_filter 全量查；禁对同 App 重复调。kb 只是"怎么做"的操作手册非意图证据，意图只能来自用户原话/历史/澄清。
4. **补充信息（按需）**：地理/路线/天气→amap_*（请求含"附近/周边/就近"且为查询或导航附近目标时用 amap_nearby）；实时信息（新闻/股价/价格/动态）→先 web_search 再答防幻觉。
5. **追问（操作必做一次）**：调用前自问"还有什么没问"，硬性未知打包一次（1-4问，每问2-6选项，UI 自动追加"其他"勿生成）。只决定"问什么"不问"问不问"，自查四项：①历史已提供→不重问 ②主观偏好可默认→并默认项 ③可 kb/list/amap 补全→先调工具再问 ④执行方式不唯一→必须问。有硬性未知问具体，无则也调一次 ask_questions 用"确认型问题"复述方案。禁止跳过直接 ready，除非用户本轮已说"随便/你定/直接执行"。
6. **生成 Plan**：用户已答/确认或按例外跳过后，立即输出 ready，停止调工具。

### Plan 生成规范
Plan 是传给执行模型的分步骤指引，输出结构化 JSON 对象（非字符串），字段短小无嵌套引号风险。
- requirement：一句话摘要需求（≤60字，含对象/地点/时间/数量；细节放工作区，禁超长复述）
- goal：一句话目标
- steps：步骤数组，每个步骤对象含：
  - order：步骤序号（从1开始递增）
  - goal：步骤目标（一句话，≤15字，只写动作）
  - success_criteria：完成标志（该步完成时应看到的界面状态/元素变体/异常处理，执行模型据此判定本步完成）
  - supervised：是否需用户监督执行（默认false；仅资金支付/转账/删除/权限变更等不可逆操作设为true；用户明确要求的常规操作如发消息不设）
  - tool_hint：可选，建议执行模型优先使用的快捷工具（仅当该步骤可用下方"执行模型快捷工具"一步完成时填写，否则省略），格式见下方说明
- 如有 kb_read 结果，把每步的"预期"字段对应写入 steps[].success_criteria；UI 元素变体名（如菜单可能叫"就医服务"或"服务平台"）、异常处理策略（弹窗处理、备选路径）一并提炼写入
- ⚠️ **终止边界（必须遵守）**：Plan 步骤范围严格以 kb_read 返回的 SOP 为准，禁止超出知识库覆盖续写后续步骤（如 SOP 只到"加入购物车"，不得续写"结算→订单→支付"）。在覆盖的最后一步后追加终止步：order: N+1, goal: "任务完成，终止行动", success_criteria: "执行模型输出FINISH，向用户报告已完成范围（后续结算/支付请手动确认），不再操作", supervised: false。kb 无匹配则用通用常识，同样追加该终止步

### 执行模型快捷工具（tool_hint）
执行模型内置可"一步完成多操作"的快捷工具，只有写进 Plan 的 tool_hint 才被可靠触发。命中以下场景必须在对应步骤标注：
- **auto_input**：一步完成"定位输入框→输入→自动点搜索/确认"。适用"输入关键词/地址后触发搜索确认"的步骤（如 App 内搜索商品/医院/联系人）。写法 "auto_input: <输入文本>；<按钮特征>"。
- **select_spec**：自动遍历规格表单（份量/辣度/尺寸/颜色/口味/数量等）逐项选取并确认。适用外卖/购物/预约多规格。写法 "select_spec"（规格由执行模型读屏，不列举）。
⚠️tool_hint 只标"动作类型+关键参数"，界面元素由执行模型识别；纯点击/滚动/导航不适用则不填。

### 输出紧凑度（防截断，步骤数不限）
步骤数不设上限（可超 10 步）但每步紧凑：goal≤15字只写动作；success_criteria 只留执行模型判断所需最小信息（界面状态/元素变体/异常处理），禁复述目标/客套；禁输出 JSON 外任何文字。

Plan 示例（预约挂号）：
{"status":"ready","intent":"operate","plan":{"requirement":"用户需要为本人预约东莞市人民医院呼吸内科的挂号","goal":"通过微信服务号预约东莞市人民医院呼吸内科","steps":[{"order":1,"goal":"打开微信","success_criteria":"进入微信主页，底部有聊天/通讯录/发现/我四个Tab；如果微信未安装，提示用户","supervised":false},{"order":2,"goal":"搜索并进入医院服务号","success_criteria":"进入服务号主页，底部有菜单栏，常见叫法有就医服务/服务平台/智慧医院/诊疗服务；搜索无结果则提示用户确认医院名称","supervised":false,"tool_hint":"auto_input: 医院服务号；搜索按钮"}]},"user_summary":"通过微信服务号预约东莞市人民医院呼吸内科"}

### user_summary 规范
- 面向用户的一句话摘要，不超过 30 字，含目标 App + 核心操作（如"通过微信预约挂号"、"在淘宝搜索商品"）
- 不要包含步骤编号、技术细节、完成标志等内部信息

### 输出格式（严格 JSON / 工具调用）
- 操作-明确：必须先调一次 ask_questions（有未知问具体，无未知用确认型），收到回答/确认后输出 ready JSON：{"status":"ready","intent":"operate","plan":{"requirement":"<需求>","goal":"<目标>","steps":[{"order":1,"goal":"<步骤目标>","success_criteria":"<完成标志>","supervised":false}]},"user_summary":"<摘要>"}
- 闲聊/愿望/查询类（不执行）：{"status":"need_more_info","message":"<真正回复用户的自然语言文字>"}
- 操作-模糊 或 执行方式歧义：必须调用 ask_questions（禁止输出 questions 文本字段）
⚠️ **操作红线**：操作禁输裸文本 need_more_info（无 questions）。操作出口只有 ready JSON 或 ask_questions；转发 ready 前须至少调一次 ask_questions，除非用户本轮已说"随便/你定/直接执行"。

最后一行（必须遵守）：输出必须是严格的 JSON 对象或一次工具调用；禁止用 markdown 代码块包裹 JSON，禁止输出 JSON 之外的任何解释文字。
"""
    }

    /** 台账单行：key（去重）+ ref（取回用）+ 固化预览（唯一预览来源，全文落盘） */
    internal data class LedgerRow(
        val key: String,
        val ref: String,
        val preview: String
    )

    /**
     * 会话级决策状态：工作记忆（workspace）+ 事实台账（ledger）+ 台账 token 预算。
     * ledger 统一只存预览（key → LedgerRow），全文在 ToolResultCache 磁盘缓存。
     */
    internal data class SessionDecisionState(
        var workspace: String = "",
        val ledger: LinkedHashMap<String, LedgerRow> = LinkedHashMap(),
        var ledgerTokens: Int = 0
    )

    // 按 sessionId 隔离的会话级决策状态。DecisionDialogService 是长生命周期实例（HomeActivity 字段），
    // 会话切换不会重建它，故必须用 Map 按 sessionId 隔离，避免多会话互相污染。ConcurrentHashMap
    // 保证并发安全（悬浮窗 + 主界面可能并发触发 chat）。
    private val sessionStates = ConcurrentHashMap<String, SessionDecisionState>()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mcpService = WebMCPService()

    /**
     * 与决策模型进行一轮对话。
     *
     * @param userMessage 当前用户消息
     * @param history 之前的对话历史（按时间顺序，isUser=true 为用户消息，
     *                isUser=false 为决策模型回复）
     * @return 对话结果（NeedMoreInfo / Ready / Error）
     */
    suspend fun chat(
        userMessage: String,
        history: List<ChatMessage>,
        sessionId: String
    ): DialogResult = withContext(Dispatchers.IO) {
        LiveLogBuffer.append("🤖 决策模型收到请求: ${userMessage.take(60)}")
        val apiKey = KVUtils.getPlannerApiKey()
        if (apiKey.isEmpty()) {
            LiveLogBuffer.append("❌ 决策模型 API Key 未配置")
            return@withContext DialogResult.Error(
                "决策模型 API Key 未配置，请在设置 → 任务决策模型中配置"
            )
        }

        val apiUrl = normalizeApiUrl(KVUtils.getPlannerApiUrl())
        if (apiUrl.isEmpty()) {
            LiveLogBuffer.append("❌ 决策模型 API 地址未配置")
            return@withContext DialogResult.Error("决策模型 API 地址未配置")
        }

        val model = KVUtils.getPlannerModel()
        Log.d(TAG, "决策请求: model=$model, url=$apiUrl, user=${userMessage.take(80)}")

        // 构建 messages: system + history + user(当前消息)
        val messages = mutableListOf<Map<String, Any>>(
            mapOf("role" to "system", "content" to SYSTEM_PROMPT)
        )
        for (msg in history) {
            val role = if (msg.isUser) "user" else "assistant"
            messages.add(mapOf("role" to role, "content" to msg.content))
        }
        messages.add(mapOf("role" to "user", "content" to userMessage))

        // 插入任务工作区占位（index=1，紧跟主 system）：进入工具循环后由持久化工作记忆刷新内容。
        // content 必须非空（部分 OpenAI 兼容 API 拒绝空 system）
        messages.add(
            1,
            mapOf(
                "role" to "system",
                "content" to "【任务工作区（scratchpad）】\n（暂无内容，等待工具调用后写入）"
            )
        )

        // 统一走 function calling：模型自主决定是否调用高德工具
        // 透传 userMessage 作为降级兜底的原始用户请求（避免被纠错提示覆盖）
        return@withContext callDecisionWithTools(apiUrl, apiKey, model, messages, userMessage, sessionId)
    }

    /**
     * 对话模型调用：启用 function calling 循环，模型可主动调 list_apps / kb_read / amap_*
     * 工具，工具结果以 role=tool 回传模型继续推理。最长 MAX_TOOL_ROUNDS 轮。
     */
    private suspend fun callDecisionWithTools(
        apiUrl: String,
        apiKey: String,
        model: String,
        messages: MutableList<Map<String, Any>>,
        originalUserMessage: String,
        sessionId: String
    ): DialogResult {
        var round = 0
        // 是否已调用过操作类工具（kb_read/list_apps）：用于判定当前为操作任务（查询/闲聊不调这些工具）
        var usedOperationalTool = false
        // 是否已对"操作任务返回无问题 need_more_info"做过一次纠错重试
        var formatRetried = false
        // 是否已对"输出被截断（finish_reason=length）"做过一次续写重试
        var truncateRetried = false
        // 续写轮提升输出预算（MAX_TOKENS * 1.5），避免同参数重发导致同一位置二次截断
        var nextMaxTokens = MAX_TOKENS
        // 会话级决策状态：工作记忆 + 事实台账，随 sessionId 跨 chat 请求持久化（按会话隔离）
        // 工具去重只由内存台账按 sessionId 隔离承担；磁盘仅存全文供 fetch_result 取回，
        // 不作为去重命中依据（避免跨会话复用旧结果）。computeIfAbsent 保证原子创建。
        val state = sessionStates.computeIfAbsent(sessionId) { SessionDecisionState() }
        while (round < MAX_TOOL_ROUNDS) {
            round++

            // 观察掩码：把更早轮次的 role=tool 结果内容掩码为占位符（保留最近 MASK_KEEP_ROUNDS 轮原文），
            // assistant.tool_calls 参数完整保留，工具真实结果由事实台账承载，不随轮次线性堆积。
            maskStaleObservations(messages, keepLastRounds = MASK_KEEP_ROUNDS)

            // 刷新工作记忆块（index=1 占位 system 消息）：模型只依赖工作记忆与台账生成 Plan；
            // take 截断兜底，防止模型超长写入导致工作区自身膨胀
            messages[1] = mapOf(
                "role" to "system",
                "content" to "【任务工作区（scratchpad）】\n${state.workspace.take(WORKSPACE_MAX_CHARS)}\n（工具结果写入事实台账，请优先从工作区与台账读取信息生成 Plan）"
            )

            // 注入事实台账（去重后的工具结果）到消息流末尾，供模型读取而非重复调用
            injectLedger(messages, state)

            Log.d(TAG, "上下文视图: workspace=${state.workspace.length}字符, 台账=${state.ledger.size}条, 掩码保留最近${MASK_KEEP_ROUNDS}轮, messages=${messages.size}条")

            // 工具选择策略：始终 auto，让 LLM 按触发式 prompt 自主决定
            // kb_read 工具仅在 KB 启用时由 buildToolsJson 注入到 tools 列表
            val toolChoiceJson = "\"auto\""

            LiveLogBuffer.append("🔁 决策模型推理（第${round}轮/${MAX_TOOL_ROUNDS}）")
            Log.d(TAG, "决策推理 第${round}轮/${MAX_TOOL_ROUNDS}")
            val (content, toolCalls, truncated) = callApiWithTools(apiUrl, apiKey, model, messages, toolChoiceJson, nextMaxTokens)
                ?: run {
                    LiveLogBuffer.append("❌ 决策模型调用失败")
                    return DialogResult.Error("决策模型调用失败，请查看日志")
                }

            if (toolCalls.isEmpty()) {
                // 模型直接给出最终回复（ready / need_more_info / 普通回答）
                if (content.isBlank()) return DialogResult.Error("决策模型返回空内容")
                val result = parseDialogResult(content)
                // 截断续写：三路截断信号或 JSON 解析失败（Malformed/Unterminated）任一命中 →
                // 追加续写提示重试一次，让模型基于已输出内容补全（不精简 plan，保留长步骤完整性）
                val truncationError = result is DialogResult.Error &&
                    (result.message.contains("Malformed") || result.message.contains("Unterminated"))
                if ((truncated || truncationError) && !truncateRetried) {
                    LiveLogBuffer.append("⚠️ [决策] 输出疑似截断，追加续写重试")
                    messages.add(mapOf("role" to "assistant", "content" to content))
                    messages.add(mapOf("role" to "user", "content" to TRUNCATED_CONTINUE_PROMPT))
                    truncateRetried = true
                    nextMaxTokens = (MAX_TOKENS * 1.5).toInt()
                    continue
                }
                // 格式违规拦截：操作任务返回"无结构化问题"的裸文本 need_more_info
                // （模型违反输出契约，把复述需求的长文本当追问）不允许直接上屏——
                // 追加纠错提示重试一次，让模型输出规范 ready JSON 或调用 ask_questions
                if (usedOperationalTool && !formatRetried &&
                    result is DialogResult.NeedMoreInfo && result.questions == null
                ) {
                    LiveLogBuffer.append("⚠️ [决策] 操作任务返回无问题追问（格式违规），追加纠错重试")
                    messages.add(mapOf("role" to "assistant", "content" to content))
                    messages.add(mapOf("role" to "user", "content" to FORMAT_CORRECTION_PROMPT))
                    formatRetried = true
                    continue
                }
                // 纠错重试后仍返回无问题 need_more_info：降级为 ready，直接用用户原始请求执行，
                // 绝不把模型长文本发给用户（userSummary 留空，UI 只显示简短确认语）
                // ⚠️ 必须使用 originalUserMessage 而非 messages 反查：此时 messages 最后一个 role=user
                // 是 FORMAT_CORRECTION_PROMPT，反查会拿到纠错提示全文而非用户真实请求
                if (usedOperationalTool && result is DialogResult.NeedMoreInfo && result.questions == null) {
                    LiveLogBuffer.append("⚠️ [决策] 纠错重试后仍返回无问题追问，降级为按用户请求直接执行")
                    val userReq = originalUserMessage.takeIf { it.isNotBlank() } ?: "执行用户请求"
                    return DialogResult.Ready(
                        plan = Plan(
                            requirement = userReq,
                            goal = userReq.take(40),
                            steps = listOf(PlanStep(order = 1, goal = "执行用户请求", successCriteria = "任务完成", supervised = false))
                        ),
                        userSummary = ""
                    )
                }
                LiveLogBuffer.append("🤖 决策模型回复: ${resultSummary(result)}")
                Log.d(TAG, "决策回复: ${resultSummary(result)}")
                return result
            }

            // 记录本轮工具调用链（决策模型请求调用的工具及参数）
            toolCalls.forEach { tc ->
                val name = tc["name"] as? String ?: "?"
                val args = tc["arguments"] as? Map<String, Any> ?: emptyMap()
                LiveLogBuffer.append("🔧 [决策] 调用工具: $name ${args.toList().take(3).toMap()}")
                Log.d(TAG, "决策工具调用: $name ${args.toList().take(3).toMap()}")
            }

            // 检测 ask_questions 工具调用（追问信号）：拦截后追加自检对话，确认完整后再展示
            val askQuestionsCall = toolCalls.firstOrNull { it["name"] == "ask_questions" }
            if (askQuestionsCall != null) {
                val args = askQuestionsCall["arguments"] as? Map<String, Any> ?: emptyMap()
                val questions = parseQuestionsFromToolArgs(args)
                if (questions.isEmpty()) {
                    LiveLogBuffer.append("❌ [决策] ask_questions 工具参数解析失败")
                    return DialogResult.Error("ask_questions 工具参数解析失败，模型未按规范输出 questions 数组")
                }
                LiveLogBuffer.append("❓ [决策] 模型追问 ${questions.size} 个问题，进行自检")
                Log.d(TAG, "决策模型调用 ask_questions 工具：${questions.size} 个问题，即将自检是否有遗漏")

                // 把 assistant 的 tool_calls 消息加入历史
                messages.add(
                    mapOf(
                        "role" to "assistant",
                        "content" to content,
                        "tool_calls" to listOf(
                            mapOf(
                                "id" to (askQuestionsCall["id"] ?: "call_ask"),
                                "type" to "function",
                                "function" to mapOf(
                                    "name" to "ask_questions",
                                    "arguments" to gson.toJson(args)
                                )
                            )
                        )
                    )
                )
                // 追加 tool 结果（空结果，表示"已拦截，待自检"）
                messages.add(
                    mapOf(
                        "role" to "tool",
                        "tool_call_id" to (askQuestionsCall["id"] ?: "call_ask"),
                        "content" to "问题已拦截，等待自检确认。"
                    )
                )
                // 追加自检追问
                messages.add(
                    mapOf(
                        "role" to "user",
                        "content" to "在向用户展示这些问题之前，请先自检：还有什么问题需要确认吗？" +
                            "如果有遗漏，请调用 ask_questions 工具把所有问题（含之前的）汇总到一次调用中；" +
                            "如果确认没有遗漏，请输出 {\"status\":\"ready\"}。"
                    )
                )

                // 再调一次模型，让模型自检
                val (_, selfCheckToolCalls, _) = callApiWithTools(
                    apiUrl, apiKey, model, messages, "\"auto\""
                ) ?: return DialogResult.NeedMoreInfo(
                    message = "请回答以下问题",
                    questions = questions  // 自检失败，降级使用原始问题
                )

                // 自检结果：模型可能再次调用 ask_questions（补充/合并问题）
                val selfCheckAskCall = selfCheckToolCalls.firstOrNull { it["name"] == "ask_questions" }
                if (selfCheckAskCall != null) {
                    val selfCheckArgs = selfCheckAskCall["arguments"] as? Map<String, Any> ?: emptyMap()
                    val mergedQuestions = parseQuestionsFromToolArgs(selfCheckArgs)
                    if (mergedQuestions.isNotEmpty()) {
                        Log.d(TAG, "自检后模型补充问题，合并后共 ${mergedQuestions.size} 个问题")
                        return DialogResult.NeedMoreInfo(
                            message = "请回答以下问题",
                            questions = mergedQuestions
                        )
                    }
                }

                // 模型输出 ready（确认问题完整）或其它情况：使用原始问题
                Log.d(TAG, "自检完成，模型确认问题完整，共 ${questions.size} 个问题")
                return DialogResult.NeedMoreInfo(
                    message = "请回答以下问题",
                    questions = questions
                )
            }

            // 工具循环：把 assistant 的 tool_calls 消息加入历史
            messages.add(
                mapOf(
                    "role" to "assistant",
                    "content" to content,
                    "tool_calls" to toolCalls.map { tc ->
                        mapOf(
                            "id" to tc["id"]!!,
                            "type" to "function",
                            "function" to mapOf(
                                "name" to tc["name"]!!,
                                "arguments" to gson.toJson(tc["arguments"])
                            )
                        )
                    }
                )
            )

            // 逐个执行工具，结果以 role=tool 追加到 messages
            for (tc in toolCalls) {
                val id = tc["id"] as? String ?: continue
                val name = tc["name"] as? String ?: continue
                // 操作类工具调用过 → 判定为操作任务（查询类/闲聊不调 kb_read/list_apps）
                if (name == "kb_read" || name == "list_apps") usedOperationalTool = true
                val args = (tc["arguments"] as? Map<String, Any>) ?: emptyMap()

                // workspace_update：写入工作记忆块（会话级持久化）。工具结果只写简短确认，不占上下文。
                if (name == "workspace_update") {
                    state.workspace = args["content"]?.toString() ?: ""
                    LiveLogBuffer.append("📝 [决策] 工作区更新: ${state.workspace.take(60)}...")
                    messages.add(
                        mapOf(
                            "role" to "tool",
                            "tool_call_id" to id,
                            "content" to "工作区已更新（${state.workspace.length} 字符）"
                        )
                    )
                    continue
                }

                // fetch_result：按 ref 取回全文，仅本轮注入（常规命中不落盘、不登记台账，符合"仅供本轮参考"契约）。
                // 例外：磁盘全文被 cleanupGeneric（60 文件上限）挤出/写盘失败但该 ref 仍在会话台账中时，
                // 按台账行 key 重新执行原工具自愈——此时落盘并重登记台账行是有意行为（恢复 ref 可取回），
                // token 记账/淘汰随行在净结算后执行，避免取回链路永久失效。
                if (name == "fetch_result") {
                    val ref = args["ref"]?.toString()?.trim().orEmpty()
                    val offset = (args["offset"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
                    var fetched: String? = null
                    if (ref.isNotEmpty()) {
                        val disk = ToolResultCache.get(ref)
                        if (disk == null) {
                            val row = state.ledger.values.firstOrNull { it.ref == ref }
                            val parsed = row?.let { parseLedgerKey(it.key) }
                            if (parsed != null) {
                                val (t, a) = parsed
                                val content = executeAnyTool(t, a)
                                val entry2 = ToolResultCache.put(t, a, content, sessionId)
                                if (entry2 != null) {
                                    val newRow = LedgerRow(entry2.key, entry2.ref, entry2.preview)
                                    // 删旧行（按原 row.key，避免 json 往返导致 key 漂移而旧行残留），插入新行并净结算
                                    val oldRow = state.ledger.remove(row.key)
                                    state.ledger[entry2.key] = newRow
                                    state.ledgerTokens += estimateTokens(newRow.key + newRow.preview) -
                                        (oldRow?.let { estimateTokens(it.key + it.preview) } ?: 0)
                                    evictLedgerIfNeeded(state)
                                    // 尊重请求的 offset 分页，渲染与 executeFetchResultTool 一致的段内容
                                    fetched = buildFxSegment(ref, entry2.tool, entry2.content, offset)
                                    LiveLogBuffer.append("♻️ [取回自愈] ref=$ref 磁盘全文缺失，重执行 $t 恢复（offset=$offset）")
                                } else {
                                    LiveLogBuffer.append("⚠️ [取回自愈] 缓存写失败，无法恢复 ref=$ref")
                                }
                            }
                        }
                    }
                    val finalContent = fetched ?: executeFetchResultTool(args)
                    LiveLogBuffer.append("📦 [决策] 取回结果: $name → ${finalContent.take(80)}")
                    messages.add(mapOf("role" to "tool", "tool_call_id" to id, "content" to finalContent))
                    continue
                }

                // 去重只看会话内存台账（按 sessionId 隔离）：磁盘仅存全文供 fetch_result 取回，
                // 不作为"去重命中"依据，避免跨会话复用旧会话写下的 fx_。内存命中取磁盘全文；缺失则删行重执行。
                val key = ToolResultCache.buildKey(name, args)
                var entry: ToolResultCache.CachedEntry? = null
                var rawResult: String? = null
                val existingRow = state.ledger[key]
                if (existingRow != null) {
                    val disk = ToolResultCache.getByKey(key, sessionId)
                    if (disk != null) {
                        LiveLogBuffer.append("🔁 [台账命中] $name 已存在结果，跳过执行")
                        entry = disk
                    } else {
                        // 磁盘全文缺失（被挤出/写盘失败）：删行后重新执行拿全文，避免 fetch 失败死循环；
                        // 扣减口径与记账/淘汰一致（key+preview），避免缺失重执行路径虚增 token
                        state.ledger.remove(key)?.let { state.ledgerTokens -= estimateTokens(it.key + it.preview) }
                        LiveLogBuffer.append("♻️ [台账失效] $name 磁盘全文缺失，重新执行获取完整内容")
                        rawResult = executeAnyTool(name, args)
                    }
                } else {
                    rawResult = executeAnyTool(name, args)
                }
                if (entry == null && rawResult != null) {
                    // 写盘成功才登记台账；写失败（磁盘满/IO 错误）返回 null，不登记假缓存避免后续错误命中
                    entry = ToolResultCache.put(name, args, rawResult, sessionId)
                }
                if (entry == null) {
                    // 执行了但写盘失败：结果仍本轮展示，跳过台账登记（下次会重新执行）
                    LiveLogBuffer.append("⚠️ [决策] $name 缓存写失败，结果仅本轮展示，未登记台账")
                    val fallbackMsg = rawResult?.take(FETCH_OUTPUT_MAX_CHARS) ?: "错误：$name 执行结果为空"
                    messages.add(mapOf("role" to "tool", "tool_call_id" to id, "content" to fallbackMsg))
                    continue
                }
                val row = LedgerRow(key = entry.key, ref = entry.ref, preview = entry.preview)
                // 覆盖写前先扣减旧 entry 的 token，避免同 key 在多轮内重复累加预算。
                // token 统计计入 key+preview（行注入成本含 [ref]+key 头部，与 buildLedgerContent 输出一致）
                val old = state.ledger.put(key, row)
                state.ledgerTokens += estimateTokens(row.key + row.preview) -
                    (old?.let { estimateTokens(it.key + it.preview) } ?: 0)
                evictLedgerIfNeeded(state)
                LiveLogBuffer.append("📦 [决策] 工具结果: $name → ${row.preview.take(80)}")
                // 工具消息注入完整结果（entry.content 是全文，limit 到展示上限），
                // 避免长结果任务里重调同工具只拿到截断预览、又提示 fetch 却无从下手。
                val toolResultMsg = entry.content.take(FETCH_OUTPUT_MAX_CHARS)
                messages.add(mapOf("role" to "tool", "tool_call_id" to id, "content" to toolResultMsg))
            }
            // 继续循环：模型基于工具结果继续推理
        }
        LiveLogBuffer.append("❌ 决策模型工具循环达到上限（$MAX_TOOL_ROUNDS 轮）")
        return DialogResult.Error("决策模型工具循环达到上限（$MAX_TOOL_ROUNDS 轮）仍未给出最终回复")
    }

    /**
     * 观察掩码：把更早轮次的 role=tool 结果内容替换为占位符，只保留最近 keepLastRounds 轮原文。
     *
     * 每"轮"指一条 assistant.tool_calls 消息及其后连续的所有 role=tool 结果消息。
     * 与旧"删除历史轮"不同：删除会破坏 OpenAI tool_calls/tool 配对而报 400，掩码则保留
     * assistant.tool_calls 完整参数（模型仍知道调用过什么、传过什么参），仅把结果正文替换为
     * 占位，工具真实结果由事实台账承载。截断/纠错/自检追加的 user 提示等非工具消息不受影响。
     */
    private fun maskStaleObservations(messages: MutableList<Map<String, Any>>, keepLastRounds: Int) {
        if (keepLastRounds <= 0) return
        // 定位每轮 [assistantIdx, toolEndIdx) 区间
        val rounds = mutableListOf<IntRange>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            if (m["role"] == "assistant" && m["tool_calls"] != null) {
                var j = i + 1
                while (j < messages.size && messages[j]["role"] == "tool") j++
                rounds.add(i until j)
                i = j
            } else {
                i++
            }
        }
        // 掩码最早超出保留量的轮（保留最近 keepLastRounds 轮原文）
        val maskCount = maxOf(0, rounds.size - keepLastRounds)
        for (idx in 0 until maskCount) {
            val range = rounds[idx]
            for (k in range.first + 1 until range.last) {
                val msg = messages[k]
                if (msg["role"] == "tool") {
                    messages[k] = msg.toMutableMap().apply { this["content"] = OBSERVATION_MASK_PLACEHOLDER }
                }
            }
        }
        if (maskCount > 0) {
            LiveLogBuffer.append("🧹 [掩码] 掩码 ${maskCount} 轮旧工具结果（保留最近 $keepLastRounds 轮原文）")
        }
    }

    /** 把事实台账注入消息流末尾（每轮刷新，保持末尾只有一份台账） */
    private fun injectLedger(messages: MutableList<Map<String, Any>>, state: SessionDecisionState) {
        val ledgerIndex = messages.indexOfLast {
            it["role"] == "system" && (it["content"] as? String)?.startsWith("【事实台账】") == true
        }
        if (ledgerIndex >= 0) messages.removeAt(ledgerIndex)
        messages.add(mapOf("role" to "system", "content" to buildLedgerContent(state)))
    }

    /** 事实台账内容：去重后的工具结果预览（只读，不修改 state.ledger）。
     *  每行恒输出 [ref]+key（保证 fetch_result 对所有行可达）；预览正文只在字符预算内展示，
     *  超预算行的正文省略但保留 ref。真正的淘汰只由 evictLedgerIfNeeded（token 预算 + 保护最近 N 条）负责。 */
    internal fun buildLedgerContent(state: SessionDecisionState): String {
        if (state.ledger.isEmpty()) return "【事实台账】\n（暂无）"
        val sb = StringBuilder("【事实台账】（已去重的工具结果预览，全文可用 fetch_result 取回，勿重调）\n")
        var used = 0
        for ((key, row) in state.ledger) {
            val head = "- [${row.ref}] ${row.key}"
            if (used + head.length + row.preview.length > MAX_LEDGER_BLOCK_CHARS) {
                // 超预算行：保留 ref 行（fetch 可达），正文省略省 token
                sb.append(head).append("（内容超预算，可用 fetch_result 取回全文）\n")
                used += head.length
            } else {
                sb.append(head).append(" = ").append(row.preview).append("\n")
                used += head.length + 3 + row.preview.length
            }
        }
        return sb.toString()
    }

    /** 字符 → token 估算（中文 ~1.5 字/token），供台账预算统计 */
    internal fun estimateTokens(s: String): Int = (s.length * 2 + 1) / 3

    /** MicroCompact 淘汰：FIFO + 保护最近 N 条 + token 预算，只作用于 per-session 台账 */
    internal fun evictLedgerIfNeeded(state: SessionDecisionState) {
        if (state.ledgerTokens <= LEDGER_MAX_BUDGET_TOKENS) return
        // 最旧在前，保护最近 N 条（按插入顺序 keys 的 List 快照），
        // 逐个淘汰直到回到预算内；token 口径与注入成本一致（key+preview）
        val evictable = state.ledger.keys.toList().dropLast(LEDGER_PROTECTED_RECENT)
        for (key in evictable) {
            if (state.ledgerTokens <= LEDGER_MAX_BUDGET_TOKENS) break
            state.ledger.remove(key)?.let {
                state.ledgerTokens -= estimateTokens(it.key + it.preview)
                LiveLogBuffer.append("🗑️ [台账淘汰] $key（token 预算内保护最近 ${LEDGER_PROTECTED_RECENT} 条）")
            }
        }
    }

    /** 决策模型回复摘要（供日志界面展示） */
    private fun resultSummary(result: DialogResult): String {
        return when (result) {
            is DialogResult.Ready -> "已生成计划（${result.plan.steps.size}步）"
            is DialogResult.NeedMoreInfo -> "需要追问: ${result.message.take(40)}（${result.questions?.size ?: 0}个问题）"
            is DialogResult.Error -> "出错: ${result.message.take(40)}"
        }
    }

    /**
     * 按工具名分派到具体执行器（list_apps / kb_read / amap_* / web_search）。
     * 注：fetch_result 在工具循环顶层单独拦截处理（不落盘不登记台账），不进入本分派。
     */
    private suspend fun executeAnyTool(name: String, args: Map<String, Any>): String {
        return when {
            name == "list_apps" -> executeListAppsTool(args)
            name == "kb_read" -> executeKbTool(name, args)
            name.startsWith("amap_") -> executeAmapTool(name, args)
            name == "web_search" -> executeWebSearchTool(args)
            else -> "未知工具：$name（仅支持 list_apps / kb_read / amap_* / web_search）"
        }
    }

    /**
     * 从台账行 key（"tool::<args JSON>"）解析出 (tool, args)，供 fetch_result 磁盘全文缺失时自愈重执行原工具。
     */
    internal fun parseLedgerKey(key: String): Pair<String, Map<String, Any>>? {
        val sep = key.indexOf("::")
        if (sep <= 0 || sep == key.length - 2) return null
        val tool = key.substring(0, sep)
        val argsJson = key.substring(sep + 2)
        val args: Map<String, Any>? = try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(argsJson, Map::class.java) as? Map<String, Any>
        } catch (e: Exception) {
            null
        }
        return if (tool.isNotBlank() && args != null) tool to args else null
    }

    /**
     * 执行 fetch_result 工具调用：按 ref 取回磁盘缓存的完整结果。
     * web_search 条目结构化格式化；其余工具返回全文（裁剪到 FETCH_OUTPUT_MAX_CHARS）。
     */
    private suspend fun executeFetchResultTool(args: Map<String, Any>): String {
        val ref = args["ref"]?.toString()?.trim() ?: ""
        val offset = (args["offset"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
        if (ref.isEmpty()) return "错误：ref 参数不能为空"
        val cached = ToolResultCache.get(ref)
        if (cached == null) {
            return "取回失败：ref=$ref 不存在或已清理，请重新调用原工具"
        }
        return if (cached.ref.startsWith("ws-")) {
            // 结构化 web_search 条目（putSearch 写入的 ws-<round>-<n>）
            buildString {
                appendLine("【取回搜索结果 ${cached.ref}】${cached.title}")
                if (cached.url.isNotBlank()) appendLine("URL: ${cached.url}")
                if (cached.snippet.isNotBlank()) appendLine("片段: ${cached.snippet}")
                if (!cached.summary.isNullOrBlank()) {
                    val cut = if (cached.summary.length > 800) "${cached.summary.take(800)}…" else cached.summary
                    appendLine("原文摘要: $cut")
                }
                appendLine("（本内容仅供本轮参考，不写入工作记忆；需要保留的要点请自行提炼）")
            }
        } else {
            // 通用 fx- 条目：按 offset 分页返回，重复调用可取完 4000 字符之后的内容
            buildFxSegment(cached.ref, cached.tool, cached.content, offset)
        }
    }

    /** 通用 fx- 条目分页渲染：带段头（第 offset..end 段/全长）与"还有 N 字符"续取提示，供取回与自愈路径共用。
     *  offset 超长会 clamp 到内容末尾并输出纠错提示（内容可能已被重执行更新/变短），避免空段矛盾头。 */
    internal fun buildFxSegment(ref: String, tool: String, content: String, offset: Int): String {
        val full = content
        val start = offset.coerceIn(0, full.length)
        val segment = full.drop(start).take(FETCH_OUTPUT_MAX_CHARS)
        val segEnd = start + segment.length
        return buildString {
            appendLine("【取回工具结果 $ref】（$tool）｜第 $start..$segEnd 段（全长 ${full.length}）")
            if (offset > full.length) {
                appendLine("⚠️ offset=$offset 超出当前内容全长（${full.length}）：内容可能已被更新/变短，请重新调用原工具获取最新结果。")
            }
            append(segment)
            if (segEnd < full.length) {
                appendLine("\n…（还有 ${full.length - segEnd} 字符未返回，可继续 fetch_result ref=$ref offset=$segEnd）")
            }
            appendLine("（本内容仅供本轮参考，不写入工作记忆；需要保留的要点请自行提炼）")
        }
    }

    /** 执行 web_search 工具调用，复用 WebSearchService 后端 */
    private suspend fun executeWebSearchTool(args: Map<String, Any>): String {
        return try {
            val query = args["query"]?.toString() ?: ""
            if (query.isBlank()) return "错误：query 参数不能为空"
            val count = (args["count"] as? Number)?.toInt() ?: 5
            val result = WebSearchService.search(query, count)
            if (result.success) result.content else "搜索失败：${result.error}"
        } catch (e: Exception) {
            Log.e(TAG, "执行 web_search 工具异常", e)
            "web_search 工具执行异常：${e.message}"
        }
    }

    /** 执行 list_apps 工具调用 */
    private suspend fun executeListAppsTool(args: Map<String, Any>): String {
        return try {
            val result = com.palmagent.app.tool.ToolRegistry.executeTool("list_apps", args)
            if (result.isSuccess) result.data ?: "" else "list_apps 执行失败：${result.error}"
        } catch (e: Exception) {
            Log.e(TAG, "执行 list_apps 工具异常", e)
            "list_apps 工具执行异常：${e.message}"
        }
    }

    /**
     * 调用 API 并返回 (content, toolCalls)。
     * - 请求体含 tools 字段（OpenAI function calling 格式）
     * - toolChoiceJson 控制工具选择策略：auto 让模型按触发式 prompt 自主决定
     * - 解析响应 message.content 和 message.tool_calls
     */
    private fun callApiWithTools(
        apiUrl: String,
        apiKey: String,
        model: String,
        messages: List<Map<String, Any>>,
        toolChoiceJson: String = "\"auto\"",
        maxTokens: Int = MAX_TOKENS
    ): Triple<String, List<Map<String, Any>>, Boolean>? {
        return try {
            // 先尝试启用 JSON 模式（response_format: json_object，由 API 层保证输出合法 JSON，
            // 避免模型输出裸文本/代码块导致解析失败）；若 API 不支持则回退为普通请求重试
            var requestBody = buildDecisionRequestBody(model, messages, toolChoiceJson, useJsonFormat = true, maxTokens = maxTokens)
            var (responseCode, body) = executeDecisionRequest(apiUrl, apiKey, requestBody)
            if (responseCode !in 200..299) {
                val firstError = parseErrorMessage(body, responseCode)
                Log.w(TAG, "决策对话 JSON 模式请求失败: HTTP $responseCode, $firstError，回退为普通请求重试")
                requestBody = buildDecisionRequestBody(model, messages, toolChoiceJson, useJsonFormat = false, maxTokens = maxTokens)
                val retry = executeDecisionRequest(apiUrl, apiKey, requestBody)
                responseCode = retry.first
                body = retry.second
            }

            if (responseCode !in 200..299) {
                val errorMsg = parseErrorMessage(body, responseCode)
                Log.e(TAG, "决策对话 API 错误: HTTP $responseCode, $errorMsg")
                return null
            }

            val responseJson = gson.fromJson(body, JsonObject::class.java)
            val choices = responseJson.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) return null

            val messageObj = choices[0].asJsonObject.getAsJsonObject("message")
            val content = messageObj.get("content")?.asString ?: ""
            // 截断检测（三路信号，不依赖单一 finish_reason）：
            // ① finish_reason="length"（API 报告的截断）
            // ② usage.completion_tokens 达到 max_tokens（部分网关不报 length，但 usage 仍会顶格）
            // ③ content 疑似截断（以 { 开头但 JSON 括号不平衡/字符串未闭合——JSON 模式下的早断兜底）
            val finishReason = choices[0].asJsonObject.get("finish_reason")?.asString ?: ""
            // 防御性读取 usage：字段缺失/非对象时按 0 处理，避免异常导致整次调用失败
            val usageObj = responseJson.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
            val completionTokens = usageObj?.get("completion_tokens")?.asInt ?: 0
            val truncated = finishReason == "length" ||
                (completionTokens > 0 && completionTokens >= MAX_TOKENS) ||
                isLikelyTruncated(content)
            if (truncated) {
                Log.w(TAG, "决策对话输出疑似截断（finish_reason='$finishReason', completion_tokens=$completionTokens/${MAX_TOKENS}, content 长度=${content.length}）")
            }

            // 解析 tool_calls（OpenAI 兼容格式）
            val toolCalls = mutableListOf<Map<String, Any>>()
            val toolCallsElem = messageObj.get("tool_calls")
            if (toolCallsElem != null && !toolCallsElem.isJsonNull) {
                val toolCallsArr = toolCallsElem.asJsonArray
                for (i in 0 until toolCallsArr.size()) {
                    val tc = toolCallsArr[i].asJsonObject
                    val id = tc.get("id")?.asString ?: "call_$i"
                    val function = tc.getAsJsonObject("function")
                    val name = function.get("name")?.asString ?: ""
                    val argsStr = function.get("arguments")?.asString ?: "{}"
                    val args = try {
                        @Suppress("UNCHECKED_CAST")
                        (gson.fromJson(argsStr, Map::class.java) as? Map<String, Any>)
                    } catch (_: Exception) {
                        null
                    }
                    toolCalls.add(buildMap {
                        put("id", id)
                        put("name", name)
                        put("arguments", args ?: emptyMap<String, Any>())
                    })
                }
            }

            Triple(content, toolCalls, truncated)
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "决策对话(带工具)请求超时", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "决策对话(带工具)调用异常", e)
            null
        }
    }

    /**
     * 构建决策模型请求体。useJsonFormat=true 时启用 response_format=json_object，
     * 由 API 层强制模型输出合法 JSON，避免裸文本/代码块包裹导致解析失败
     */
    private fun buildDecisionRequestBody(
        model: String,
        messages: List<Map<String, Any>>,
        toolChoiceJson: String,
        useJsonFormat: Boolean,
        maxTokens: Int = MAX_TOKENS
    ): String = buildString {
        append("{")
        append("\"model\":\"$model\",")
        append("\"messages\":${gson.toJson(messages)},")
        // 显式设置足够的输出上限（16384），避免长 plan（复杂任务可超 10 步）被截断；
        // 不传该字段时 API 使用默认上限（约 4096）仍会截断，必须显式给足
        append("\"max_tokens\":$maxTokens,")
        append("\"temperature\":$TEMPERATURE,")
        if (useJsonFormat) {
            // API 层结构化输出约束（OpenAI 兼容格式，与 function calling 可共存）
            append("\"response_format\":{\"type\":\"json_object\"},")
        }
        // 注入 tools 字段，让模型能主动调 list_apps / kb_read / amap_* / web_search
        // enable_search 已删除 — 联网搜索由 web_search 工具提供（与执行模型统一）
        append("\"tools\":${buildToolsJson()},")
        append("\"tool_choice\":$toolChoiceJson")
        append("}")
    }

    /**
     * 疑似截断检测（不依赖 finish_reason）：content 以 { 开头但 JSON 结构未闭合
     * （括号不平衡或字符串未终结）——JSON 模式下响应早断时的兜底信号。
     */
    private fun isLikelyTruncated(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{")) return false
        var depth = 0
        var inString = false
        var escaped = false
        for (c in trimmed) {
            when {
                inString -> {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                }
                c == '"' -> inString = true
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
            }
        }
        return inString || depth != 0
    }

    /** 执行决策模型 HTTP 请求，返回 (响应码, 响应体) */
    private fun executeDecisionRequest(
        apiUrl: String,
        apiKey: String,
        requestBody: String
    ): Pair<Int, String> {
        Log.d(TAG, "调用决策对话模型(带工具): url=$apiUrl, body 长度=${requestBody.length}")
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()
        return client.newCall(request).execute().use { resp ->
            Pair(resp.code, resp.body?.string() ?: "")
        }
    }

    /**
     * 执行高德工具调用，返回结果字符串（成功为结果内容，失败为错误说明）
     */
    private suspend fun executeAmapTool(name: String, args: Map<String, Any>): String {
        return try {
            when (name) {
                "amap_nearby" -> {
                    val keywords = args["keywords"]?.toString() ?: ""
                    val radius = (args["radius"] as? Number)?.toInt() ?: 1000
                    if (keywords.isBlank()) return "错误：keywords 参数不能为空"
                    val result = mcpService.amapNearby(keywords, radius)
                    if (result.success) result.content else "搜索失败：${result.error}"
                }
                "amap_search" -> {
                    val keywords = args["keywords"]?.toString() ?: ""
                    val city = args["city"]?.toString() ?: ""
                    if (keywords.isBlank()) return "错误：keywords 参数不能为空"
                    val result = mcpService.amapSearch(keywords, city)
                    if (result.success) result.content else "搜索失败：${result.error}"
                }
                "amap_weather" -> {
                    val result = mcpService.amapWeather()
                    if (result.success) result.content else "天气查询失败：${result.error}"
                }
                "amap_directions" -> {
                    val destination = args["destination"]?.toString() ?: ""
                    val mode = args["mode"]?.toString() ?: "drive"
                    if (destination.isBlank()) return "错误：destination 参数不能为空"
                    val result = mcpService.amapDirections(destination, mode)
                    if (result.success) result.content else "路线规划失败：${result.error}"
                }
                else -> "未知工具：$name"
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行工具 $name 异常", e)
            "工具执行异常：${e.message}"
        }
    }

    /**
     * 执行知识库工具调用，返回结果字符串（成功为检索内容，失败为错误说明）
     */
    private suspend fun executeKbTool(name: String, args: Map<String, Any>): String {
        return try {
            when (name) {
                "kb_read" -> {
                    val query = args["query"]?.toString() ?: ""
                    val topK = (args["top_k"] as? Number)?.toInt() ?: 3
                    val appFilter = args["app_filter"]?.toString()
                    // 三禁止之一：禁止无 app_filter 的全量检索（schema 已要求必填，这里兜底强制）
                    if (appFilter.isNullOrBlank()) {
                        return "错误：kb_read 必须传 app_filter（指定单个App名，一次只查一个App）。请先调用 list_apps 确认已安装的候选App，再对该App单独查询。"
                    }
                    val params = mutableMapOf<String, Any>("query" to query, "top_k" to topK)
                    params["app_filter"] = appFilter
                    val result = KbReadTool().execute(params)
                    if (result.isSuccess) result.data ?: "" else "知识库查询失败：${result.error}"
                }
                else -> "未知工具：$name"
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行知识库工具 $name 异常", e)
            "工具执行异常：${e.message}"
        }
    }

    /** 构建 OpenAI function calling 的 tools JSON 字符串 */
    private fun buildAmapToolsJson(): String {
        val tools = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "amap_nearby",
                    "description" to "搜索当前位置周边地点（自动用当前定位），用于'附近医院/餐厅'等。",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "keywords" to mapOf(
                                "type" to "string",
                                "description" to "搜索关键词，如'医院'、'餐厅'、'药店'、'ATM'"
                            ),
                            "radius" to mapOf(
                                "type" to "integer",
                                "description" to "搜索半径(米)，默认1000，最大5000",
                                "default" to 1000
                            )
                        ),
                        "required" to listOf("keywords")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "amap_search",
                    "description" to "按关键词搜索地点（可指定城市），查特定地点如'协和医院'。",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "keywords" to mapOf("type" to "string", "description" to "搜索关键词"),
                            "city" to mapOf("type" to "string", "description" to "城市名(可选)")
                        ),
                        "required" to listOf("keywords")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "amap_weather",
                    "description" to "查询当前天气。",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to emptyMap<String, Any>(),
                        "required" to emptyList<String>()
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "amap_directions",
                    "description" to "规划当前位置到目的地的路线，用于'怎么去X'。",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "destination" to mapOf("type" to "string", "description" to "目的地名称或地址"),
                            "mode" to mapOf(
                                "type" to "string",
                                "description" to "出行方式：drive(驾车)/walk(步行)/transit(公交)",
                                "enum" to listOf("drive", "walk", "transit"),
                                "default" to "drive"
                            )
                        ),
                        "required" to listOf("destination")
                    )
                )
            )
        )
        return gson.toJson(tools)
    }

    /**
     * 组装传给模型的 tools JSON 数组：始终包含 amap 工具和 list_apps 工具，
     * 当本地知识库启用时追加 kb_read 工具，当执行模型联网搜索启用时追加 web_search 工具。
     * LOCAL_KB_ENABLED=false 时模型完全无感知 kb_read。
     * EXECUTION_ENABLE_SEARCH=false 时模型完全无感知 web_search。
     */
    private fun buildToolsJson(): String {
        val amapArray = buildAmapToolsJson()
        val listAppsTool = buildListAppsToolJson()
        // amapArray 形如 "[{...},{...}]"，去掉首尾方括号取内部内容
        val amapInner = amapArray.removeSurrounding("[", "]")
        return buildString {
            append("[")
            append(amapInner)
            // list_apps 工具（始终可用，对话层查设备已装应用）
            append(",")
            append(listAppsTool)
            // ask_questions 工具（始终注入，用于结构化批量追问）
            append(",")
            append(buildAskQuestionsToolJson())
            // workspace_update 工具（始终注入）：模型把关键信息写入任务工作区（会话级持久化）
            append(",")
            append(buildWorkspaceToolJson())
            // fetch_result 工具（始终注入）：按 ref 取回磁盘缓存的完整工具结果（台账只展示预览）
            append(",")
            append(buildFetchResultToolJson())
            if (KVUtils.isLocalKbEnabled()) {
                append(",")
                append(buildKbToolsJson())
            }
            // 追加 web_search 工具（与执行模型共用开关与 WebSearchService 后端）
            if (KVUtils.isExecutionSearchEnabled()) {
                append(",")
                append(buildWebSearchToolJson())
            }
            append("]")
        }
    }

    /** 构建 workspace_update 工具的 OpenAI function calling schema（任务工作区写入，会话级持久化） */
    private fun buildWorkspaceToolJson(): String {
        val tool = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "workspace_update",
                "description" to "把本轮获取的关键信息（已确认App+包名、SOP步骤要点、用户确认项、待办）用你自己的话精简写入任务工作区（覆盖式更新）。" +
                    "工具结果会写入事实台账（同参数去重），工作区保留你提炼的关键结论，生成Plan时从工作区和台账读取。",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "content" to mapOf(
                            "type" to "string",
                            "description" to "工作区新内容（覆盖旧内容，精简，不超过800字）"
                        )
                    ),
                    "required" to listOf("content")
                )
            )
        )
        return gson.toJson(tool)
    }

    /** 构建 fetch_result 工具的 OpenAI function calling schema（按 ref 取回磁盘缓存的完整工具结果） */
    private fun buildFetchResultToolJson(): String {
        val tool = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "fetch_result",
                "description" to "按 ref 取回已缓存工具结果的完整内容（list_apps/kb_read/amap_*/web_search 等）。" +
                    "事实台账只展示预览，需要完整结果时用本工具分页取回：每次返回约4000字符，超过时用 offset 继续取下一段。",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "ref" to mapOf(
                            "type" to "string",
                            "description" to "缓存条目 ref（如 ws-3-2 / fx-12345678...），见台账中每行 [ref] 前缀"
                        ),
                        "offset" to mapOf(
                            "type" to "integer",
                            "description" to "分段取回偏移（字符），默认0；上次返回标记「还有N字符未返回」时用 offset=上次 end 继续",
                            "default" to 0
                        )
                    ),
                    "required" to listOf("ref")
                )
            )
        )
        return gson.toJson(tool)
    }

    /** 构建 ask_questions 工具的 OpenAI function calling schema（参考 GitHub Copilot） */
    private fun buildAskQuestionsToolJson(): String {
        val tool = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "ask_questions",
                "description" to "向用户批量澄清必要信息，一次性收齐所有硬性未知（1-4问，每问2-6选项，UI自动追加'其他'勿生成）。禁分多轮追问；答案可从历史/工具结果推断、或主观偏好可默认、或你可自行决定的事，不必问。收到答案后不得重复追问。",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "questions" to mapOf(
                            "type" to "array",
                            "minItems" to 1,
                            "maxItems" to 4,
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "question" to mapOf("type" to "string", "description" to "问题文本(≤30字)"),
                                    "header" to mapOf("type" to "string", "maxLength" to 12, "description" to "短标签(≤12字)"),
                                    "multiSelect" to mapOf("type" to "boolean", "default" to false),
                                    "allowFreeInput" to mapOf("type" to "boolean", "default" to true),
                                    "options" to mapOf(
                                        "type" to "array",
                                        "minItems" to 2,
                                        "maxItems" to 6,
                                        "items" to mapOf(
                                            "type" to "object",
                                            "properties" to mapOf(
                                                "label" to mapOf("type" to "string"),
                                                "description" to mapOf("type" to "string"),
                                                "recommended" to mapOf("type" to "boolean")
                                            ),
                                            "required" to listOf("label")
                                        )
                                    )
                                ),
                                "required" to listOf("question", "header", "options")
                            )
                        )
                    ),
                    "required" to listOf("questions")
                )
            )
        )
        return gson.toJson(tool)
    }

    /** 构建 web_search 工具的 OpenAI function calling schema */
    private fun buildWebSearchToolJson(): String {
        val tool = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "web_search",
                "description" to "联网搜实时信息（新闻/价格/天气/动态等）。涉及实时/近期事件先搜后答，勿凭训练知识回答实时问题。",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "搜索关键词"),
                        "count" to mapOf("type" to "integer", "description" to "返回结果数，默认5", "default" to 5)
                    ),
                    "required" to listOf("query")
                )
            )
        )
        return gson.toJson(tool)
    }

    /** 构建 list_apps 工具的 OpenAI function calling schema（单个工具对象） */
    private fun buildListAppsToolJson(): String {
        val tool = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "list_apps",
                "description" to "查询设备已装应用与应用名映射。可选 keywords（数组）按应用名模糊过滤，不传则返回全量。用于判断设备装了哪些 App、给 kb_read 的 app_filter 取值，返回的已装候选名需记录留用。",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "keywords" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                            "description" to "可选关键词数组，按应用名模糊过滤（如['支付宝']或['支付宝','交管12123']）。不传则返回全量列表。"
                        ),
                        "max_results" to mapOf(
                            "type" to "integer",
                            "description" to "最多返回结果数（1-200），默认50",
                            "default" to 50
                        )
                    ),
                    "required" to emptyList<String>()
                )
            )
        )
        return gson.toJson(tool)
    }

    /** 构建知识库查询工具的 OpenAI function calling schema（单个工具对象） */
    private fun buildKbToolsJson(): String {
        val tool = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "kb_read",
                "description" to "查询指定App的操作手册/SOP。必须先调用 list_apps 确认设备已安装的App，再对每个候选App单独调用本工具（app_filter 必填，一次只查一个App）。禁止无 app_filter 的全量检索、禁止对同一App重复调用、禁止跳过 list_apps 直接查库。",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "检索关键词或问题，如'微信发消息步骤'、'高德地图查路线'"
                        ),
                        "top_k" to mapOf(
                            "type" to "integer",
                            "description" to "返回结果数（1-5），默认3",
                            "default" to 3
                        ),
                        "app_filter" to mapOf(
                            "type" to "string",
                            "description" to "必填，按App过滤检索范围，如\"微信\"、\"高德地图\"，一次只传一个App名"
                        )
                    ),
                    "required" to listOf("query", "app_filter")
                )
            )
        )
        return gson.toJson(tool)
    }

    /** 解析模型返回内容为 DialogResult */
    private fun parseDialogResult(content: String): DialogResult {
        val jsonStr = extractJson(content)
        if (jsonStr.isEmpty()) {
            // 工具调用后模型可能返回自然语言（非 JSON），作为直接回复处理
            return if (content.isNotBlank()) {
                DialogResult.NeedMoreInfo(content)
            } else {
                DialogResult.Error("决策模型返回空内容")
            }
        }
        return try {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            val status = obj.get("status")?.asString ?: ""
            when (status) {
                "need_more_info" -> {
                    // need_more_info 文本输出仅用于查询类任务直接回答（message 字段）
                    // 追问必须通过 ask_questions 工具调用，文本 questions 不再支持
                    val message = obj.get("message")?.asString ?: ""
                    if (message.isBlank()) {
                        DialogResult.Error("决策模型返回 need_more_info 但缺少 message 字段（追问须调用 ask_questions 工具）")
                    } else {
                        DialogResult.NeedMoreInfo(message)
                    }
                }
                "ready" -> {
                    val planObj = obj.getAsJsonObject("plan")
                    if (planObj == null) {
                        DialogResult.Error("决策模型返回 ready 但缺少 plan 对象")
                    } else {
                        val plan = parsePlan(planObj)
                        if (plan.steps.isEmpty()) {
                            DialogResult.Error("决策模型返回 ready 但 steps 为空")
                        } else {
                            val userSummary = obj.get("user_summary")?.asString?.takeIf { it.isNotBlank() }
                                ?: PlanFormatter.extractSummary(plan)
                            DialogResult.Ready(plan, userSummary)
                        }
                    }
                }
                else -> {
                    DialogResult.Error("决策模型返回未知的 status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析决策对话响应失败: ${e.message}")
            DialogResult.Error("解析决策模型响应失败: ${e.message ?: "未知错误"}")
        }
    }

    /** 从 JSON 对象解析结构化 Plan */
    private fun parsePlan(planObj: JsonObject): Plan {
        val requirement = planObj.get("requirement")?.asString ?: ""
        val goal = planObj.get("goal")?.asString ?: ""
        val stepsArr = planObj.getAsJsonArray("steps")
        val steps = if (stepsArr != null) {
            stepsArr.mapIndexed { idx, elem ->
                val s = elem.asJsonObject
                PlanStep(
                    order = s.get("order")?.asInt ?: (idx + 1),
                    goal = s.get("goal")?.asString ?: "",
                    successCriteria = s.get("success_criteria")?.asString ?: "",
                    supervised = s.get("supervised")?.asBoolean ?: false,
                    toolHint = s.get("tool_hint")?.asString ?: ""
                )
            }.filter { it.goal.isNotBlank() }
        } else {
            emptyList()
        }
        return Plan(requirement, goal, steps)
    }

    /**
     * 从 ask_questions 工具参数解析 questions 数组（结构化批量追问）
     * 工具参数已是结构化 Map，无需 JSON 解析。校验：每问必须有 question 文本和 ≥2 个合法选项
     * @return 合法问题列表（≥1 个）；整体非法返回空列表
     */
    private fun parseQuestionsFromToolArgs(args: Map<String, Any>): List<Question> {
        val rawList = args["questions"] as? List<*> ?: return emptyList()
        return rawList.mapNotNull { item ->
            val qMap = item as? Map<*, *> ?: return@mapNotNull null
            val questionText = qMap["question"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val header = qMap["header"]?.toString()?.takeIf { it.isNotBlank() } ?: questionText.take(12)
            val options = (qMap["options"] as? List<*>)?.mapNotNull { o ->
                val oMap = o as? Map<*, *> ?: return@mapNotNull null
                val label = oMap["label"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                QuestionOption(
                    label = label,
                    description = oMap["description"]?.toString(),
                    recommended = oMap["recommended"] as? Boolean ?: false
                )
            } ?: emptyList()
            if (options.size < 2) return@mapNotNull null
            Question(
                question = questionText,
                header = header,
                options = options,
                multiSelect = qMap["multiSelect"] as? Boolean ?: false,
                allowFreeInput = qMap["allowFreeInput"] as? Boolean ?: true
            )
        }
    }

    /** 从模型返回内容中提取 JSON（处理 markdown 代码块包裹） */
    private fun extractJson(content: String): String {
        if (content.trim().startsWith("{")) {
            return content.trim()
        }
        val codeBlockRegex = Regex("""```(?:json)?\s*(\{[\s\S]*?\})\s*```""")
        val match = codeBlockRegex.find(content)
        if (match != null) {
            return match.groupValues[1]
        }
        val firstBrace = content.indexOf('{')
        val lastBrace = content.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return content.substring(firstBrace, lastBrace + 1)
        }
        return ""
    }

    private fun normalizeApiUrl(baseUrl: String): String {
        if (baseUrl.isBlank()) return ""
        val trimmed = baseUrl.trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    private fun parseErrorMessage(body: String, code: Int): String {
        return try {
            val errorMap = gson.fromJson(body, Map::class.java)
            (errorMap["error"] as? Map<*, *>)?.get("message")?.toString()
                ?: (errorMap["message"]?.toString())
                ?: "HTTP $code"
        } catch (e: Exception) {
            "HTTP $code: ${body.take(200)}"
        }
    }
}
