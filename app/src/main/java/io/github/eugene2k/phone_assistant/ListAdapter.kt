package io.github.eugene2k.phone_assistant

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

internal abstract class ListAdapter<T : ListAdapter.Item?>(var mContext: Context) :
    RecyclerView.Adapter<ListAdapter.ViewHolder>() {
    internal interface Item {
        val label: String?
    }

    internal class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val view: View
            get() = this.itemView
    }

    var mList: ArrayList<T> = ArrayList()

    fun add(item: T) {
        mList.add(item)
    }

    override fun getItemCount(): Int {
        return mList.size
    }

    override fun getItemId(i: Int): Long {
        return i.toLong()
    }

    abstract override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder
    abstract override fun onBindViewHolder(holder: ViewHolder, position: Int)


    abstract fun onAction(item: T)
}