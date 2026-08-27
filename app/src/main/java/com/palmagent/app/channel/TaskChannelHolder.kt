package com.palmagent.app.channel

/**
 * 全局任务通道持有器
 *
 * 跨模块共享当前任务来源通道，供非 Hilt 注入的组件（AskUserManager、ActionExecutor 等）
 * 判断当前任务来源，从而适配不同的交互逻辑（本地弹窗 vs 微信文本消息）。
 *
 * 生命周期：
 * - TaskOrchestrator.startNewTask() 时设置 activeChannel
 * - TaskOrchestrator.releaseTask() / onTaskFinished() 时重置为 LOCAL
 */
object TaskChannelHolder {

    @Volatile
    var activeChannel: Channel = Channel.LOCAL

    fun isWeChat(): Boolean = activeChannel == Channel.WECHAT

    fun isLocal(): Boolean = activeChannel == Channel.LOCAL

    fun reset() {
        activeChannel = Channel.LOCAL
    }
}
