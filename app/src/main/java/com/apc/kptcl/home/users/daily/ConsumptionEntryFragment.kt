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
        private const val FEEDER_LIST_URL = "http://62.72.59.119:9000/api/feeder/list"
        private const val CONSUMPTION_URL = "http://62.72.59.119:9000/api/feeder/consumption"
        private const val SAVE_URL = "http://62.72.59.119:9000/api/feeder/consumption/save"
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
                R.layout.spinner_item_black,
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

                        // ✅ Try to fetch existing data first
                        fetchExistingDataAndDisplay(selected)
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
     * ✅ FIXED: Fetch existing data for selected feeder and display
     * Now handles null feeder codes by using feeder_name
     */
    private fun fetchExistingDataAndDisplay(selected: FeederData) {
        lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())
                val selectedDate = dateFormat.format(calendar.time)

                Log.d(TAG, "📥 Fetching existing data for ${selected.feederName} on $selectedDate")

                // ✅ FIX: Pass both feederCode AND feederName
                val existingData = fetchExistingConsumptionData(
                    token,
                    feederId = selected.feederCode,
                    feederName = selected.feederName,
                    selectedDate
                )

                // Create consumption data object (with existing data if found)
                val consumptionData = EntryConsumptionData(
                    feederName = selected.feederName,
                    feederCode = selected.feederCode,
                    feederCategory = selected.feederCategory,
                    remark = existingData?.remark ?: "PROPER",
                    totalConsumption = existingData?.totalConsumption,
                    supply3ph = existingData?.supply3ph ?: "",
                    supply1ph = existingData?.supply1ph ?: ""
                )

                selectedFeederData = consumptionData
                adapter.submitData(consumptionData, selectedDate, stationName)

                if (existingData != null) {
                    Log.d(TAG, "✅ Existing data loaded: TC=${existingData.totalConsumption}, 3PH=${existingData.supply3ph}")
                    Snackbar.make(binding.root, "✓ Existing data loaded", Snackbar.LENGTH_SHORT).show()
                } else {
                    Log.d(TAG, "ℹ️ No existing data found - showing empty form")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error fetching existing data: ${e.message}")
                // On error, show empty form
                val consumptionData = EntryConsumptionData(
                    feederName = selected.feederName,
                    feederCode = selected.feederCode,
                    feederCategory = selected.feederCategory,
                    remark = "PROPER",
                    totalConsumption = null,
                    supply3ph = "",
                    supply1ph = ""
                )
                selectedFeederData = consumptionData
                adapter.submitData(consumptionData, dateFormat.format(calendar.time), stationName)
            }
        }
    }

    /**
     * ✅ FIXED: Fetch existing consumption data from API
     * Now handles null feeder codes by using feeder_name in request
     */
    private suspend fun fetchExistingConsumptionData(
        token: String,
        feederId: String?,
        feederName: String?,
        date: String
    ): EntryConsumptionData? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(CONSUMPTION_URL)
            connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.doOutput = true
            connection.doInput = true

            // ✅ FIX: Build request body based on whether code exists
            val jsonBody = JSONObject().apply {
                // Only include feeder_id if it's not null
                feederId?.let { put("feeder_id", it) }
                put("feeder_name", feederName)  // ✅ Always include feeder_name as fallback
                put("date", date)
            }

            Log.d(TAG, "📤 Fetch request: $jsonBody")

            // Send request
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(jsonBody.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            Log.d(TAG, "📥 Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                // ✅ LOG FULL RESPONSE
                Log.d(TAG, "📥 FULL Response: $response")

                // Parse response
                val jsonObject = JSONObject(response)
                val success = jsonObject.optBoolean("success", false)

                if (success) {
                    val dataArray = jsonObject.optJSONArray("data")

                    // ✅ LOG ARRAY
                    Log.d(TAG, "📊 Data array length: ${dataArray?.length() ?: 0}")

                    if (dataArray != null && dataArray.length() > 0) {
                        val item = dataArray.getJSONObject(0)

                        // ✅ LOG EACH FIELD
                        Log.d(TAG, "🔍 FEEDER_NAME: ${item.optString("FEEDER_NAME")}")
                        Log.d(TAG, "🔍 TOTAL_CONSUMPTION: ${item.optString("TOTAL_CONSUMPTION")}")
                        Log.d(TAG, "🔍 SUPPLY_3PH: ${item.optString("SUPPLY_3PH")}")
                        Log.d(TAG, "🔍 SUPPLY_1PH: ${item.optString("SUPPLY_1PH")}")
                        Log.d(TAG, "🔍 REMARK: ${item.optString("REMARK")}")

                        val totalConsumption = item.optDouble("TOTAL_CONSUMPTION")
                        Log.d(TAG, "🔍 TC as double: $totalConsumption, isNaN: ${totalConsumption.isNaN()}")

                        // Return existing data
                        val result = EntryConsumptionData(
                            feederName = item.optString("FEEDER_NAME", ""),
                            feederCode = feederId,
                            feederCategory = item.optString("FEEDER_CATEGORY", ""),
                            remark = item.optString("REMARK", "PROPER"),
                            totalConsumption = totalConsumption.takeIf { !it.isNaN() },
                            supply3ph = item.optString("SUPPLY_3PH", ""),
                            supply1ph = item.optString("SUPPLY_1PH", "")
                        )

                        Log.d(TAG, "✅ Returning: TC=${result.totalConsumption}, 3PH=${result.supply3ph}, 1PH=${result.supply1ph}")

                        return@withContext result
                    }
                }
            } else {
                Log.w(TAG, "⚠️ No existing data (HTTP $responseCode)")
            }

            null // No data found

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error fetching existing data: ${e.message}")
            e.printStackTrace()
            null // Return null on error
        } finally {
            connection?.disconnect()
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
                        feederCode = if (item.isNull("FEEDER_CODE")) null else item.optString("FEEDER_CODE"),
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
                    // Only include feeder_code if it's not null
                    item.feederCode?.let { put("feeder_code", it) }
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
    val feederCode: String?,
    val feederName: String,
    val feederCategory: String
)

/**
 * Entry data for consumption
 */
data class EntryConsumptionData(
    val feederName: String,
    val feederCode: String?,
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
        private val etSupply3ph: EditText = itemView.findViewById(R.id.etSupply3ph)
        private val etSupply1ph: EditText = itemView.findViewById(R.id.etSupply1ph)

        init {
            Log.d("ConsumptionEntry", "🔧 Setting up time input fields")

            // Add TextWatcher for auto-formatting HH:MM
            setupTimeInputFormatting(etSupply3ph)
            setupTimeInputFormatting(etSupply1ph)

            Log.d("ConsumptionEntry", "✅ Time input fields configured")
        }

        /**
         * Setup automatic HH:MM formatting as user types
         */
        private fun setupTimeInputFormatting(editText: EditText) {
            editText.addTextChangedListener(object : TextWatcher {
                private var isFormatting = false

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    if (isFormatting) return

                    isFormatting = true
                    val input = s.toString().replace(":", "")

                    if (input.length >= 2) {
                        val formatted = buildString {
                            append(input.substring(0, 2))
                            if (input.length > 2) {
                                append(":")
                                append(input.substring(2, minOf(4, input.length)))
                            }
                        }

                        if (formatted != s.toString()) {
                            editText.setText(formatted)
                            editText.setSelection(formatted.length)
                        }
                    }

                    isFormatting = false
                }
            })
        }

        fun bind(data: EntryConsumptionData, date: String, station: String) {
            tvCategory.text = data.feederCategory ?: ""

            etRemark.setText(data.remark ?: "")
            etTotal.setText(data.totalConsumption?.toString() ?: "")

            // Set time values from data
            etSupply3ph.setText(data.supply3ph ?: "")
            etSupply1ph.setText(data.supply1ph ?: "")

            // Text watchers
            setupTextWatcher(etRemark) { data.remark = it }
            setupTextWatcher(etTotal) { data.totalConsumption = it.toDoubleOrNull() }

            // Time validation watchers with 24-hour total check
            setupTimeWatcher(etSupply3ph, etSupply1ph) { time3ph ->
                data.supply3ph = time3ph
                Log.d("ConsumptionEntry", "⏰ 3PH time = $time3ph")
            }

            setupTimeWatcher(etSupply1ph, etSupply3ph) { time1ph ->
                data.supply1ph = time1ph
                Log.d("ConsumptionEntry", "⏰ 1PH time = $time1ph")
            }
        }

        /**
         * Setup time watcher with validation
         * Validates HH:MM format and ensures 3PH + 1PH <= 24:00
         */
        private fun setupTimeWatcher(
            currentField: EditText,
            otherField: EditText,
            onTimeChanged: (String) -> Unit
        ) {
            currentField.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val timeStr = s.toString().trim()

                    // Skip if empty
                    if (timeStr.isEmpty()) {
                        onTimeChanged("")
                        currentField.error = null
                        return
                    }

                    // Validate HH:MM format
                    if (!isValidTimeFormat(timeStr)) {
                        currentField.error = "Invalid format. Use HH:MM (00:00 to 23:59)"
                        return
                    }

                    // Parse current and other time
                    val currentMinutes = parseTimeToMinutes(timeStr)
                    val otherTimeStr = otherField.text.toString().trim()
                    val otherMinutes = if (otherTimeStr.isNotEmpty() && isValidTimeFormat(otherTimeStr)) {
                        parseTimeToMinutes(otherTimeStr)
                    } else {
                        0
                    }

                    // Validate total <= 24 hours (1440 minutes)
                    val totalMinutes = currentMinutes + otherMinutes
                    if (totalMinutes > 1440) {
                        currentField.error = "❌ Total supply time (3PH + 1PH) cannot exceed 24:00 hours"
                        return
                    }

                    // All validations passed
                    currentField.error = null
                    onTimeChanged(timeStr)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        /**
         * Validate HH:MM format
         */
        private fun isValidTimeFormat(time: String): Boolean {
            if (!time.matches(Regex("^\\d{2}:\\d{2}$"))) return false

            val parts = time.split(":")
            val hours = parts[0].toIntOrNull() ?: return false
            val minutes = parts[1].toIntOrNull() ?: return false

            return hours in 0..23 && minutes in 0..59
        }

        /**
         * Convert HH:MM to total minutes
         */
        private fun parseTimeToMinutes(time: String): Int {
            val parts = time.split(":")
            val hours = parts[0].toIntOrNull() ?: 0
            val minutes = parts[1].toIntOrNull() ?: 0
            return (hours * 60) + minutes
        }

        /**
         * Setup generic text watcher
         */
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