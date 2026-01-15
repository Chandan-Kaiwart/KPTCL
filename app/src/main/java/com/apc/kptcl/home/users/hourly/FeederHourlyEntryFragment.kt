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
    private val numberOfHours = 24

    companion object {
        private const val TAG = "HourlyEntry"
        private const val FEEDER_LIST_URL = "http://62.72.59.119:5000/api/feeder/list"
        private const val FETCH_URL = "http://62.72.59.119:4004/api/feeder/hourly-entry/fetch"
        private const val SAVE_URL = "http://62.72.59.119:4004/api/feeder/hourly-entry/save"
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
        binding.etDate.setText(dateFormat.format(calendar.time))
    }

    private fun fetchFeederList() {
        val token = SessionManager.getToken(requireContext())

        if (token.isEmpty()) {
            Snackbar.make(binding.root, "Token missing", Snackbar.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "🔄 Fetching feeders...")
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

        binding.actvFeeder.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < allFeeders.size) {
                    selectedFeeder = allFeeders[position]
                    Log.d(TAG, "✅ Selected: ${selectedFeeder?.feederName}")
                    loadFeederData()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                Log.d(TAG, "No feeder selected")
            }
        }
    }

    private fun setupRecyclerView() {
        layoutManager = LinearLayoutManager(context)
        adapter = HourlyDataAdapter()

        binding.rvFeederData.apply {
            this.layoutManager = this@FeederHourlyEntryFragment.layoutManager
            this.adapter = this@FeederHourlyEntryFragment.adapter
        }
    }

    /**
     * ✅ Load feeder data and auto-scroll to next empty hour
     */
    private fun loadFeederData() {
        if (selectedFeeder == null) return

        val feeder = selectedFeeder!!

        binding.llFeederInfo.visibility = View.VISIBLE
        binding.tvFeederInfo.text =
            "DATE: ${dateFormat.format(calendar.time)} | " +
                    "STATION: $stationName | " +
                    "FEEDER: ${feeder.feederName} | " +
                    "CODE: ${feeder.feederCode}"

        showLoading(true)

        lifecycleScope.launch {
            try {
                // ✅ Fetch existing data from API
                val existingData = withContext(Dispatchers.IO) {
                    fetchExistingData(feeder.feederCode, dateFormat.format(calendar.time))
                }

                // Build rows
                val rows = mutableListOf<HourlyDataRow>()

                for (hour in 1 until numberOfHours) {
                    val hourStr = String.format("%02d", hour)
                    val parameters = mutableMapOf<String, String>()

                    // Fill with existing data if available
                    this@FeederHourlyEntryFragment.parameters.forEach { param ->
                        val existingValue = existingData[hourStr]?.get(param) ?: ""
                        parameters[param] = existingValue
                    }

                    rows.add(HourlyDataRow(hourStr, parameters))
                }

                adapter.submitList(rows, feeder.feederName, feeder.feederCode)

                // ✅ Auto-scroll to next empty hour
                scrollToNextEmptyHour(rows)

                Log.d(TAG, "✅ Loaded data for ${feeder.feederName}")

            } catch (e: Exception) {
                Log.e(TAG, "Error loading data", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                showLoading(false)
            }
        }
    }

    /**
     * ✅ Fetch existing data from backend
     */
    private suspend fun fetchExistingData(feederCode: String, date: String): Map<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val url = URL(FETCH_URL)
        val connection = url.openConnection() as HttpURLConnection

        val resultMap = mutableMapOf<String, MutableMap<String, String>>()

        try {
            val token = SessionManager.getToken(requireContext())

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

            OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val jsonResponse = JSONObject(response)

                if (jsonResponse.optBoolean("success", false)) {
                    val dataArray = jsonResponse.optJSONArray("data")

                    if (dataArray != null) {
                        for (i in 0 until dataArray.length()) {
                            val row = dataArray.getJSONObject(i)
                            val parameter = row.optString("PARAMETER", "")

                            // Extract hour columns (00, 01, 02, ... 23)
                            for (hour in 1 until numberOfHours) {
                                val hourStr = String.format("%02d", hour)
                                val value = row.optString(hourStr, "")

                                if (value.isNotEmpty()) {
                                    if (!resultMap.containsKey(hourStr)) {
                                        resultMap[hourStr] = mutableMapOf()
                                    }
                                    resultMap[hourStr]!![parameter] = value
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching existing data", e)
        } finally {
            connection.disconnect()
        }

        return@withContext resultMap
    }

    /**
     * ✅ AUTO-SCROLL TO NEXT EMPTY HOUR
     */
    private fun scrollToNextEmptyHour(rows: List<HourlyDataRow>) {
        // Find first hour that doesn't have all parameters filled
        val firstEmptyIndex = rows.indexOfFirst { row ->
            row.parameters.values.any { it.isEmpty() }
        }

        if (firstEmptyIndex != -1) {
            // ✅ Scroll to position with smooth scrolling
            binding.rvFeederData.postDelayed({
                layoutManager.scrollToPositionWithOffset(firstEmptyIndex, 0)

                Log.d(TAG, "✅ Auto-scrolled to hour: ${rows[firstEmptyIndex].hour}")

                Toast.makeText(
                    context,
                    "📍 Next empty hour: ${rows[firstEmptyIndex].hour}:00",
                    Toast.LENGTH_SHORT
                ).show()
            }, 300) // Small delay to ensure RecyclerView is ready
        } else {
            // All hours filled
            Toast.makeText(context, "✓ All hours already filled!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupButtons() {
        binding.btnSubmit.setOnClickListener {
            submitData()
        }
    }

    private fun submitData() {
        if (selectedFeeder == null) {
            Toast.makeText(context, "Please select a feeder first", Toast.LENGTH_SHORT).show()
            return
        }

        val rows = adapter.getHourlyData()
        if (rows.isEmpty()) {
            Toast.makeText(context, "No data to submit", Toast.LENGTH_SHORT).show()
            return
        }

        val hasAnyData = rows.any { row ->
            row.parameters.values.any { it.isNotEmpty() }
        }

        if (!hasAnyData) {
            Toast.makeText(context, "Please enter at least one value before submitting", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Confirm Submit")
            .setMessage("Submit hourly data for ${selectedFeeder?.feederName}?")
            .setPositiveButton("Submit") { _, _ ->
                performSubmit(rows)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performSubmit(rows: List<HourlyDataRow>) {
        val selectedDate = dateFormat.format(calendar.time)
        val token = SessionManager.getToken(requireContext())

        Log.d(TAG, "🚀 Submitting data...")
        showLoading(true)

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    submitToAPI(token, selectedDate, selectedFeeder!!.feederCode, rows)
                }

                if (result.success) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Success")
                        .setMessage("✓ Hourly data submitted!\n\nFeeder: ${selectedFeeder?.feederName}\nDate: $selectedDate")
                        .setPositiveButton("OK") { _, _ ->
                            selectedFeeder = null
                            adapter.clearData()
                            binding.llFeederInfo.visibility = View.GONE
                            findNavController().popBackStack()
                        }
                        .setCancelable(false)
                        .show()
                } else {
                    showError("Submit failed: ${result.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Submit error", e)
                showError("Error: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun submitToAPI(
        token: String,
        date: String,
        feederCode: String,
        rows: List<HourlyDataRow>
    ): SaveResult = withContext(Dispatchers.IO) {
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

                rows.forEach { row ->
                    val value = row.parameters[parameter] ?: ""
                    if (value.isNotEmpty()) {
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
    val hour: String,
    val parameters: MutableMap<String, String>
)

data class SaveResult(
    val success: Boolean,
    val message: String
)

class HourlyDataAdapter : RecyclerView.Adapter<HourlyDataAdapter.ViewHolder>() {

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_feeder_hourly_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount(): Int = rows.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvHour: TextView = itemView.findViewById(R.id.tvHour)
        private val etIB: EditText = itemView.findViewById(R.id.etIB)
        private val etIR: EditText = itemView.findViewById(R.id.etIR)
        private val etIY: EditText = itemView.findViewById(R.id.etIY)
        private val etMW: EditText = itemView.findViewById(R.id.etMW)
        private val etMVAR: EditText = itemView.findViewById(R.id.etMVAR)

        private lateinit var currentRow: HourlyDataRow

        fun bind(row: HourlyDataRow) {
            currentRow = row

            tvHour.text = "${row.hour}:00"

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

            if (parameterName == "MVAR") {
                editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
                editText.filters = arrayOf(DecimalInputFilter(allowNegative = true))
                editText.hint = "Can be ±"
            } else {
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                editText.filters = arrayOf(DecimalInputFilter(allowNegative = false))
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

class DecimalInputFilter(private val allowNegative: Boolean) : InputFilter {
    override fun filter(
        source: CharSequence?,
        start: Int,
        end: Int,
        dest: Spanned?,
        dstart: Int,
        dend: Int
    ): CharSequence? {
        val builder = StringBuilder(dest ?: "")
        builder.replace(dstart, dend, source?.subSequence(start, end).toString())
        val result = builder.toString()

        if (result.isEmpty()) return null

        if (allowNegative && result == "-") return null

        if (!allowNegative && result.contains("-")) return ""

        if (result.matches(Regex("^-?\\d*\\.?\\d*$"))) {
            return null
        }

        return ""
    }
}