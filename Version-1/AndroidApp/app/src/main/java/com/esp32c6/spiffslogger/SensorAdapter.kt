package com.esp32c6.spiffslogger

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SensorAdapter : RecyclerView.Adapter<SensorAdapter.VH>() {

    private val data = mutableListOf<SensorRecord>()

    fun updateData(list: List<SensorRecord>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    fun clear() {
        data.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount() = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            setPadding(4, 6, 4, 6)
        }
        fun makeCell(weight: Float): TextView {
            return TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(2, 2, 2, 2)
            }
        }
        val tv0 = makeCell(0f).also { it.layoutParams = LinearLayout.LayoutParams(
            dpToPx(parent.context, 28), LinearLayout.LayoutParams.WRAP_CONTENT) }
        val tv1 = makeCell(1f)
        val tv2 = makeCell(1f)
        val tv3 = makeCell(1.3f)
        val tv4 = makeCell(1f)
        row.addView(tv0); row.addView(tv1); row.addView(tv2)
        row.addView(tv3); row.addView(tv4)
        return VH(row, tv0, tv1, tv2, tv3, tv4)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec = data[position]
        holder.tvIdx.text   = rec.index.toString()
        holder.tvT.text     = "%.1f".format(rec.tempAht)
        holder.tvH.text     = "%.1f".format(rec.humidity)
        holder.tvP.text     = "%.1f".format(rec.pressure)
        holder.tvTb.text    = "%.1f".format(rec.tempBmp)
        holder.row.setBackgroundColor(
            if (position % 2 == 0) Color.WHITE else Color.parseColor("#EEEEEE")
        )
    }

    private fun dpToPx(ctx: android.content.Context, dp: Int): Int {
        return (dp * ctx.resources.displayMetrics.density + 0.5f).toInt()
    }

    class VH(
        val row:   LinearLayout,
        val tvIdx: TextView,
        val tvT:   TextView,
        val tvH:   TextView,
        val tvP:   TextView,
        val tvTb:  TextView
    ) : RecyclerView.ViewHolder(row)
}
