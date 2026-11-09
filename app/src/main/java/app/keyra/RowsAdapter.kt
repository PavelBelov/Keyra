package app.keyra

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.keyra.databinding.RowItemBinding
import java.util.UUID

data class Row(val id: UUID, val title: String, val subtitle: String, val folder: Boolean, val icon: VaultIcon)
class RowsAdapter(private val click: (Row) -> Unit, private val longClick: (Row) -> Unit) : ListAdapter<Row, RowsAdapter.Holder>(object : DiffUtil.ItemCallback<Row>() {
    override fun areItemsTheSame(old: Row, new: Row) = old.id == new.id
    override fun areContentsTheSame(old: Row, new: Row) = old == new
}) {
    class Holder(val binding: RowItemBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(RowItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.binding.title.text = row.title
        holder.binding.subtitle.text = row.subtitle
        holder.binding.subtitle.visibility = if (row.subtitle.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        val bitmap = row.icon.bitmap
        if (bitmap != null) holder.binding.icon.setImageBitmap(bitmap)
        else holder.binding.icon.setImageResource(row.icon.resourceId)
        holder.binding.root.setOnClickListener { click(row) }
        holder.binding.root.setOnLongClickListener { longClick(row); true }
    }
}
