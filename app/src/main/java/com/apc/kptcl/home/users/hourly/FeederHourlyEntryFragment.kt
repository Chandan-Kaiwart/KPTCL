package com.apc.kptcl.home.users.hourly

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentFeederHourlyEntryBinding
import com.apc.kptcl.utils.SessionManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import androidx.navigation.fragment.findNavController

class FeederHourlyEntryFragment : Fragment() {

    private var _binding: FragmentFeederHourlyEntryBinding? = null
    private val binding get() = _binding!!

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private lateinit var adapter: HourlyDataAdapter
    private lateinit var layoutManager: LinearLayoutManager

    private var stationName: String = ""
    private val allFeeders = mutableListOf<FeederData>()
    private var selectedFeeder: FeederData? = null

    private val parameters = listOf("IB", "IR", "IY", "MW", "MVAR")

    companion object {
        private const val TAG = "HourlyEntry"
        private const val FEEDER_LIST_URL = "http://62.72.59.119:9000/api/feeder/list"
        private const val FETCH_URL = "http://62.72.59.119:9000/api/feeder/hourly-entry/fetch"
        private const val SAVE_URL = "http://62.72.59.119:9000/api/feeder/hourly-entry/save"
        private const val TIMEOUT = 15000
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeederHourlyEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!SessionManager.isLoggedIn(requireContext())) {
            Snackbar.make(binding.root, "Please login first", Snackbar.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "✅ User logged in")

        setupDatePicker()
        setupRecyclerView()
        setupButtons()
        updateDateDisplay()
        fetchFeederList()
    }

    private fun setupDatePicker() {
        binding.etDate.setText(dateFormat.format(calendar.time))
        binding.etDate.setOnClickListener {
            showDatePicker()
        }
    }

    private fun showDatePicker() {
        val datePickerDialog = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                binding.etDate.setText(dateFormat.format(calendar.time))
                updateDateDisplay()

                // Reload data when date changes
                if (selectedFeeder != null) {
                    loadFeederData()
                }
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        // Only allow yesterday and before
        val yesterdayCalendar = Calendar.getInstance()
        yesterdayCalendar.add(Calendar.DAY_OF_YEAR, -1)
        datePickerDialog.datePicker.maxDate = yesterdayCalendar.timeInMillis

        datePickerDialog.show()
    }

    private fun updateDateDisplay() {
        val selectedDate = dateFormat.format(calendar.time)
        binding.etDate.setText(selectedDate)
        binding.tvEntryDate.text = "FOR $selectedDate"
    }

    private fun fetchFeederList() {
        val token = SessionManager.getToken(requireContext())

        if (token.isEmpty()) {
            Snackbar.make(binding.root, "Token missing", Snackbar.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "📄 Fetching feeders...")
        showLoading(true)

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    fetchFeedersFromAPI(token)
                }

                stationName = result.station
                allFeeders.clear()
                allFeeders.addAll(result.feeders)

                Log.d(TAG, "✅ Station: $stationName, Feeders: ${allFeeders.size}")

                setupFeederDropdown()
                Toast.makeText(context, "Loaded ${allFeeders.size} feeders", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error", e)
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun fetchFeedersFromAPI(token: String): FeedersResponse = withContext(Dispatchers.IO) {
        val url = URL(FEEDER_LIST_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }
                parseFeedersResponse(response)
            } else {
                throw Exception("Failed: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseFeedersResponse(jsonString: String): FeedersResponse {
        val feeders = mutableListOf<FeederData>()
        var station = ""

        val jsonObject = JSONObject(jsonString)
        val success = jsonObject.optBoolean("success", false)

        if (!success) {
            throw Exception(jsonObject.optString("message", "Failed"))
        }

        station = jsonObject.optString("station", "")
        val dataArray = jsonObject.optJSONArray("data")

        if (dataArray != null) {
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                feeders.add(
                    FeederData(
                        feederCode = item.optString("FEEDER_CODE", ""),
                        feederName = item.optString("FEEDER_NAME", ""),
                        feederCategory = item.optString("FEEDER_CATEGORY", "")
                    )
                )
            }
        }

        return FeedersResponse(station, feeders)
    }

    private fun setupFeederDropdown() {
        if (allFeeders.isEmpty()) return

        val feederDisplayList = allFeeders.map { it.feederName }

        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item_black,
            feederDisplayList
        )
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_black)

        binding.actvFeeder.adapter = adapter

        // ✅ FIX: By default first feeder ko select karo
        if (allFeeders.isNotEmpty()) {
            selectedFeeder = allFeeders[0]
            binding.actvFeeder.setSelection(0)  // ← setText() ki jagah setSelection() use karo
            Log.d(TAG, "✅ Default selected: ${selectedFeeder?.feederName} (${selectedFeeder?.feederCode})")

            // ✅ Load data for first feeder automatically
            loadFeederData()
        }

        binding.actvFeeder.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < allFeeders.size) {
                    selectedFeeder = allFeeders[position]
                    Log.d(TAG, "🔹 Selected: ${selectedFeeder?.feederName}")
                    loadFeederData()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedFeeder = null
                adapter.clear()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = HourlyDataAdapter()
        layoutManager = LinearLayoutManager(requireContext())

        binding.rvFeederData.layoutManager = layoutManager
        binding.rvFeederData.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnSubmit.setOnClickListener {
            submitData()
        }
    }

    private fun loadFeederData() {
        val feeder = selectedFeeder ?: return
        val date = binding.etDate.text.toString()

        if (date.isEmpty()) {
            Toast.makeText(context, "Please select a date", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "📄 Loading data for ${feeder.feederName} on $date")

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    fetchFeederData(date, feeder.feederCode)
                }

                Log.d(TAG, "✅ Received ${result.size} rows")

                val rowList = mutableListOf<HourlyDataRow>()

                if (result.isNotEmpty()) {
                    // Edit mode - parse existing data
                    val hourMap = mutableMapOf<String, MutableMap<String, String>>()

                    result.forEach { item ->
                        val parameter = item.parameter
                        item.hours.forEach { (hour, value) ->
                            if (!hourMap.containsKey(hour)) {
                                hourMap[hour] = mutableMapOf()
                            }
                            hourMap[hour]!![parameter] = value
                        }
                    }

                    // Create rows for all 24 hours (00-23)
                    for (hour in 0..23) {
                        val hourKey = String.format("%02d", hour)
                        rowList.add(
                            HourlyDataRow(
                                hour = hourKey,
                                parameters = hourMap[hourKey] ?: mutableMapOf()
                            )
                        )
                    }
                } else {
                    // New mode - empty rows
                    for (hour in 0..23) {
                        rowList.add(
                            HourlyDataRow(
                                hour = String.format("%02d", hour),
                                parameters = mutableMapOf()
                            )
                        )
                    }
                }

                adapter.submitList(rowList, feeder.feederName, feeder.feederCode)
                Toast.makeText(context, "Data loaded", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading data", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun fetchFeederData(date: String, feederCode: String): List<ExistingHourlyData> = withContext(Dispatchers.IO) {
        val token = SessionManager.getToken(requireContext())
        val url = URL(FETCH_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                doOutput = true
            }

            val requestBody = JSONObject().apply {
                put("date", date)
                put("feeder_code", feederCode)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }

                val jsonResponse = JSONObject(response)
                val dataArray = jsonResponse.optJSONArray("data") ?: JSONArray()

                val result = mutableListOf<ExistingHourlyData>()

                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    val parameter = item.optString("PARAMETER", "")

                    val hoursMap = mutableMapOf<String, String>()
                    val keys = item.keys()

                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key.matches(Regex("\\d{2}"))) {
                            hoursMap[key] = item.optString(key, "")
                        }
                    }

                    result.add(ExistingHourlyData(parameter, hoursMap))
                }

                result
            } else {
                emptyList()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun submitData() {
        val feeder = selectedFeeder
        if (feeder == null) {
            Toast.makeText(context, "Please select a feeder", Toast.LENGTH_SHORT).show()
            return
        }

        val date = binding.etDate.text.toString()
        if (date.isEmpty()) {
            Toast.makeText(context, "Please select a date", Toast.LENGTH_SHORT).show()
            return
        }

        val rows = adapter.getHourlyData()

        Log.d(TAG, "💾 Submitting ${rows.size} rows for ${feeder.feederName}")

        lifecycleScope.launch {
            try {
                showLoading(true)

                val result = withContext(Dispatchers.IO) {
                    saveData(date, feeder.feederCode, rows)
                }

                if (result.success) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("✅ Success")
                        .setMessage(result.message)
                        .setPositiveButton("OK") { _, _ ->
                            findNavController().navigateUp()
                        }
                        .show()
                } else {
                    showError(result.message)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Submit error", e)
                showError("Error: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun saveData(date: String, feederCode: String, rows: List<HourlyDataRow>): SaveResult = withContext(Dispatchers.IO) {
        val token = SessionManager.getToken(requireContext())
        val url = URL(SAVE_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                doOutput = true
            }

            val rowsArray = JSONArray()

            for (parameter in parameters) {
                val hoursObject = JSONObject()

                for (row in rows) {
                    val value = row.parameters[parameter] ?: ""
                    // ✅ Skip empty values AND "null" strings
                    if (value.isNotEmpty() && value != "null") {
                        hoursObject.put(row.hour, value)
                    }
                }

                rowsArray.put(JSONObject().apply {
                    put("date", date)
                    put("feeder_code", feederCode)
                    put("parameter", parameter)
                    put("hours", hoursObject)
                })
            }

            val requestBody = JSONObject().put("rows", rowsArray)

            Log.d(TAG, "📤 Request:\n${requestBody.toString(2)}")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }

                Log.d(TAG, "✅ Response: $response")

                val jsonResponse = JSONObject(response)
                SaveResult(
                    jsonResponse.optBoolean("success", false),
                    jsonResponse.optString("message", "")
                )
            } else {
                SaveResult(false, "HTTP Error: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun showLoading(show: Boolean) {
        binding.btnSubmit.isEnabled = !show
        binding.btnSubmit.text = if (show) "Submitting..." else "SUBMIT"
    }

    private fun showError(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

data class FeedersResponse(
    val station: String,
    val feeders: List<FeederData>
)

data class FeederData(
    val feederCode: String,
    val feederName: String,
    val feederCategory: String
)

data class HourlyDataRow(
    val hour: String,  // "00" to "23" - database format
    val parameters: MutableMap<String, String>
)

// ============================================
// MODIFIED HourlyDataAdapter - INTEGER ONLY
// ============================================

class HourlyDataAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_DATA = 1
    }

    private val rows = mutableListOf<HourlyDataRow>()
    private var feederName: String = ""
    private var feederCode: String = ""

    fun submitList(list: List<HourlyDataRow>, name: String, code: String) {
        rows.clear()
        rows.addAll(list)
        feederName = name
        feederCode = code
        notifyDataSetChanged()
    }

    fun getHourlyData(): List<HourlyDataRow> = rows

    fun clearData() {
        rows.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) VIEW_TYPE_HEADER else VIEW_TYPE_DATA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_feeder_hourly_header, parent, false)
                HeaderViewHolder(view)
            }
            VIEW_TYPE_DATA -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_feeder_hourly_row, parent, false)
                DataViewHolder(view)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> {
                // Header row - no binding needed, static content
            }
            is DataViewHolder -> {
                val actualPosition = position - 1
                if (actualPosition >= 0 && actualPosition < rows.size) {
                    holder.bind(rows[actualPosition])
                }
            }
        }
    }

    override fun getItemCount(): Int = rows.size + 1

    // Header ViewHolder
    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    // Data ViewHolder
    class DataViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvHour: TextView = itemView.findViewById(R.id.tvHour)
        private val etIB: EditText = itemView.findViewById(R.id.etIB)
        private val etIR: EditText = itemView.findViewById(R.id.etIR)
        private val etIY: EditText = itemView.findViewById(R.id.etIY)
        private val etMW: EditText = itemView.findViewById(R.id.etMW)
        private val etMVAR: EditText = itemView.findViewById(R.id.etMVAR)

        private lateinit var currentRow: HourlyDataRow

        fun bind(row: HourlyDataRow) {
            currentRow = row

            val dbHourInt = row.hour.toInt()
            val displayHour = dbHourInt + 1
            tvHour.text = "$displayHour:00"

            setupParameterInput(etIB, "IB")
            setupParameterInput(etIR, "IR")
            setupParameterInput(etIY, "IY")
            setupParameterInput(etMW, "MW")
            setupParameterInput(etMVAR, "MVAR")
        }


        private fun setupParameterInput(editText: EditText, parameterName: String) {
            editText.tag?.let { oldWatcher ->
                if (oldWatcher is TextWatcher) {
                    editText.removeTextChangedListener(oldWatcher)
                }
            }

            // ✅ UPDATED: Different input types for different parameters
            when (parameterName) {
                "IB", "IR", "IY" -> {
                    // Integer only, no decimals, no negative
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    editText.filters = arrayOf(IntegerInputFilter(allowNegative = false))
                    editText.hint = "Integer only"
                }
                "MW" -> {
                    // Decimal allowed (8 places), no negative
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    editText.filters = arrayOf(DecimalInputFilter(allowNegative = false, maxDecimalPlaces = 8))
                    editText.hint = "e.g., 12.12345678"
                }
                "MVAR" -> {
                    // Decimal allowed (8 places), negative allowed
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    editText.filters = arrayOf(DecimalInputFilter(allowNegative = true, maxDecimalPlaces = 8))
                    editText.hint = "Can be ± decimal"
                }
            }

            editText.setText(currentRow.parameters[parameterName] ?: "")

            val watcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    currentRow.parameters[parameterName] = s.toString()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }

            editText.addTextChangedListener(watcher)
            editText.tag = watcher
        }
    }
}