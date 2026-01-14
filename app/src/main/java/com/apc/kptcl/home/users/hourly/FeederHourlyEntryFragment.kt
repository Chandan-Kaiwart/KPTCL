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

    private var stationName: String = ""
    private val allFeeders = mutableListOf<FeederData>()
    private var selectedFeeder: FeederData? = null

    private val parameters = listOf("IB", "IR", "IY", "MW", "MVAR")
    private val numberOfHours = 24

    companion object {
        private const val TAG = "HourlyEntry"
        private const val FEEDER_LIST_URL = "http://62.72.59.119:5000/api/feeder/list"
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
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        // ✅ FUTURE DATES DISABLE - Present date se aage select nahi hoga
        datePickerDialog.datePicker.maxDate = System.currentTimeMillis()

        datePickerDialog.show()
    }

    private fun updateDateDisplay() {
        binding.etDate.setText(dateFormat.format(calendar.time))
        binding.tvEntryDate.text = "FOR ${dateFormat.format(calendar.time)}"
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
        adapter = HourlyDataAdapter()
        binding.rvFeederData.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@FeederHourlyEntryFragment.adapter
        }
    }

    private fun loadFeederData() {
        if (selectedFeeder == null) return

        val feeder = selectedFeeder!!

        binding.llFeederInfo.visibility = View.VISIBLE
        binding.tvFeederInfo.text =
            "DATE: ${dateFormat.format(calendar.time)} | " +
                    "STATION: $stationName | " +
                    "FEEDER: ${feeder.feederName} | " +
                    "CODE: ${feeder.feederCode}"

        val rows = mutableListOf<HourlyDataRow>()

        // ✅ CHANGED: Hour format from "00:00-01:00" to just "00"
        for (hour in 0 until numberOfHours) {
            val hourStr = String.format("%02d", hour) // Just "00", "01", "02", etc.
            val parameters = mutableMapOf<String, String>()

            this.parameters.forEach { param ->
                parameters[param] = ""
            }

            rows.add(HourlyDataRow(hourStr, parameters))
        }

        adapter.submitList(rows, feeder.feederName, feeder.feederCode)

        Log.d(TAG, "✅ Loaded data template for ${feeder.feederName}")
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

        // ✅ VALIDATION: Check if any data is entered
        val hasAnyData = rows.any { row ->
            row.parameters.values.any { it.isNotEmpty() }
        }

        if (!hasAnyData) {
            Toast.makeText(context, "Please enter at least one value before submitting", Toast.LENGTH_SHORT).show()
            return
        }

        // Show confirmation dialog
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
                            // Clear form
                            selectedFeeder = null
                            adapter.clearData()
                            binding.llFeederInfo.visibility = View.GONE

                            // ✅ NAVIGATE TO HOMEPAGE
                            findNavController().popBackStack()
                        }
                        .setCancelable(false)
                        .show()
                } else {
                    showError("Submit failed: ${result.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Submit error", e)
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
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                doOutput = true
                doInput = true
            }

            // ✅ Build rows array matching API expectations
            val rowsArray = JSONArray()

            parameters.forEach { param ->
                val rowObject = JSONObject().apply {
                    put("date", date)
                    put("feeder_code", feederCode)
                    put("feeder_name", selectedFeeder?.feederName ?: "")
                    put("feeder_category", selectedFeeder?.feederCategory ?: "")
                    put("parameter", param)

                    // ✅ Create hours object
                    val hoursObject = JSONObject()

                    rows.forEach { row ->
                        val hourKey = row.hour
                        val value = row.parameters[param]
                        if (!value.isNullOrEmpty()) {
                            hoursObject.put(hourKey, value.toDoubleOrNull() ?: 0.0)
                        }
                    }

                    // ✅ Add hours object to row
                    put("hours", hoursObject)
                }
                rowsArray.put(rowObject)
            }

            // ✅ Use "rows" key
            val requestBody = JSONObject().apply {
                put("rows", rowsArray)
            }

            Log.d(TAG, "📤 Request:\n${requestBody.toString(2)}")

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }

                Log.d(TAG, "✅ Response: $response")

                val jsonResponse = JSONObject(response)
                SaveResult(
                    jsonResponse.optBoolean("success", false),
                    jsonResponse.optString("message", "")
                )
            } else {
                val errorStream = connection.errorStream
                val errorMessage = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
                } else {
                    "HTTP Error: $responseCode"
                }

                Log.e(TAG, "❌ Error Response: $errorMessage")
                SaveResult(false, errorMessage)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during save", e)
            SaveResult(false, e.message ?: "Unknown error")
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

    // ✅ REPLACE THE ViewHolder CLASS (lines 525-581) WITH THIS:

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

            tvHour.text = row.hour

            setupParameterInput(etIB, "IB")
            setupParameterInput(etIR, "IR")
            setupParameterInput(etIY, "IY")
            setupParameterInput(etMW, "MW")
            setupParameterInput(etMVAR, "MVAR")
        }

        private fun setupParameterInput(editText: EditText, parameterName: String) {
            // Remove old watcher
            editText.tag?.let { oldWatcher ->
                if (oldWatcher is TextWatcher) {
                    editText.removeTextChangedListener(oldWatcher)
                }
            }

            // ✅ SET PROPER INPUT TYPE AND FILTER
            if (parameterName == "MVAR") {
                // ✅ USE TEXT INPUT - Shows full keyboard with minus!
                editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
                editText.filters = arrayOf(DecimalInputFilter(allowNegative = true))
                editText.hint = "Can be ±"
            } else {
                // Only positive for IB, IR, IY, MW
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                editText.filters = arrayOf(DecimalInputFilter(allowNegative = false))
            }

            // Set current value
            editText.setText(currentRow.parameters[parameterName] ?: "")

            // Add new watcher
            val watcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    currentRow.parameters[parameterName] = s.toString()
                    Log.d("HourlyEntry", "Hour ${currentRow.hour} - $parameterName = '${s.toString()}'")
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }

            editText.addTextChangedListener(watcher)
            editText.tag = watcher
        }
    }
// ✅ REPLACE THE DecimalInputFilter CLASS (lines 584-611) WITH THIS:
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

            // Allow empty
            if (result.isEmpty()) return null

            // ✅ Allow just minus sign at start (for MVAR)
            if (allowNegative && result == "-") {
                return null
            }

            // ❌ Block minus sign for non-negative parameters (IB, IR, IY, MW)
            if (!allowNegative && result.contains("-")) {
                return ""
            }

            // ✅ Allow partial decimal inputs like: "1", "12", "12.", "12.5", "-1", "-12", "-12.", "-12.5"
            // Pattern: optional minus, optional digits, optional dot, optional digits
            if (result.matches(Regex("^-?\\d*\\.?\\d*$"))) {
                return null // Accept
            }

            // Reject anything else
            return ""
        }
    }
}

// ✅ Input filter for decimal numbers with optional negative sign
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

        // Allow negative sign only at the beginning for MVAR
        if (allowNegative && result == "-") return null

        // Validate decimal number
        return try {
            result.toDouble()
            null // Accept input
        } catch (e: NumberFormatException) {
            "" // Reject input
        }
    }
}