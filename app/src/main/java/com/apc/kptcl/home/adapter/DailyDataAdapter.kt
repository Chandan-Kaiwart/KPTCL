package com.apc.kptcl.home.adapter

import android.text.Editable
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.databinding.ItemDailyParameterVerticalBinding
import com.apc.kptcl.home.users.daily.DailyDataRow
import com.google.android.material.textfield.TextInputEditText
import android.text.TextWatcher

class DailyDataAdapter : RecyclerView.Adapter<DailyDataAdapter.ViewHolder>() {

    private val rows = mutableListOf<DailyDataRow>()

    companion object {
        private const val TAG = "DailyDataAdapter"
    }

    fun submitList(list: List<DailyDataRow>) {
        rows.clear()
        rows.addAll(list)

        Log.d(TAG, "📋 submitList called with ${list.size} rows")
        list.forEachIndexed { index, row ->
            Log.d(TAG, "  Row $index: Feeder=${row.feederName}, Category='${row.feederCategory}'")
        }

        notifyDataSetChanged()
    }

    fun getDailyData(): List<DailyDataRow> = rows

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDailyParameterVerticalBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount(): Int = rows.size

    class ViewHolder(
        private val binding: ItemDailyParameterVerticalBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private lateinit var currentRow: DailyDataRow

        fun bind(row: DailyDataRow) {
            currentRow = row

            Log.d(TAG, "🔍 Binding row - Feeder: ${row.feederName}, Category: '${row.feederCategory}'")

            // ✅ CRITICAL FIX: Handle null/empty values properly
            binding.tvFeederName.text = row.feederName.ifEmpty { "-" }

            binding.etFeederCode.text = row.feederCode?.ifEmpty { "-" }

            binding.etFeederCategory.text = row.feederCategory.ifEmpty { "-" }

            // ✅ Handle nullable String properly
            binding.etRemark.text = if (row.remark.isNullOrEmpty()) {
                "-"
            } else {
                row.remark
            }

            // ✅ Handle numeric values - show number or dash
            binding.etTotalConsumption.text = if (row.totalConsumption.isNullOrEmpty()) {
                "-"
            } else {
                // Try to format as number
                try {
                    val value = row.totalConsumption.toDoubleOrNull()
                    if (value != null) {
                        // Format with 2 decimal places
                        String.format("%.2f", value)
                    } else {
                        row.totalConsumption
                    }
                } catch (e: Exception) {
                    row.totalConsumption
                }
            }

            binding.etSupply3PH.text = if (row.supply3PH.isNullOrEmpty()) {
                "-"
            } else {
                row.supply3PH
            }

            binding.etSupply1PH.text = if (row.supply1PH.isNullOrEmpty()) {
                "-"
            } else {
                row.supply1PH
            }

            // ✅ DEBUG: Log what's being displayed
            Log.d(TAG, "  📝 Display values:")
            Log.d(TAG, "     Feeder Name: '${binding.tvFeederName.text}'")
            Log.d(TAG, "     Feeder Code: '${binding.etFeederCode.text}'")
            Log.d(TAG, "     Category: '${binding.etFeederCategory.text}'")
            Log.d(TAG, "     Remark: '${binding.etRemark.text}'")
            Log.d(TAG, "     Total Consumption: '${binding.etTotalConsumption.text}'")
            Log.d(TAG, "     Supply 3PH: '${binding.etSupply3PH.text}'")
            Log.d(TAG, "     Supply 1PH: '${binding.etSupply1PH.text}'")
        }

        private fun setupTextInput(
            editText: TextInputEditText,
            initialValue: String,
            onValueChanged: (String) -> Unit
        ) {
            editText.setText(initialValue)
            editText.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    onValueChanged(s.toString())
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
    }
}