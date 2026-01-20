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
import com.apc.kptcl.databinding.FragmentConsumptionEntryBinding
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
class ConsumptionEntryFragment : Fragment() {

    private var _binding: FragmentConsumptionEntryBinding? = null
    private val binding get() = _binding!!

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private lateinit var adapter: EntryConsumptionDataAdapter

    // Station name will be fetched from API
    private var stationName: String = ""

    // Store ALL feeders from API
    private val allFeeders = mutableListOf<FeederData>()

    // Store selected feeder data
    private var selectedFeederData: EntryConsumptionData? = null

    companion object {
        private const val TAG = "ConsumptionEntry"
        private const val FEEDER_LIST_URL = "http://62.72.59.119:7000/api/feeder/list"
        private const val SAVE_URL = "http://62.72.59.119:7000/api/feeder/consumption/save"
        private const val TIMEOUT = 15000
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConsumptionEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check if user is logged in
        if (!SessionManager.isLoggedIn(requireContext())) {
            Snackbar.make(binding.root, "Please login first", Snackbar.LENGTH_LONG).show()
            Log.e(TAG, "❌ User not logged in")
            return
        }

        Log.d(TAG, "✅ User is logged in")
        Log.d(TAG, "🔑 Token: ${SessionManager.getToken(requireContext()).take(30)}...")

        setupDatePicker()
        setupRecyclerView()
        setupButtons()

        // Fetch feeder list - station name will come from API response
        Log.d(TAG, "🚀 Starting feeder list fetch...")
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
                updateTableTitle()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        // ✅ FUTURE DATES DISABLE - Present date se aage select nahi hoga
        datePickerDialog.datePicker.maxDate = System.currentTimeMillis()

        datePickerDialog.show()
    }
    private fun updateTableTitle() {
        val formattedDate = dateFormat.format(calendar.time)
        // ✅ Show station name in title only if it's available
        val titleText = if (stationName.isNotEmpty()) {
            "FEEDER CONSUMPTION DATA ENTRY FOR $formattedDate - $stationName"
        } else {
            "FEEDER CONSUMPTION DATA ENTRY FOR $formattedDate"
        }
        binding.tvTableTitle.text = titleText
    }

    private fun setupRecyclerView() {
        adapter = EntryConsumptionDataAdapter { data ->
            // Callback when data changes
            selectedFeederData = data
        }
        binding.rvConsumptionData.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@ConsumptionEntryFragment.adapter
        }
        Log.d(TAG, "✅ RecyclerView setup complete")
    }

    private fun setupButtons() {
        binding.btnSubmit.setOnClickListener {
            validateAndSubmitData()
        }

        binding.btnEdit.setOnClickListener {
            // Refresh feeder list
            fetchFeederList()
            Toast.makeText(context, "Refreshing feeder list...", Toast.LENGTH_SHORT).show()
        }

        binding.btnView.setOnClickListener {
            showDataSummary()
        }
    }

    /**
     * Fetch feeder list from API and populate spinner
     * Also extracts station name from API response
     */
    private fun fetchFeederList() {
        val token = SessionManager.getToken(requireContext())

        if (token.isEmpty()) {
            Snackbar.make(binding.root, "Authentication token missing", Snackbar.LENGTH_LONG).show()
            Log.e(TAG, "❌ TOKEN IS EMPTY")
            return
        }

        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "🔄 FETCH FEEDER LIST STARTED")
        Log.d(TAG, "═══════════════════════════════════════")

        showLoading(true, "Loading feeders...")

        lifecycleScope.launch {
            try {
                Log.d(TAG, "📡 Calling API...")

                val result = withContext(Dispatchers.IO) {
                    fetchFeedersFromAPI(token)
                }

                // Extract station name and feeders from result
                stationName = result.station
                val feeders = result.feeders

                Log.d(TAG, "✅ API SUCCESS!")
                Log.d(TAG, "🏢 Station: $stationName")
                Log.d(TAG, "📋 Feeders: ${feeders.size}")

                // Update feeder list
                allFeeders.clear()
                allFeeders.addAll(feeders)

                feeders.forEachIndexed { i, f ->
                    Log.d(TAG, "  [$i] ${f.feederName} (${f.feederCode})")
                }

                // Update table title with station name
                updateTableTitle()

                // Setup spinner on Main thread
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "🎨 Setting up spinner on Main thread...")
                    setupFeederSpinner()
                }

                withContext(Dispatchers.Main) {
                    val message = if (stationName.isNotEmpty()) {
                        "Loaded ${feeders.size} feeders from $stationName"
                    } else {
                        "Loaded ${feeders.size} feeders"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ EXCEPTION", e)
                e.printStackTrace()

                withContext(Dispatchers.Main) {
                    Snackbar.make(
                        binding.root,
                        "Error: ${e.message}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                }
            }
        }
    }

    /**
     * Setup feeder spinner with loaded feeders
     */
    private fun setupFeederSpinner() {
        Log.d(TAG, "─────────────────────────────────────")
        Log.d(TAG, "🎯 setupFeederSpinner() START")
        Log.d(TAG, "📊 Feeders: ${allFeeders.size}")

        if (allFeeders.isEmpty()) {
            Log.e(TAG, "❌ No feeders!")
            Toast.makeText(context, "No feeders available", Toast.LENGTH_LONG).show()
            return
        }

        try {
            // Create display list
            val feederDisplayList = allFeeders.map {
                "${it.feederName} (${it.feederCode})"
            }

            Log.d(TAG, "📋 Display list:")
            feederDisplayList.forEachIndexed { i, item ->
                Log.d(TAG, "    [$i] $item")
            }

            // Create spinner adapter
            val spinnerAdapter = ArrayAdapter(
                requireContext(),
                R.layout.spinner_item_black,  // ← Use custom layout
                feederDisplayList
            )
            spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_black)

            // Set adapter
            binding.spinnerFeeder.adapter = spinnerAdapter

            Log.d(TAG, "✅ SPINNER ADAPTER SET!")
            Log.d(TAG, "📊 Adapter count: ${binding.spinnerFeeder.adapter?.count}")

            // Set selection listener
            binding.spinnerFeeder.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    Log.d(TAG, "🎯 ITEM SELECTED: position=$position")

                    if (position >= 0 && position < allFeeders.size) {
                        val selected = allFeeders[position]
                        Log.d(TAG, "✅ Selected: ${selected.feederName}")

                        val consumptionData = EntryConsumptionData(
                            feederName = selected.feederName,
                            feederCode = selected.feederCode,
                            feederCategory = selected.feederCategory,
                            remark = "",
                            totalConsumption = null,
                            supply3ph = "",
                            supply1ph = ""
                        )

                        selectedFeederData = consumptionData
                        adapter.submitData(consumptionData, dateFormat.format(calendar.time), stationName)

                        Log.d(TAG, "✅ Data submitted to RecyclerView")
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            Log.d(TAG, "✅ SPINNER SETUP COMPLETE!")

        } catch (e: Exception) {
            Log.e(TAG, "❌ SPINNER ERROR", e)
            e.printStackTrace()
        }
    }

    /**
     * Fetch feeders from API and extract station name
     */
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
            Log.d(TAG, "📡 Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }

                Log.d(TAG, "📄 Response: ${response.take(200)}...")
                parseFeedersResponse(response)
            } else {
                val errorMessage = connection.errorStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream)).use { it.readText() }
                } ?: "HTTP Error: $responseCode"

                Log.e(TAG, "❌ API Error: $errorMessage")
                throw Exception("Failed to fetch feeders: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parse API response to extract station name and feeders
     */
    private fun parseFeedersResponse(jsonString: String): FeedersResponse {
        val feeders = mutableListOf<FeederData>()
        var station = ""

        try {
            val jsonObject = JSONObject(jsonString)
            val success = jsonObject.optBoolean("success", false)

            if (!success) {
                throw Exception(jsonObject.optString("message", "Failed to fetch feeders"))
            }

            // Extract station name from response
            station = jsonObject.optString("station", "")
            Log.d(TAG, "🏢 Parsed station: $station")

            val dataArray = jsonObject.optJSONArray("data")

            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    val feeder = FeederData(
                        feederCode = item.optString("FEEDER_CODE", ""),
                        feederName = item.optString("FEEDER_NAME", ""),
                        feederCategory = item.optString("FEEDER_CATEGORY", "")
                    )
                    feeders.add(feeder)
                    Log.d(TAG, "  ➕ ${feeder.feederName} (${feeder.feederCode})")
                }
            }

            Log.d(TAG, "✅ Parsed: $station with ${feeders.size} feeders")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Parse error", e)
            throw e
        }

        return FeedersResponse(station, feeders)
    }

    /**
     * Validate and submit data
     */
    private fun validateAndSubmitData() {
        if (selectedFeederData == null) {
            Toast.makeText(context, "Please select a feeder first", Toast.LENGTH_SHORT).show()
            return
        }

        val data = adapter.getCurrentData() ?: selectedFeederData!!

        // Check if any data entered
        val hasData = (data.totalConsumption != null && data.totalConsumption!! > 0) ||
                !data.supply3ph.isNullOrEmpty() ||
                !data.supply1ph.isNullOrEmpty() ||
                !data.remark.isNullOrEmpty()

        if (!hasData) {
            Toast.makeText(
                context,
                "Please enter at least one field",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // ✅ Build confirmation message - show station only if available
        val confirmMessage = buildString {
            append("Submit data?\n\n")
            append("Feeder: ${data.feederName}\n")
            append("Date: ${dateFormat.format(calendar.time)}")
            if (stationName.isNotEmpty()) {
                append("\nStation: $stationName")
            }
        }

        // Confirm submission
        AlertDialog.Builder(requireContext())
            .setTitle("Confirm Submission")
            .setMessage(confirmMessage)
            .setPositiveButton("Submit") { _, _ ->
                submitDataToAPI(listOf(data))
            }
            .setNegativeButton("Cancel", null)
            .show()


    }

    /**
     * Submit data to API
     */
    private fun submitDataToAPI(dataList: List<EntryConsumptionData>) {
        val token = SessionManager.getToken(requireContext())

        if (token.isEmpty()) {
            Snackbar.make(binding.root, "Authentication token missing", Snackbar.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "🚀 Submitting data...")
        showLoading(true, "Submitting...")

        lifecycleScope.launch {
            try {
                val selectedDate = dateFormat.format(calendar.time)

                val result = withContext(Dispatchers.IO) {
                    submitToAPI(token, dataList, selectedDate)
                }

                if (result.success) {
                    // ✅ Build success message - show station only if available
                    val successMessage = buildString {
                        append("✓ Data saved!\n\n")
                        if (stationName.isNotEmpty()) {
                            append("Station: $stationName\n")
                        }
                        append("Date: $selectedDate\n")
                        append("Feeder: ${dataList[0].feederName}")
                    }

                    AlertDialog.Builder(requireContext())
                        .setTitle("Success")
                        .setMessage(successMessage)
                        .setPositiveButton("OK") { _, _ ->
                            selectedFeederData = null
                            adapter.clearData()
                            fetchFeederList()

                            // ✅ NAVIGATE TO HOMEPAGE
                            findNavController().popBackStack()
                        }
                        .show()
                } else {
                    showError("Save failed: ${result.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Submit error", e)
                showError("Error: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    /**
     * Submit to save API
     */
    private suspend fun submitToAPI(
        token: String,
        dataList: List<EntryConsumptionData>,
        date: String
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

            // Build request body
            val rowsArray = JSONArray()

            dataList.forEach { item ->
                val rowObject = JSONObject().apply {
                    put("date", date)
                    put("station_name", stationName)
                    put("feeder_name", item.feederName)
                    put("feeder_code", item.feederCode)
                    put("feeder_category", item.feederCategory ?: "")
                    put("remark", item.remark ?: "")
                    put("total_consumption", item.totalConsumption ?: 0.0)
                    put("supply_3ph", item.supply3ph ?: "")
                    put("supply_1ph", item.supply1ph ?: "")
                }
                rowsArray.put(rowObject)
            }

            val requestBody = JSONObject().apply {
                put("rows", rowsArray)
            }

            Log.d(TAG, "📤 Request:\n${requestBody.toString(2)}")

            // Send request
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "📡 Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }

                Log.d(TAG, "✅ Response: $response")

                val jsonResponse = JSONObject(response)
                val success = jsonResponse.optBoolean("success", false)
                val message = jsonResponse.optString("message", "")

                SaveResult(success, message)
            } else {
                val errorMessage = connection.errorStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream)).use { it.readText() }
                } ?: "HTTP Error: $responseCode"

                Log.e(TAG, "❌ Error: $errorMessage")
                SaveResult(false, errorMessage)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Show data summary
     */
    private fun showDataSummary() {
        if (selectedFeederData == null) {
            Toast.makeText(context, "No data. Select a feeder first.", Toast.LENGTH_SHORT).show()
            return
        }

        val data = adapter.getCurrentData() ?: selectedFeederData!!

        // ✅ Build summary - show station only if available
        val summary = buildString {
            append("📊 Current Data\n\n")
            if (stationName.isNotEmpty()) {
                append("Station: $stationName\n")
            }
            append("Feeder: ${data.feederName}\n")
            append("Code: ${data.feederCode}\n\n")
            append("Consumption: ${data.totalConsumption ?: "Not entered"} kWh\n")
            append("Supply 3PH: ${data.supply3ph ?: "Not entered"}\n")
            append("Supply 1PH: ${data.supply1ph ?: "Not entered"}\n")
            append("Remark: ${data.remark ?: "None"}\n\n")
            append("Date: ${dateFormat.format(calendar.time)}")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Data Summary")
            .setMessage(summary)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLoading(show: Boolean, message: String = "Loading...") {
        binding.btnSubmit.isEnabled = !show
        binding.btnEdit.isEnabled = !show
        binding.btnView.isEnabled = !show
        binding.etDate.isEnabled = !show
        binding.spinnerFeeder.isEnabled = !show

        if (show) {
            binding.btnSubmit.text = message
        } else {
            binding.btnSubmit.text = "SUBMIT"
        }
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

/**
 * Response wrapper with station name and feeders
 */
data class FeedersResponse(
    val station: String,
    val feeders: List<FeederData>
)

/**
 * Feeder data from API
 */
data class FeederData(
    val feederCode: String,
    val feederName: String,
    val feederCategory: String
)

/**
 * Entry data for consumption
 */
data class EntryConsumptionData(
    val feederName: String,
    val feederCode: String,
    var feederCategory: String?,
    var remark: String?,
    var totalConsumption: Double?,
    var supply3ph: String?,
    var supply1ph: String?
)

/**
 * Save result
 */
data class SaveResult(
    val success: Boolean,
    val message: String
)

/**
 * RecyclerView adapter
 */
class EntryConsumptionDataAdapter(
    private val onDataChange: (EntryConsumptionData) -> Unit
) : RecyclerView.Adapter<EntryConsumptionDataAdapter.ViewHolder>() {

    private var currentData: EntryConsumptionData? = null
    private var currentDate: String = ""
    private var currentStation: String = ""

    fun submitData(data: EntryConsumptionData, date: String, station: String) {
        currentData = data
        currentDate = date
        currentStation = station
        notifyDataSetChanged()
    }

    fun getCurrentData(): EntryConsumptionData? = currentData

    fun clearData() {
        currentData = null
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_consumption_column, parent, false)
        return ViewHolder(view, onDataChange)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        currentData?.let { holder.bind(it, currentDate, currentStation) }
    }

    override fun getItemCount(): Int = if (currentData != null) 1 else 0

    class ViewHolder(
        itemView: View,
        private val onDataChange: (EntryConsumptionData) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
        private val etRemark: EditText = itemView.findViewById(R.id.etRemark)
        private val etTotal: EditText = itemView.findViewById(R.id.etTotal)
        private val spinnerSupply3ph: Spinner = itemView.findViewById(R.id.spinnerSupply3ph)
        private val spinnerSupply1ph: Spinner = itemView.findViewById(R.id.spinnerSupply1ph)
        // tvCode removed - not needed in UI

        init {
            Log.d("ConsumptionEntry", "🔧 Setting up TIME spinners")

            // Create time options: --Select or type--, 00:00, 00:01, ... 23:59
            val timeOptions = mutableListOf<String>()
            timeOptions.add("--Select or type--")

            // Generate all time values from 00:00 to 23:59
            for (hour in 0..23) {
                for (minute in 0..59) {
                    timeOptions.add(String.format("%02d:%02d", hour, minute))
                }
            }

            Log.d("ConsumptionEntry", "⏰ Generated ${timeOptions.size} time options")

            // Setup 3PH spinner with time values
            val adapter3ph = ArrayAdapter(
                itemView.context,
                android.R.layout.simple_spinner_item,
                timeOptions
            )
            adapter3ph.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerSupply3ph.adapter = adapter3ph

            // Setup 1PH spinner with time values
            val adapter1ph = ArrayAdapter(
                itemView.context,
                android.R.layout.simple_spinner_item,
                timeOptions
            )
            adapter1ph.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerSupply1ph.adapter = adapter1ph

            Log.d("ConsumptionEntry", "✅ Time spinners configured")
        }

        fun bind(data: EntryConsumptionData, date: String, station: String) {


            tvCategory.text = data.feederCategory ?: ""

            etRemark.setText(data.remark ?: "")
            etTotal.setText(data.totalConsumption?.toString() ?: "")

            // Set spinner selections based on saved time values
            // Find position in time list
            val timeOptions = mutableListOf<String>()
            timeOptions.add("--Select or type--")
            for (hour in 0..23) {
                for (minute in 0..59) {
                    timeOptions.add(String.format("%02d:%02d", hour, minute))
                }
            }

            val pos3ph = if (data.supply3ph.isNullOrEmpty()) {
                0
            } else {
                timeOptions.indexOf(data.supply3ph).takeIf { it >= 0 } ?: 0
            }
            spinnerSupply3ph.setSelection(pos3ph)

            val pos1ph = if (data.supply1ph.isNullOrEmpty()) {
                0
            } else {
                timeOptions.indexOf(data.supply1ph).takeIf { it >= 0 } ?: 0
            }
            spinnerSupply1ph.setSelection(pos1ph)

            // Text watchers
            setupTextWatcher(etRemark) { data.remark = it }
            setupTextWatcher(etTotal) { data.totalConsumption = it.toDoubleOrNull() }

            // Spinner listeners - save selected time value
            spinnerSupply3ph.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    // Set text color to black after selection
                    (view as? TextView)?.setTextColor(android.graphics.Color.BLACK)

                    if (position > 0) {
                        data.supply3ph = parent?.getItemAtPosition(position).toString()
                        Log.d("ConsumptionEntry", "⏰ 3PH time = ${data.supply3ph}")
                    } else {
                        data.supply3ph = ""
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            spinnerSupply1ph.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    // Set text color to black after selection
                    (view as? TextView)?.setTextColor(android.graphics.Color.BLACK)

                    if (position > 0) {
                        data.supply1ph = parent?.getItemAtPosition(position).toString()
                        Log.d("ConsumptionEntry", "⏰ 1PH time = ${data.supply1ph}")
                    } else {
                        data.supply1ph = ""
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        private fun setupTextWatcher(editText: EditText, onTextChanged: (String) -> Unit) {
            editText.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    onTextChanged(s.toString())
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
    }
}