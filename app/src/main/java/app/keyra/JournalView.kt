package app.keyra

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import java.text.DateFormat
import java.util.Date

class JournalView(context: Context) : LinearLayout(context) {
    private val empty = TextView(context).apply {
        text = L10n.text(context, R.string.ui_no_log_entries_41); textSize = 16f
        setTextColor(ContextCompat.getColor(context, R.color.settings_secondary))
        setPadding(24, 24, 24, 24)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    private val rows = object : ListAdapter<JournalEntry, Holder>(object : DiffUtil.ItemCallback<JournalEntry>() {
        override fun areItemsTheSame(old: JournalEntry, new: JournalEntry) = old == new
        override fun areContentsTheSame(old: JournalEntry, new: JournalEntry) = old == new
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val body = LinearLayout(context).apply {
                orientation = VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                setBackgroundResource(R.drawable.bg_settings_card)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            }
            val date = TextView(context).apply { textSize = 12f; setTextColor(ContextCompat.getColor(context, R.color.settings_secondary)) }
            val info = TextView(context).apply {
                textSize = 16f; setTextColor(ContextCompat.getColor(context, R.color.settings_text))
                setPadding(0, dp(6), 0, 0); setTypeface(typeface, Typeface.NORMAL)
            }
            body.addView(date); body.addView(info)
            return Holder(body, date, info)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = getItem(position)
            holder.date.text = dateFormat.format(Date.from(entry.time))
            holder.info.text = entry.information
        }
    }
    init {
        orientation = VERTICAL
        addView(empty)
        addView(RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context); adapter = rows
            setPadding(dp(14), dp(16), dp(14), dp(24)); clipToPadding = false
        }, LayoutParams(-1, 0, 1f))
    }
    fun show(entries: List<JournalEntry>) {
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        rows.submitList(entries)
    }
    private class Holder(view: View, val date: TextView, val info: TextView) : RecyclerView.ViewHolder(view)
}
