package com.palmagent.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * ScreenVlmDescribeService.parseAdMarker 单元测试（P0-1 修复回归）
 *
 * 验证目标：
 * 1. 合法 4 段标记 + 正文含"广告" → AdInfo(isAd=true)
 * 2. "否/无" 前缀 → AdInfo(false)
 * 3. 字段不足（<4）→ 格式异常降级，继续扫描其它行或返回 false
 * 4. 多标记行 → 取第一条有效"是"的
 * 5. 字样闸门正例/反例（回归点：旧实现把前缀♦️广告♦️本身当特征词，闸门恒真失效）
 * 6. isAd 精确匹配（parts[0] 不以"是"开头即否，避免 contains 误命中"否/是否"）
 * 7. 无标记行 → null（维持"宁可漏判不误关"）
 * 8. 标记中含"广告"字样但判定为否 → 不误关（过了 isAd 检查才进闸门）
 */
class ScreenVlmDescribeAdMarkerTest {

    private lateinit var parseAdMarker: Method

    @Before
    fun setUp() {
        parseAdMarker = ScreenVlmDescribeService::class.java.getDeclaredMethod("parseAdMarker", String::class.java)
        parseAdMarker.isAccessible = true
    }

    private fun parse(content: String): Any? =
        parseAdMarker.invoke(ScreenVlmDescribeService, content)

    private fun adInfoOf(content: String): ScreenVlmDescribeService.AdInfo =
        (parse(content) as? ScreenVlmDescribeService.AdInfo) ?: error("返回应为 AdInfo")

    // 1. 合法 4 段 + 正文含"广告"
    @Test
    fun `合法标记且正文含广告字样 返回isAd true并解析四字段`() {
        val content = "屏幕中央有弹窗，标注广告字样\n◆广告◆是|开屏广告|否|跳过=屏幕右上角"
        val info = adInfoOf(content)
        assertTrue(info.isAd)
        assertEquals("开屏广告", info.adType)
        assertFalse(info.autoSkip)
        assertEquals("跳过=屏幕右上角", info.closeButton)
    }

    // 1b. autoSkip 为"是"
    @Test
    fun `自动跳过为是 解析autoSkip true`() {
        val content = "广告推广\n◆广告◆是|插屏广告|是|关闭=弹窗右上角"
        val info = adInfoOf(content)
        assertTrue(info.isAd)
        assertTrue(info.autoSkip)
        assertEquals("关闭=弹窗右上角", info.closeButton)
    }

    // 2. "否/无" 前缀
    @Test
    fun `标记内容为否 返回isAd false`() {
        val content = "正常页面\n◆广告◆否"
        val info = adInfoOf(content)
        assertFalse(info.isAd)
    }

    @Test
    fun `标记内容为无 返回isAd false`() {
        val content = "正常页面\n◆广告◆无"
        val info = adInfoOf(content)
        assertFalse(info.isAd)
    }

    // 3. 字段不足（<4）→ 降级不误关
    @Test
    fun `标记字段不足4个 降级为isAd false`() {
        val content = "广告弹窗\n◆广告◆是|开屏广告"
        val info = adInfoOf(content)
        assertFalse("字段不足应降级不误关", info.isAd)
    }

    // 3b. 字段不足的行前有合法"是"行 → 取合法行
    @Test
    fun `字段不足行在前 合法行在后 取合法行`() {
        val content = "页面内容\n◆广告◆是|损坏行\n◆广告◆是|开屏广告|否|跳过=右上角"
        val info = adInfoOf(content)
        assertTrue(info.isAd)
        assertEquals("开屏广告", info.adType)
    }

    // 4. 多标记行 → 取第一条"是"的有效行
    @Test
    fun `多条标记行 第一条是即可 不等待后续`() {
        val content = "◆广告◆是|横幅广告|是|×=右下角\n◆广告◆是|插屏广告|否|关闭=中央"
        val info = adInfoOf(content)
        assertTrue(info.isAd)
        assertEquals("横幅广告", info.adType)
        assertEquals("×=右下角", info.closeButton)
    }

    // 5. 闸门反例：正文与 adType 均无"广告"字样 → 拒绝（旧实现恒真失效的回归点）
    @Test
    fun `闸门反例 正文无广告字样 拒绝误关`() {
        val content = "确认兑换信息，底部按钮立即兑换\n◆广告◆是|功能弹窗|否|取消=右下角"
        val info = adInfoOf(content)
        assertFalse("业务功能弹窗无广告字样不应误关", info.isAd)
    }

    // 5b. 闸门正例：adType 字段含"广告"即可过闸（正文可不含）
    @Test
    fun `闸门正例 adType字段含广告字样 判定为广告`() {
        val content = "◆广告◆是|广告|是|跳过=右上角"
        val info = adInfoOf(content)
        assertTrue(info.isAd)
    }

    // 6. parts[0] 不以"是"开头（如"确定/是么"）→ 精确匹配不命中 → 不判广告
    //    （"否/无"前缀会先命中"否/无"分支返回 false，这里验证"是"精确匹配的兜底路径）
    @Test
    fun `parts0非是开头 精确匹配不命中 判定非广告`() {
        val content = "页面\n◆广告◆不确定|类型|否|按钮=位置"
        val info = adInfoOf(content)
        assertFalse("parts[0]='不确定' 精确匹配应判非广告", info.isAd)
    }

    // 7. 无标记行 → null
    @Test
    fun `无标记行 返回null`() {
        assertNull(parse("普通屏幕描述：有标题和按钮"))
    }

    // 8. 判定为"否"但正文含"广告" → 不误关（闸门在 isAd 检查之后）
    @Test
    fun `判定否但正文含广告字样 不误关`() {
        val content = "该页面禁止展示广告内容\n◆广告◆否"
        val info = adInfoOf(content)
        assertFalse(info.isAd)
    }

    // 9. 标记粘在段落中间（整行不以 ◆广告◆ 开头）→ 不在独立行，返回 null（宁可漏判不误关）
    @Test
    fun `标记非独立行 忽略不解析`() {
        val content = "这是◆广告◆是|开屏广告|否|跳过=右上角的描述"
        assertNull("非独立行的标记应忽略并保持原描述", parse(content))
    }

    // 10. 描述中正文含广告字样但标记行合法 → 正常判定为广告
    @Test
    fun `正文与标记均含广告字样 正常判定`() {
        val content = "屏幕显示广告卡片\n◆广告◆是|信息流广告|是|关闭=卡片右上角"
        val info = adInfoOf(content)
        assertTrue(info.isAd)
        assertEquals("信息流广告", info.adType)
    }
}