package com.saamcito.aiva.presentation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.saamcito.aiva.R

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<UiChatMessage>()

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AI = 1
    }

    fun addMessage(message: UiChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastAiMessage(text: String) {
        val lastAiIndex = messages.indexOfLast { it.sender == UiChatMessage.Sender.AI }
        if (lastAiIndex != -1) {
            messages[lastAiIndex] = messages[lastAiIndex].copy(text = text)
            notifyItemChanged(lastAiIndex)
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].sender == UiChatMessage.Sender.USER) TYPE_USER else TYPE_AI

    override fun getItemCount() = messages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            val view = inflater.inflate(R.layout.item_message_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_message_ai, parent, false)
            AiViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is UserViewHolder -> holder.bind(msg)
            is AiViewHolder -> holder.bind(msg)
        }
    }

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvText: TextView = view.findViewById(R.id.tvMessageText)
        private val tvTime: TextView = view.findViewById(R.id.tvTimestamp)
        fun bind(msg: UiChatMessage) {
            tvText.text = msg.text
            tvTime.text = msg.timestamp
        }
    }

    class AiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvText: TextView = view.findViewById(R.id.tvMessageText)
        private val tvTime: TextView = view.findViewById(R.id.tvTimestamp)
        fun bind(msg: UiChatMessage) {
            tvText.text = msg.text
            tvTime.text = msg.timestamp
        }
    }
}
