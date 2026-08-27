package com.palmagent.app.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.palmagent.app.R
import com.palmagent.app.data.local.dao.SessionWithPreview
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表适配器（侧边抽屉）。
 * - 点击：切换会话
 * - 长按：删除会话（由外部弹确认框）
 */
class SessionAdapter(
    private val onSessionClick: (SessionWithPreview) -> Unit,
    private val onSessionLongClick: (SessionWithPreview) -> Unit
) : ListAdapter<SessionWithPreview, SessionAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SessionWithPreview>() {
            override fun areItemsTheSame(
                oldItem: SessionWithPreview,
                newItem: SessionWithPreview
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: SessionWithPreview,
                newItem: SessionWithPreview
            ): Boolean = oldItem == newItem
        }

        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvSessionName)
        private val tvPreview: TextView = itemView.findViewById(R.id.tvSessionPreview)
        private val tvTime: TextView = itemView.findViewById(R.id.tvSessionTime)
        private val tvWechatBadge: TextView = itemView.findViewById(R.id.tvWechatBadge)

        fun bind(item: SessionWithPreview) {
            tvName.text = item.name
            tvPreview.text = item.preview ?: "（空会话）"
            tvTime.text = TIME_FORMAT.format(Date(item.updatedAt))
            // 微信来源标识：仅微信通道会话显示绿色"微信"徽章
            tvWechatBadge.visibility = if (item.source == "WECHAT") View.VISIBLE else View.GONE
            itemView.setOnClickListener { onSessionClick(item) }
            itemView.setOnLongClickListener {
                onSessionLongClick(item)
                true
            }
        }
    }
}
