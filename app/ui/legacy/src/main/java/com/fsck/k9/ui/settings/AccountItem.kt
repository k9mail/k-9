package com.fsck.k9.ui.settings

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fsck.k9.Account
import com.fsck.k9.ui.R
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.drag.IDraggable
import com.mikepenz.fastadapter.items.AbstractItem
import com.mikepenz.fastadapter.listeners.TouchEventHook

class AccountItem(val account: Account) : AbstractItem<AccountItem.ViewHolder>(), IDraggable {
    override var identifier = 200L + account.accountNumber

    override val type = R.id.settings_list_account_item

    override val layoutRes = R.layout.account_list_item

    override var isDraggable = true

    fun withIsDraggable(): Boolean {
        return isDraggable
    }

    override fun getViewHolder(v: View) = ViewHolder(v)

    class ViewHolder(view: View) : FastAdapter.ViewHolder<AccountItem>(view) {
        val name: TextView = view.findViewById(R.id.name)
        val email: TextView = view.findViewById(R.id.email)
        val drag_handle: ImageView = view.findViewById(R.id.drag_handle)

        /*
        override fun onBind(viewHolder: RecyclerView.ViewHolder): View? {
        return if (viewHolder is DraggableSingleLineItem.ViewHolder) viewHolder.dragHandle else null
    }
         */
        override fun bindView(item: AccountItem, payloads: List<Any>) {
            name.text = item.account.description
            email.text = item.account.email
        }

        override fun unbindView(item: AccountItem) {
            name.text = null
            email.text = null
        }
    }
}
class DragHandleTouchEvent(val action: (position: Int) -> Unit) : TouchEventHook<AccountItem>() {
    override fun onBind(viewHolder: RecyclerView.ViewHolder): View? {
        return if (viewHolder is AccountItem.ViewHolder) viewHolder.drag_handle else null
    }

    override fun onTouch(
        v: View,
        event: MotionEvent,
        position: Int,
        fastAdapter: FastAdapter<AccountItem>,
        item: AccountItem
    ): Boolean {
        return if (event.action == MotionEvent.ACTION_DOWN) {
            action(position)
            true
        } else {
            false
        }
    }
}