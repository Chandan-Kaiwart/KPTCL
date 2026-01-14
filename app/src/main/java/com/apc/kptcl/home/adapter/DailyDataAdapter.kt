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

    fun submitList(list: List<DailyDataRow>) {
        rows.clear()
        rows.addAll(list)

        // ADD THIS LOG
        Log.d("DailyDataAdapter", "📋 submitList called with ${list.size} rows")
        list.forEachIndexed { index, row ->
            Log.d("DailyDataAdapter", "  Row $index: Feeder=${row.feederName}, Category='${row.feederCategory}'")
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

            // ADD THIS LOG - debugging ke liye
            Log.d("DailyDataAdapter", "🔍 Binding row - Feeder: ${row.feederName}, Category: '${row.feederCategory}'")

            // Set all values as TextViews (read-only display)
            binding.tvFeederName.text = row.feederName
            binding.etFeederCode.text = row.feederCode
            binding.etFeederCategory.text = row.feederCategory
            binding.etRemark.text = row.remark
            binding.etTotalConsumption.text = row.totalConsumption
            binding.etSupply3PH.text = row.supply3PH
            binding.etSupply1PH.text = row.supply1PH
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