package com.apc.kptcl.home.users.daily

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentElogEntryBinding
import com.apc.kptcl.utils.SessionManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

//class DailyEditFragment : Fragment() {
//
//    private var _binding: FragmentElogEntryBinding? = null
//    private val binding get() = _binding!!
//
//    private val calendar = Calendar.getInstance()
//    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
//    private val displayDateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
//
//    private lateinit var adapter: DailyEditAdapter
//
//    private var stationName: String = ""
//    private val allFeeders = mutableListOf<FeederData>()
//    private var selectedFeeder: FeederData? = null
//
//    companion object {
//        private const val TAG = "DailyEdit"
//        private const val FEEDER_LIST_URL = "http://62.72.59.119:8000/api/feeder/list"
//        private const val FETCH_URL = "http://62.72.59.119:8000/api/feeder/consumption/edit/fetch"
//        private const val SAVE_URL = "http://62.72.59.119:8000/api/feeder/consumption/edit/save"
//        private const val TIMEOUT = 15000
//    }
//
//    override fun onCreateView(
//        inflater: LayoutInflater,
//        container: ViewGroup?,
//        savedInstanceState: Bundle?
//    ): View {
//        _binding = FragmentElogEntryBinding.inflate(inflater, container, false)
//        return binding.root
//    }
//
//    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//        super.onViewCreated(view, savedInstanceState)
//
//        if (!SessionManager.isLoggedIn(requireContext())) {
//            Snackbar.make(binding.root, "Please login first", Snackbar.LENGTH_LONG).show()
//            return
//        }
//
//        Log.d(TAG, "✅ User logged in")
//
//        setupDatePicker()
//        setupRecyclerView()
//        setupButtons()
//        updateDateDisplay()
//
//        fetchFeederList()
//    }
//
//    private fun setupDatePicker() {
//        binding.etDate.setText(displayDateFormat.format(calendar.time))
//        binding.etDate.setOnClickListener {
//            showDatePicker()
//        }
//    }
//
//    private fun showDatePicker() {
//        val datePickerDialog = DatePickerDialog(
//            requireContext(),
//            { _, year, month, dayOfMonth ->
//                calendar.set(year, month, dayOfMonth)
//                updateDateDisplay()
//            },
//            calendar.get(Calendar.YEAR),
//            calendar.get(Calendar.MONTH),
//            calendar.get(Calendar.DAY_OF_MONTH)
//        )
//
//        datePickerDialog.show()
//    }
//
//    private fun updateDateDisplay() {
//        binding.etDate.setText(displayDateFormat.format(calendar.time))
//    }
//
//    private fun fetchFeederList() {
//        val token = SessionManager.getToken(requireContext())
//
//        if (token.isEmpty()) {
//            Snackbar.make(binding.root, "Token missing", Snackbar.LENGTH_LONG).show()
//            return
//        }
//
//        Log.d(TAG, "🔄 Fetching feeders...")
//        showLoading(true)
//
//        lifecycleScope.launch {
//            try {
//                val result = withContext(Dispatchers.IO) {
//                    fetchFeedersFromAPI(token)
//                }
//
//                stationName = result.station
//                allFeeders.clear()
//                allFeeders.addAll(result.feeders)
//
//                Log.d(TAG, "✅ Station: $stationName, Feeders: ${allFeeders.size}")
//
//                setupFeederDropdown()
//                Toast.makeText(context, "Loaded ${allFeeders.size} feeders", Toast.LENGTH_SHORT).show()
//
//            } catch (e: Exception) {
//                Log.e(TAG, "❌ Error", e)
//                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
//            } finally {
//                showLoading(false)
//            }
//        }
//    }
//
//    private suspend fun fetchFeedersFromAPI(token: String): FeedersResponse = withContext(Dispatchers.IO) {
//        val url = URL(FEEDER_LIST_URL)
//        val connection = url.openConnection() as HttpURLConnection
//
//        try {
//            connection.apply {
//                requestMethod = "GET"
//                connectTimeout = TIMEOUT
//                readTimeout = TIMEOUT
//                setRequestProperty("Accept", "application/json")
//                setRequestProperty("Authorization", "Bearer $token")
//            }
//
//            val responseCode = connection.responseCode
//
//            if (responseCode == HttpURLConnection.HTTP_OK) {
//                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
//                    it.readText()
//                }
//                parseFeedersResponse(response)
//            } else {
//                throw Exception("Failed: $responseCode")
//            }
//        } finally {
//            connection.disconnect()
//        }
//    }
//
//    private fun parseFeedersResponse(jsonString: String): FeedersResponse {
//        val feeders = mutableListOf<FeederData>()
//        var station = ""
//
//        val jsonObject = JSONObject(jsonString)
//        val success = jsonObject.optBoolean("success", false)
//
//        if (!success) {
//            throw Exception(jsonObject.optString("message", "Failed"))
//        }
//
//        station = jsonObject.optString("station", "")
//        val dataArray = jsonObject.optJSONArray("data")
//
//        if (dataArray != null) {
//            for (i in 0 until dataArray.length()) {
//                val item = dataArray.getJSONObject(i)
//                feeders.add(
//                    FeederData(
//                        feederCode = item.optString("FEEDER_CODE", ""),
//                        feederName = item.optString("FEEDER_NAME", ""),
//                        feederCategory = item.optString("FEEDER_CATEGORY", "")
//                    )
//                )
//            }
//        }
//
//        return FeedersResponse(station, feeders)
//    }
//
//    private fun setupFeederDropdown() {
//        if (allFeeders.isEmpty()) return
//
//        val feederDisplayList = allFeeders.map {
//            "${it.feederName} (${it.feederCode})"
//        }
//
//        val adapter = ArrayAdapter(
//            requireContext(),
//            R.layout.spinner_item_black,
//            feederDisplayList
//        )
//        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_black)
//
//        binding.actvStation.adapter = adapter
//
//        binding.actvStation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
//            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
//                if (position < allFeeders.size) {
//                    selectedFeeder = allFeeders[position]
//                    Log.d(TAG, "✅ Selected: ${selectedFeeder?.feederName}")
//                }
//            }
//
//            override fun onNothingSelected(parent: AdapterView<*>?) {
//                selectedFeeder = null
//            }
//        }
//    }
//
//    private fun setupRecyclerView() {
//        adapter = DailyEditAdapter()
//
//        // ✅ CORRECT ID: rvElogData (not rvDailyData)
//        binding.rvElogData.apply {
//            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
//            this.adapter = this@DailyEditFragment.adapter
//        }
//
//        Log.d(TAG, "✅ RecyclerView setup complete")
//    }
//
//    private fun setupButtons() {
//        binding.btnSearch.setOnClickListener {
//            searchExistingData()
//        }
//
//        binding.btnSubmitChanges.setOnClickListener {
//            saveChanges()
//        }
//    }
//
//    private fun searchExistingData() {
//        val token = SessionManager.getToken(requireContext())
//        val date = dateFormat.format(calendar.time)
//
//        if (token.isEmpty()) {
//            Snackbar.make(binding.root, "Token missing", Snackbar.LENGTH_LONG).show()
//            return
//        }
//
//        if (selectedFeeder == null) {
//            Snackbar.make(binding.root, "Please select a feeder", Snackbar.LENGTH_LONG).show()
//            return
//        }
//
//        Log.d(TAG, "🔍 Searching for: ${selectedFeeder?.feederCode} on $date")
//        showLoading(true)
//
//        lifecycleScope.launch {
//            try {
//                val result = withContext(Dispatchers.IO) {
//                    fetchExistingData(token, selectedFeeder!!.feederCode, date)
//                }
//
//                if (result.success && result.data != null) {
//                    // ✅ SHOW EXISTING DATA IN EDITABLE FORMAT
//                    val column = DailyEditColumn(
//                        feederName = result.data.feederName,
//                        feederCode = result.data.feederCode,
//                        feederCategory = result.data.feederCategory,
//                        totalConsumption = result.data.totalConsumption,
//                        supply3ph = result.data.supply3ph,
//                        supply1ph = result.data.supply1ph,
//                        remark = result.data.remark
//                    )
//
//                    adapter.submitList(listOf(column))
//
//                    Log.d(TAG, "✅ Data loaded for editing:")
//                    Log.d(TAG, "   Feeder: ${column.feederName}")
//                    Log.d(TAG, "   Category: ${column.feederCategory}")
//                    Log.d(TAG, "   Total: ${column.totalConsumption}")
//
//                    Toast.makeText(context, "Data loaded. You can edit now.", Toast.LENGTH_SHORT).show()
//                } else {
//                    adapter.clearData()
//                    Toast.makeText(context, "No data found for this date", Toast.LENGTH_SHORT).show()
//                }
//
//            } catch (e: Exception) {
//                Log.e(TAG, "❌ Search error", e)
//                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
//            } finally {
//                showLoading(false)
//            }
//        }
//    }
//
//    private suspend fun fetchExistingData(
//        token: String,
//        feederCode: String,
//        date: String
//    ): FetchResult = withContext(Dispatchers.IO) {
//        val url = URL(FETCH_URL)
//        val connection = url.openConnection() as HttpURLConnection
//
//        try {
//            connection.apply {
//                requestMethod = "POST"
//                connectTimeout = TIMEOUT
//                readTimeout = TIMEOUT
//                setRequestProperty("Content-Type", "application/json")
//                setRequestProperty("Authorization", "Bearer $token")
//                doOutput = true
//            }
//
//            val requestBody = JSONObject().apply {
//                put("feeder_code", feederCode)
//                put("date", date)
//            }
//
//            Log.d(TAG, "📤 Fetch request: $requestBody")
//
//            OutputStreamWriter(connection.outputStream).use {
//                it.write(requestBody.toString())
//                it.flush()
//            }
//
//            val responseCode = connection.responseCode
//
//            if (responseCode == HttpURLConnection.HTTP_OK) {
//                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
//                    it.readText()
//                }
//
//                Log.d(TAG, "📥 Fetch response: $response")
//
//                val jsonResponse = JSONObject(response)
//                val success = jsonResponse.optBoolean("success", false)
//
//                if (success) {
//                    val data = jsonResponse.optJSONObject("data")
//                    if (data != null) {
//                        FetchResult(
//                            success = true,
//                            data = ExistingDailyData(
//                                feederName = data.optString("FEEDER_NAME", ""),
//                                feederCode = data.optString("FEEDER_CODE", ""),
//                                feederCategory = data.optString("FEEDER_CATEGORY", ""),
//                                totalConsumption = data.optString("TOTAL_CONSUMPTION", ""),
//                                supply3ph = data.optString("SUPPLY_3PH", ""),
//                                supply1ph = data.optString("SUPPLY_1PH", ""),
//                                remark = data.optString("REMARK", "")
//                            )
//                        )
//                    } else {
//                        FetchResult(success = false, data = null)
//                    }
//                } else {
//                    FetchResult(success = false, data = null)
//                }
//            } else {
//                FetchResult(success = false, data = null)
//            }
//        } finally {
//            connection.disconnect()
//        }
//    }
//
//    private fun saveChanges() {
//        val token = SessionManager.getToken(requireContext())
//        val date = dateFormat.format(calendar.time)
//
//        if (token.isEmpty()) {
//            Snackbar.make(binding.root, "Token missing", Snackbar.LENGTH_LONG).show()
//            return
//        }
//
//        val columns = adapter.getColumns()
//        if (columns.isEmpty()) {
//            Snackbar.make(binding.root, "No data to save", Snackbar.LENGTH_SHORT).show()
//            return
//        }
//
//        Log.d(TAG, "💾 Saving changes...")
//        showLoading(true)
//
//        lifecycleScope.launch {
//            try {
//                val result = withContext(Dispatchers.IO) {
//                    saveToAPI(token, columns[0], date)
//                }
//
//                if (result.success) {
//                    Toast.makeText(context, "✅ Changes saved successfully", Toast.LENGTH_SHORT).show()
//
//                    // Clear form after successful save
//                    adapter.clearData()
//
//                } else {
//                    showError("Failed to save: ${result.message}")
//                }
//
//            } catch (e: Exception) {
//                Log.e(TAG, "❌ Save error", e)
//                showError("Error saving: ${e.message}")
//            } finally {
//                showLoading(false)
//            }
//        }
//    }
//
//    private suspend fun saveToAPI(
//        token: String,
//        column: DailyEditColumn,
//        date: String
//    ): SaveResult = withContext(Dispatchers.IO) {
//        val url = URL(SAVE_URL)
//        val connection = url.openConnection() as HttpURLConnection
//
//        try {
//            connection.apply {
//                requestMethod = "POST"
//                connectTimeout = TIMEOUT
//                readTimeout = TIMEOUT
//                setRequestProperty("Content-Type", "application/json")
//                setRequestProperty("Authorization", "Bearer $token")
//                doOutput = true
//            }
//
//            val requestBody = JSONObject().apply {
//                put("feeder_code", column.feederCode)
//                put("date", date)
//                put("total_consumption", column.totalConsumption)
//                put("supply_3ph", column.supply3ph)
//                put("supply_1ph", column.supply1ph)
//                put("remark", column.remark)
//            }
//
//            Log.d(TAG, "📤 Save request: $requestBody")
//
//            OutputStreamWriter(connection.outputStream).use { writer ->
//                writer.write(requestBody.toString())
//                writer.flush()
//            }
//
//            val responseCode = connection.responseCode
//
//            if (responseCode == HttpURLConnection.HTTP_OK) {
//                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
//                    it.readText()
//                }
//
//                Log.d(TAG, "✅ Save response: $response")
//
//                val jsonResponse = JSONObject(response)
//                SaveResult(
//                    jsonResponse.optBoolean("success", false),
//                    jsonResponse.optString("message", "")
//                )
//            } else {
//                SaveResult(false, "HTTP Error: $responseCode")
//            }
//        } finally {
//            connection.disconnect()
//        }
//    }
//
//    private fun showLoading(show: Boolean) {
//        binding.btnSearch.isEnabled = !show
//        binding.btnSubmitChanges.isEnabled = !show
//
//        binding.btnSearch.text = if (show) "Searching..." else "SEARCH"
//        binding.btnSubmitChanges.text = if (show) "Updating..." else "SUBMIT CHANGES"
//    }
//
//    private fun showError(message: String) {
//        AlertDialog.Builder(requireContext())
//            .setTitle("Error")
//            .setMessage(message)
//            .setPositiveButton("OK", null)
//            .show()
//    }
//
//    override fun onDestroyView() {
//        super.onDestroyView()
//        _binding = null
//    }
//}
//
//// ================== DATA CLASSES ==================
//
//
//


data class FetchResult(
    val success: Boolean,
    val data: ExistingDailyData?
)

data class ExistingDailyData(
    val feederName: String,
    val feederCode: String,
    val feederCategory: String,
    val totalConsumption: String,
    val supply3ph: String,
    val supply1ph: String,
    val remark: String
)

data class DailyEditColumn(
    val feederName: String,
    val feederCode: String,
    val feederCategory: String,
    var totalConsumption: String,
    var supply3ph: String,
    var supply1ph: String,
    var remark: String
)



// ================== ADAPTER ==================

class DailyEditAdapter : RecyclerView.Adapter<DailyEditAdapter.ViewHolder>() {

    private val columns = mutableListOf<DailyEditColumn>()

    fun submitList(list: List<DailyEditColumn>) {
        columns.clear()
        columns.addAll(list)
        notifyDataSetChanged()

        Log.d("DailyEditAdapter", "✅ Submitted ${list.size} items for editing")
    }

    fun getColumns(): List<DailyEditColumn> = columns

    fun clearData() {
        columns.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.daily_edit_vertical, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(columns[position])
    }

    override fun getItemCount(): Int = columns.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvFeeder: TextView = itemView.findViewById(R.id.tvFeeder)
        private val tvCode: TextView = itemView.findViewById(R.id.tvCode)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
        private val etRemark: TextInputEditText = itemView.findViewById(R.id.etRemark)
        private val etTotal: TextInputEditText = itemView.findViewById(R.id.etTotal)
        private val spinnerSupply3ph: Spinner = itemView.findViewById(R.id.spinnerSupply3ph)
        private val spinnerSupply1ph: Spinner = itemView.findViewById(R.id.spinnerSupply1ph)

        private lateinit var currentColumn: DailyEditColumn

        fun bind(column: DailyEditColumn) {
            currentColumn = column

            Log.d("DailyEditAdapter", "🔍 Binding - Feeder: ${column.feederName}, Category: '${column.feederCategory}'")

            // ✅ Display non-editable fields
            tvFeeder.text = column.feederName
            tvCode.text = column.feederCode
            tvCategory.text = column.feederCategory

            // ✅ Clear previous listeners to avoid duplication
            etRemark.removeTextChangedListeners()
            etTotal.removeTextChangedListeners()

            // ✅ Set editable field values
            etRemark.setText(column.remark)
            etTotal.setText(column.totalConsumption)

            // ✅ Setup time spinners
            setupTimeSpinners()

            // ✅ Set spinner selections
            setSpinnerSelection(spinnerSupply3ph, column.supply3ph)
            setSpinnerSelection(spinnerSupply1ph, column.supply1ph)

            // ✅ Add text watchers for two-way binding
            setupTextWatcher(etRemark) { currentColumn.remark = it }
            setupTextWatcher(etTotal) { currentColumn.totalConsumption = it }

            // ✅ Spinner listeners for two-way binding
            setupSpinnerListener(spinnerSupply3ph) { currentColumn.supply3ph = it }
            setupSpinnerListener(spinnerSupply1ph) { currentColumn.supply1ph = it }

            Log.d("DailyEditAdapter", "   ✅ Bound successfully")
        }

        private fun TextInputEditText.removeTextChangedListeners() {
            // Remove existing TextWatcher if present
            val existingWatcher = this.tag as? TextWatcher
            if (existingWatcher != null) {
                this.removeTextChangedListener(existingWatcher)
                this.tag = null
            }
        }

        private fun setupTimeSpinners() {
            val timeOptions = mutableListOf<String>()
            timeOptions.add("--Select--")

            for (hour in 0..23) {
                for (minute in 0..59) {
                    timeOptions.add(String.format("%02d:%02d", hour, minute))
                }
            }

            val adapter3ph = ArrayAdapter(
                itemView.context,
                android.R.layout.simple_spinner_item,
                timeOptions
            )
            adapter3ph.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerSupply3ph.adapter = adapter3ph

            val adapter1ph = ArrayAdapter(
                itemView.context,
                android.R.layout.simple_spinner_item,
                timeOptions
            )
            adapter1ph.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerSupply1ph.adapter = adapter1ph
        }

        private fun setSpinnerSelection(spinner: Spinner, value: String?) {
            if (value.isNullOrEmpty()) {
                spinner.setSelection(0)
                return
            }

            val adapter = spinner.adapter
            for (i in 0 until adapter.count) {
                if (adapter.getItem(i).toString() == value) {
                    spinner.setSelection(i)
                    return
                }
            }
            spinner.setSelection(0)
        }

        private fun setupSpinnerListener(spinner: Spinner, onSelected: (String) -> Unit) {
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    (view as? TextView)?.setTextColor(android.graphics.Color.BLACK)

                    if (position > 0) {
                        val selectedValue = parent?.getItemAtPosition(position).toString()
                        onSelected(selectedValue)
                        Log.d("DailyEditAdapter", "   Spinner selected: $selectedValue")
                    } else {
                        onSelected("")
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        private fun setupTextWatcher(editText: TextInputEditText, onTextChanged: (String) -> Unit) {
            val watcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val newValue = s.toString()
                    onTextChanged(newValue)
                    Log.d("DailyEditAdapter", "   Text changed: $newValue")
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }

            editText.addTextChangedListener(watcher)
            editText.tag = watcher
        }
    }
}