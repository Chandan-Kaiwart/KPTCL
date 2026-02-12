package com.apc.kptcl.home.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.databinding.ItemFeederHourlyColumnBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * FIXED Adapter - Shows ALL parameters in separate columns
 * Simplified color logic based on data presence only
 */
class FeederHourlyViewAdapter : RecyclerView.Adapter<FeederHourlyViewAdapter.ViewHolder>() {

    private val dataList = mutableListOf<FeederHourlyData>()
    private var onItemClickListener: ((FeederHourlyData) -> Unit)? = null

    companion object {
        private const val EMPTY_VALUE = "-"

        // Simplified Status Colors - Based on data presence only
        private const val COLOR_HAS_DATA = "#4CAF50"    // Green - Has data
        private const val COLOR_NO_DATA = "#F44336"      // Red - No data
        private const val COLOR_ZERO = "#FF9800"         // Orange - Zero value
    }

    /**
     * Submit new list of data - SORTED in required order: IR, IY, IB, MW, MVAR
     */
    fun submitList(list: List<FeederHourlyData>) {
        android.util.Log.d("FeederAdapter", "📊 submitList called with ${list.size} items")

        // ✅ Define the correct parameter order
        val parameterOrder = listOf("IR", "IY", "IB", "MW", "MVAR")

        // ✅ Sort the list according to the defined order
        val sortedList = list.sortedBy { data ->
            val index = parameterOrder.indexOf(data.parameter.uppercase())
            if (index == -1) Int.MAX_VALUE else index  // Unknown parameters go to end
        }

        val diffCallback = FeederHourlyDiffCallback(dataList, sortedList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        dataList.clear()
        dataList.addAll(sortedList)

        android.util.Log.d("FeederAdapter", "📊 Adapter now has ${dataList.size} items")
        android.util.Log.d("FeederAdapter", "📊 Parameters (sorted): ${dataList.map { it.parameter }.distinct()}")

        diffResult.dispatchUpdatesTo(this)
    }

    /**
     * Clear all data
     */
    fun clearData() {
        dataList.clear()
        notifyDataSetChanged()
    }

    /**
     * Get data at position
     */
    fun getItem(position: Int): FeederHourlyData? {
        return if (position in dataList.indices) dataList[position] else null
    }

    /**
     * Set item click listener
     */
    fun setOnItemClickListener(listener: (FeederHourlyData) -> Unit) {
        onItemClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFeederHourlyColumnBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onItemClickListener)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        android.util.Log.d("FeederAdapter", "📊 Binding position $position: ${dataList[position].parameter}")
        holder.bind(dataList[position], position)
    }

    override fun getItemCount(): Int {
        android.util.Log.d("FeederAdapter", "📊 getItemCount: ${dataList.size}")
        return dataList.size
    }

    /**
     * ViewHolder class with SIMPLIFIED color logic
     */
    class ViewHolder(
        private val binding: ItemFeederHourlyColumnBinding,
        private val clickListener: ((FeederHourlyData) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(data: FeederHourlyData, position: Int) {
            binding.apply {
                // Set background color based on position (alternating colors)
                root.setBackgroundColor(
                    if (position % 2 == 0) Color.parseColor("#F5F5F5")
                    else Color.WHITE
                )

                // Top 4 rows: Feeder metadata (non-editable)

                tvParam.text = data.parameter.ifEmpty { EMPTY_VALUE }

                // Apply color coding based on parameter type
                applyParameterColor(tvParam, data.parameter)

                // ✅ FIXED: Correct hour mapping - Display Column 1-24 → DB Hour 00-23
                setHourValueSimple(etHour00, data.hourlyValues["00"])
                setHourValueSimple(etHour01, data.hourlyValues["01"])
                setHourValueSimple(etHour02, data.hourlyValues["02"])
                setHourValueSimple(etHour03, data.hourlyValues["03"])
                setHourValueSimple(etHour04, data.hourlyValues["04"])
                setHourValueSimple(etHour05, data.hourlyValues["05"])
                setHourValueSimple(etHour06, data.hourlyValues["06"])
                setHourValueSimple(etHour07, data.hourlyValues["07"])
                setHourValueSimple(etHour08, data.hourlyValues["08"])
                setHourValueSimple(etHour09, data.hourlyValues["09"])
                setHourValueSimple(etHour10, data.hourlyValues["10"])
                setHourValueSimple(etHour11, data.hourlyValues["11"])
                setHourValueSimple(etHour12, data.hourlyValues["12"])
                setHourValueSimple(etHour13, data.hourlyValues["13"])
                setHourValueSimple(etHour14, data.hourlyValues["14"])
                setHourValueSimple(etHour15, data.hourlyValues["15"])
                setHourValueSimple(etHour16, data.hourlyValues["16"])
                setHourValueSimple(etHour17, data.hourlyValues["17"])
                setHourValueSimple(etHour18, data.hourlyValues["18"])
                setHourValueSimple(etHour19, data.hourlyValues["19"])
                setHourValueSimple(etHour20, data.hourlyValues["20"])
                setHourValueSimple(etHour21, data.hourlyValues["21"])
                setHourValueSimple(etHour22, data.hourlyValues["22"])
                setHourValueSimple(etHour23, data.hourlyValues["23"])

                // Set click listener
                root.setOnClickListener {
                    clickListener?.invoke(data)
                }
            }
        }

        /**
         * SIMPLIFIED: Set hour value with color based ONLY on data presence
         */
        private fun setHourValueSimple(
            textView: android.widget.TextView,
            value: Double?
        ) {
            // Set text value
            textView.text = when {
                value == null -> EMPTY_VALUE
                value == 0.0 -> "0"
                value == value.toInt().toDouble() -> value.toInt().toString()
                else -> String.format("%.2f", value)
            }

            // Determine color based ONLY on data presence (no date comparison)
            val statusColor = when {
                value == null -> COLOR_NO_DATA      // Red - No data
                value == 0.0 -> COLOR_ZERO          // Orange - Zero value
                else -> COLOR_HAS_DATA              // Green - Has data
            }

            // Apply colored border to indicate status
            applyStatusIndicator(textView, statusColor)
        }

        /**
         * Apply status indicator (colored border)
         */
        private fun applyStatusIndicator(textView: android.widget.TextView, colorHex: String) {
            val drawable = GradientDrawable()
            drawable.setColor(Color.WHITE)
            drawable.setStroke(3, Color.parseColor(colorHex)) // 3px colored border
            drawable.cornerRadius = 4f
            textView.background = drawable

            // Subtle text color tint
            textView.setTextColor(Color.parseColor("#000000"))
        }

        /**
         * Apply color coding based on parameter type
         */
        private fun applyParameterColor(textView: android.widget.TextView, parameter: String) {
            val color = when (parameter.uppercase()) {
                "IR" -> Color.parseColor("#FF9800") // Orange
                "IY" -> Color.parseColor("#F44336") // Red
                "IB" -> Color.parseColor("#9C27B0") // Purple ← ADDED
                "MW" -> Color.parseColor("#4CAF50") // Green
                "MVAR" -> Color.parseColor("#2196F3") // Blue
                else -> Color.parseColor("#757575") // Gray
            }
            textView.setTextColor(color)
            textView.setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    /**
     * DiffUtil callback for efficient list updates
     */
    private class FeederHourlyDiffCallback(
        private val oldList: List<FeederHourlyData>,
        private val newList: List<FeederHourlyData>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]

            return oldItem.id == newItem.id &&
                    oldItem.parameter == newItem.parameter &&
                    oldItem.hourlyValues == newItem.hourlyValues
        }
    }
}