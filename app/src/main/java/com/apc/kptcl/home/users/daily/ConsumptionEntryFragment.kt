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
import com.apc.kptcl.utils.ApiErrorHandler

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
        private const val FEEDER_LIST_URL = "http://62.72.59.119:9009/api/feeder/list"
        private const val CONSUMPTION_URL = "http://62.72.59.119:9009/api/feeder/consumption"
        private const val SAVE_URL = "http://62.72.59.119:9009/api/feeder/consumption/save"
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
        // ✅ Point 6 FIX: Default date = yesterday (today blocked for consumption entry)
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        binding.etDate.setText(dateFormat.format(calendar.time))

        binding.etDate.setOnClickListener {
            val dialog = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    calendar.set(year, month, day)
                    binding.etDate.setText(dateFormat.format(calendar.time))
                    updateTableTitle()

                    // Reload data for new date if feeder is selected
                    val selectedPosition = binding.spinnerFeeder.selectedItemPosition
                    if (selectedPosition >= 0 && selectedPosition < allFeeders.size) {
                        fetchExistingDataAndDisplay(allFeeders[selectedPosition])
                    }
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )

            // ✅ Point 6 FIX: Block today and future — consumption can only be entered for yesterday or earlier
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            dialog.datePicker.maxDate = yesterday.timeInMillis

            dialog.show()
        }
    }

    private fun setupRecyclerView() {
        adapter = EntryConsumptionDataAdapter { updatedData ->
            selectedFeederData = updatedData
        }
        binding.rvConsumptionData.layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.HORIZONTAL,
            false
        )
        binding.rvConsumptionData.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnSubmit.setOnClickListener { submitData() }
        binding.btnEdit.setOnClickListener { refreshData() }
        binding.btnView.setOnClickListener { navigateToView() }
    }

    private fun updateTableTitle() {
        val dateStr = dateFormat.format(calendar.time)
        val title = if (stationName.isNotEmpty()) {
            "FEEDER CONSUMPTION DATA ENTRY FOR $dateStr ($stationName)"
        } else {
            "FEEDER CONSUMPTION DATA ENTRY FOR $dateStr"
        }
        binding.tvTableTitle.text = title
    }

    // ============================================================================
    // ✅ VALIDATION METHODS - NEW ADDITION
    // ============================================================================

    /**
     * ✅ CRITICAL: Validate data before submission
     * BLOCKS submission if:
     * 1. Individual field (3PH or 1PH) > 24:00
     * 2. Total (3PH + 1PH) > 24:00
     */
    private fun validateBeforeSubmit(data: EntryConsumptionData): String? {
        val supply3ph = data.supply3ph?.trim() ?: ""
        val supply1ph = data.supply1ph?.trim() ?: ""

        // Point 6: If both are empty, they will default to "00:00" — this is always valid
        if (supply3ph.isEmpty() && supply1ph.isEmpty()) {
            return null // No error — defaults to 00:00 on submit
        }

        // ✅ Validate 3PH format and value if not empty
        if (supply3ph.isNotEmpty()) {
            if (!isValidTimeFormat(supply3ph)) {
                return """
                ❌ Invalid 3PH format!
                
                Expected: HH:MM (00:00 to 24:00)
                Your input: $supply3ph
                
                Examples:
                • 18:30 ✅
                • 24:00 ✅
                • 25:00 ❌
                • 18:60 ❌
            """.trimIndent()
            }

            // ✅ CRITICAL: Block if 3PH alone exceeds 24:00
            val minutes3ph = parseTimeToMinutes(supply3ph)
            if (minutes3ph > 1440) {
                return """
                ❌ 3PH Supply exceeds 24:00!
                
                3PH Supply: $supply3ph
                Maximum allowed: 24:00
                
                ⚠️ SUBMISSION BLOCKED!
                Please correct the value.
            """.trimIndent()
            }
        }

        // ✅ Validate 1PH format and value if not empty
        if (supply1ph.isNotEmpty()) {
            if (!isValidTimeFormat(supply1ph)) {
                return """
                ❌ Invalid 1PH format!
                
                Expected: HH:MM (00:00 to 24:00)
                Your input: $supply1ph
                
                Examples:
                • 05:30 ✅
                • 24:00 ✅
                • 30:00 ❌
                • 05:99 ❌
            """.trimIndent()
            }

            // ✅ CRITICAL: Block if 1PH alone exceeds 24:00
            val minutes1ph = parseTimeToMinutes(supply1ph)
            if (minutes1ph > 1440) {
                return """
                ❌ 1PH Supply exceeds 24:00!
                
                1PH Supply: $supply1ph
                Maximum allowed: 24:00
                
                ⚠️ SUBMISSION BLOCKED!
                Please correct the value.
            """.trimIndent()
            }
        }

        // ✅ Validate total (3PH + 1PH) <= 24:00
        val minutes3ph = if (supply3ph.isNotEmpty() && isValidTimeFormat(supply3ph)) {
            parseTimeToMinutes(supply3ph)
        } else {
            0
        }

        val minutes1ph = if (supply1ph.isNotEmpty() && isValidTimeFormat(supply1ph)) {
            parseTimeToMinutes(supply1ph)
        } else {
            0
        }

        val totalMinutes = minutes3ph + minutes1ph

        if (totalMinutes > 1440) {
            val totalTime = convertMinutesToTime(totalMinutes)
            return """
            ❌ Total Supply exceeds 24:00!
            
            3PH Supply: $supply3ph
            1PH Supply: $supply1ph
            Total: $totalTime
            
            Maximum: 24:00
            
            ⚠️ SUBMISSION BLOCKED!
            Please adjust the values.
        """.trimIndent()
        }

        return null // ✅ All validations passed
    }


    /**
     * ✅ Validate HH:MM format - allows 00:00 to 24:00
     */
    private fun isValidTimeFormat(time: String): Boolean {
        if (time.isBlank()) return true

        // ✅ CRITICAL: Use [0-5][0-9] to allow only 00-59 for minutes
        if (!time.matches(Regex("^\\d{1,2}:[0-5][0-9]$"))) return false

        val parts = time.split(":")
        val hours = parts[0].toIntOrNull() ?: return false
        val minutes = parts[1].toIntOrNull() ?: return false

        // ✅ Allow 00:00 to 24:00 (24:00 is valid, but 24:01+ is not)
        return hours in 0..24 && minutes in 0..59 && !(hours == 24 && minutes > 0)
    }

    /**
     * ✅ Convert time to minutes
     */
    private fun parseTimeToMinutes(time: String): Int {
        if (time.isBlank()) return 0

        val parts = time.split(":")
        val hours = parts[0].toIntOrNull() ?: 0
        val minutes = parts[1].toIntOrNull() ?: 0

        return (hours * 60) + minutes
    }

    /**
     * ✅ Convert minutes back to HH:MM
     */
    private fun convertMinutesToTime(totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return String.format("%02d:%02d", hours, minutes)
    }

    /**
     * ✅ Helper: Check karta hai ki time valid format mein hai AND 24:00 ke andar hai
     * Submit ke time error message ke liye use hota hai
     */
    private fun isValidTimeOrInRange(time: String): Boolean {
        if (time.isBlank()) return true
        if (!isValidTimeFormat(time)) return false
        return parseTimeToMinutes(time) <= 1440
    }

    // ============================================================================
    // END OF VALIDATION METHODS
    // ============================================================================

    private fun fetchFeederList() {
        lifecycleScope.launch {
            showLoading(true, "Loading feeders...")

            try {
                val token = SessionManager.getToken(requireContext())
                Log.d(TAG, "🔑 Using token: ${token.take(30)}...")

                val response = withContext(Dispatchers.IO) {
                    val url = URL(FEEDER_LIST_URL)
                    val connection = url.openConnection() as HttpURLConnection

                    connection.requestMethod = "GET"
                    connection.connectTimeout = TIMEOUT
                    connection.readTimeout = TIMEOUT
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer $token")

                    val responseCode = connection.responseCode
                    Log.d(TAG, "📡 Response code: $responseCode")

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream))
                        val response = reader.readText()
                        reader.close()
                        Log.d(TAG, "✅ SUCCESS! Response: ${response.take(200)}...")
                        response
                    } else {
                        val errorMsg = ApiErrorHandler.fromErrorStream(connection.errorStream, responseCode)
                        throw Exception(errorMsg)
                    }
                }

                val json = JSONObject(response)
                val success = json.optBoolean("success", false)

                if (!success) {
                    throw Exception(json.optString("message", "Failed to load feeders"))
                }

                // Parse username as station name
                stationName = json.optString("username", "")
                Log.d(TAG, "🏢 Station: $stationName")

                // Parse feeders
                val dataArray = json.getJSONArray("data")
                val feeders = mutableListOf<FeederData>()

                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    val feeder = FeederData(
                        feederCode = item.optString("FEEDER_CODE", null),
                        feederName = item.getString("FEEDER_NAME"),
                        feederCategory = item.getString("FEEDER_CATEGORY")
                    )
                    feeders.add(feeder)
                }

                Log.d(TAG, "✅ Parsed ${feeders.size} feeders")
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
                    Snackbar.make(binding.root, ApiErrorHandler.handle(e), Snackbar.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                }
            }
        }
    }

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

    private fun fetchExistingDataAndDisplay(selected: FeederData) {
        lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())
                val selectedDate = dateFormat.format(calendar.time)

                Log.d(TAG, "📥 Fetching existing data for ${selected.feederName} on $selectedDate")

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

            val jsonBody = JSONObject().apply {
                feederId?.let { put("feeder_id", it) }
                put("feeder_name", feederName)
                put("date", date)
            }

            Log.d(TAG, "📤 Fetch request: $jsonBody")

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(jsonBody.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            Log.d(TAG, "📡 Fetch response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                Log.d(TAG, "✅ Fetch response: ${response.take(200)}")

                val json = JSONObject(response)
                val dataArray = json.optJSONArray("data")

                if (dataArray != null && dataArray.length() > 0) {
                    val item = dataArray.getJSONObject(0)

                    // ✅ FIXED: Properly handle null values from backend
                    EntryConsumptionData(
                        feederName = item.optString("FEEDER_NAME", feederName ?: ""),
                        feederCode = item.optString("FEEDER_CODE", feederId),
                        feederCategory = item.optString("FEEDER_CATEGORY", null),
                        remark = if (item.isNull("REMARK")) null else item.optString("REMARK", null),
                        totalConsumption = if (item.isNull("TOTAL_CONSUMPTION")) null else item.optDouble("TOTAL_CONSUMPTION"),
                        supply3ph = if (item.isNull("SUPPLY_3PH")) "" else item.optString("SUPPLY_3PH", ""),
                        supply1ph = if (item.isNull("SUPPLY_1PH")) "" else item.optString("SUPPLY_1PH", "")
                    )
                } else {
                    null
                }
            } else {
                Log.w(TAG, "⚠️ No existing data (${responseCode})")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Error fetching existing data: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun refreshData() {
        val selectedPosition = binding.spinnerFeeder.selectedItemPosition
        if (selectedPosition >= 0 && selectedPosition < allFeeders.size) {
            fetchExistingDataAndDisplay(allFeeders[selectedPosition])
            Snackbar.make(binding.root, "Data refreshed", Snackbar.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Select a feeder first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToView() {
        findNavController().navigate(R.id.action_consumptionEntryFragment_to_dailyParameterEntryFragment)
    }

    // ============================================================================
    // ✅ UPDATED submitData() - WITH VALIDATION
    // ============================================================================

    private fun submitData() {
        val data = adapter.getCurrentData()

        // ✅ CRITICAL: Check for validation errors FIRST
        if (adapter.hasValidationErrors()) {
            // ✅ FIX: Actual entered value ke saath specific error message dikhao
            val supply3ph = data?.supply3ph?.trim() ?: ""
            val supply1ph = data?.supply1ph?.trim() ?: ""

            val errorDetail = buildString {
                if (supply3ph.isNotEmpty() && !isValidTimeOrInRange(supply3ph)) {
                    appendLine("• 3PH: \"$supply3ph\" — 24:00 se zyada allowed nahi hai")
                }
                if (supply1ph.isNotEmpty() && !isValidTimeOrInRange(supply1ph)) {
                    appendLine("• 1PH: \"$supply1ph\" — 24:00 se zyada allowed nahi hai")
                }
                if (isEmpty()) {
                    appendLine("• Koi invalid time value field mein hai")
                }
            }

            AlertDialog.Builder(requireContext())
                .setTitle("❌ Validation Error")
                .setMessage("""
                    Galat time value hai! Submit nahi ho sakta.
                    
                    $errorDetail
                    ✅ Allowed range: 00:00 to 24:00
                    
                    Kripya sahi value enter karein.
                """.trimIndent())
                .setPositiveButton("Theek Hai") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
            return
        }

        if (data == null) {
            showError("No data to submit. Please select a feeder first.")
            return
        }

        // ✅ CRITICAL: Validate BEFORE submitting
        Log.d(TAG, "🔍 Validating data: 3PH=${data.supply3ph}, 1PH=${data.supply1ph}")
        val validationError = validateBeforeSubmit(data)
        if (validationError != null) {
            Log.e(TAG, "❌ VALIDATION FAILED - SUBMISSION BLOCKED!")
            Log.e(TAG, "Error: $validationError")

            // ✅ Show AlertDialog for better visibility
            AlertDialog.Builder(requireContext())
                .setTitle("❌ Validation Failed")
                .setMessage(validationError)
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()

            return // ❌ BLOCK submission
        }

        Log.d(TAG, "✅ Validation passed - proceeding with submission")
        lifecycleScope.launch {
            showLoading(true, "Submitting...")

            try {
                val token = SessionManager.getToken(requireContext())
                val selectedDate = dateFormat.format(calendar.time)

                Log.d(TAG, "📤 Submitting data for ${data.feederName}")

                val result = submitToAPI(token, listOf(data), selectedDate)

                withContext(Dispatchers.Main) {
                    if (result.success) {
                        val successMessage = result.message.ifEmpty { "Data saved successfully!" }

                        AlertDialog.Builder(requireContext())
                            .setTitle("✅ Success")
                            .setMessage(successMessage)
                            .setPositiveButton("OK") { _, _ ->
                                // Refresh to show saved data
                                refreshData()
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        showError("Save failed: ${result.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Submit error", e)
                withContext(Dispatchers.Main) {
                    showError(ApiErrorHandler.handle(e))
                }
            } finally {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                }
            }
        }
    }

    // ============================================================================
    // ✅ UPDATED submitToAPI() - FIXED NULL HANDLING
    // ============================================================================

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

            val rowsArray = JSONArray()
            dataList.forEach { item ->
                val rowObject = JSONObject().apply {
                    put("date", date)
                    put("feeder_name", item.feederName)

                    // ✅ CRITICAL FIX: Send actual null, not "null" string
                    if (item.feederCode.isNullOrBlank() || item.feederCode == "null") {
                        put("feeder_code", JSONObject.NULL)
                    } else {
                        put("feeder_code", item.feederCode)
                    }

                    put("feeder_category", item.feederCategory ?: JSONObject.NULL)

                    // ✅ Point 7 FIX: null/blank remark defaults to "PROPER", never send null
                    val remarkValue = if (item.remark.isNullOrBlank()) "PROPER" else item.remark
                    put("remark", remarkValue)

                    put("total_consumption", item.totalConsumption ?: JSONObject.NULL)

                    // Point 6: Send "00:00" as default when supply times are empty (not null)
                    if (item.supply3ph.isNullOrBlank()) {
                        put("supply_3ph", "00:00")
                    } else {
                        put("supply_3ph", item.supply3ph)
                    }

                    if (item.supply1ph.isNullOrBlank()) {
                        put("supply_1ph", "00:00")
                    } else {
                        put("supply_1ph", item.supply1ph)
                    }
                }
                rowsArray.put(rowObject)
            }

            val requestBody = JSONObject().apply { put("rows", rowsArray) }
            Log.d(TAG, "📤 Save request: $requestBody")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "📡 Save response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                Log.d(TAG, "✅ Save response: $response")

                val jsonResponse = JSONObject(response)
                SaveResult(
                    jsonResponse.optBoolean("success", false),
                    jsonResponse.optString("message", "")
                )
            } else {
                val errorMsg = ApiErrorHandler.fromErrorStream(connection.errorStream, responseCode)
                SaveResult(false, errorMsg)
            }
        } finally {
            connection.disconnect()
        }
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
 * ✅ FIXED RecyclerView adapter with proper null handling and 24:00 support
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

    // ✅ NEW: Check if there are validation errors
    private var has3phError = false
    private var has1phError = false

    fun hasValidationErrors(): Boolean = has3phError || has1phError

    fun setValidationError(is3ph: Boolean, hasError: Boolean) {
        if (is3ph) {
            has3phError = hasError
        } else {
            has1phError = hasError
        }
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
         * ✅ ENHANCED: Automatic HH:MM formatting with STRICT minute validation
         * - Auto-inserts colon after 2 digits
         * - Blocks minutes > 59 in real-time
         * - Prevents invalid input before submission
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
                            // Hours part (first 2 digits)
                            append(input.substring(0, 2))

                            if (input.length > 2) {
                                append(":")

                                // ✅ CRITICAL: Validate minutes in real-time
                                val minutesPart = input.substring(2, minOf(4, input.length))

                                // ✅ Check if minutes are valid (00-59)
                                if (minutesPart.length == 2) {
                                    val minutes = minutesPart.toIntOrNull() ?: 0
                                    if (minutes > 59) {
                                        // ❌ Invalid minutes - clear the field
                                        editText.setText("")
                                        editText.error = "❌ Minutes must be 00-59 (not ${minutes})"

                                        android.widget.Toast.makeText(
                                            editText.context,
                                            "❌ Invalid minutes: $minutes\nMinutes must be between 00-59",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()

                                        isFormatting = false
                                        return
                                    }
                                }

                                append(minutesPart)
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

        private fun updateCellColor(editText: EditText) {
            if (editText.text.isNullOrBlank()) {
                editText.setBackgroundColor(android.graphics.Color.parseColor("#FFCCCC")) // red when empty
            } else {
                editText.setBackgroundResource(R.drawable.table_cell_border_editable) // normal when filled
            }
        }

        fun bind(data: EntryConsumptionData, date: String, station: String) {
            // ✅ FIXED: Display category or empty string (not "null")
            tvCategory.text = data.feederCategory ?: ""

            // ✅ FIXED: Handle null values properly - show empty string, not "null"
            etRemark.setText(if (data.remark.isNullOrBlank()) "" else data.remark)
            etTotal.setText(if (data.totalConsumption == null) "" else data.totalConsumption.toString())

            // Point 6: Set hint for total consumption
            etTotal.hint = "Enter or leave 0"

            // Point 6: Set 3PH/1PH values or show autofill hint when empty
            etSupply3ph.setText(if (data.supply3ph.isNullOrBlank()) "" else data.supply3ph)
            etSupply1ph.setText(if (data.supply1ph.isNullOrBlank()) "" else data.supply1ph)

            // Point 6: Autofill hint — shown when field is empty
            etSupply3ph.hint = "Autofill 00:00 or enter manually"
            etSupply1ph.hint = "Autofill 00:00 or enter manually"

            // Set initial background colors based on empty/filled state
            updateCellColor(etTotal)
            updateCellColor(etSupply3ph)
            updateCellColor(etSupply1ph)

            // Text watchers
            setupTextWatcher(etRemark) { data.remark = it.ifBlank { null } }
            setupTextWatcher(etTotal) {
                data.totalConsumption = it.toDoubleOrNull()
                updateCellColor(etTotal)
            }

            // Time validation watchers with 24-hour total check
            setupTimeWatcher(etSupply3ph, etSupply1ph) { time3ph ->
                data.supply3ph = time3ph.ifBlank { null }
                updateCellColor(etSupply3ph)
                Log.d("ConsumptionEntry", "⏰ 3PH time = $time3ph")
            }

            setupTimeWatcher(etSupply1ph, etSupply3ph) { time1ph ->
                data.supply1ph = time1ph.ifBlank { null }
                updateCellColor(etSupply1ph)
                Log.d("ConsumptionEntry", "⏰ 1PH time = $time1ph")
            }
        }

        /**
         * ✅ ENHANCED: Setup time watcher with STRICT validation and error tracking
         * - Validates format (HH:MM, 00:00 to 24:00)
         * - Checks if individual field > 24:00
         * - Checks if total (3PH + 1PH) > 24:00
         * - Tracks validation errors with flags
         */
        private fun setupTimeWatcher(
            currentField: EditText,
            otherField: EditText,
            onTimeChanged: (String) -> Unit
        ) {
            currentField.addTextChangedListener(object : TextWatcher {
                private var isFormatting = false

                override fun afterTextChanged(s: Editable?) {
                    if (isFormatting) return

                    val timeStr = s.toString().trim()
                    val is3phField = (currentField.id == etSupply3ph.id)

                    // Skip if empty - clear data AND error flag
                    if (timeStr.isEmpty()) {
                        onTimeChanged("")
                        currentField.error = null
                        otherField.error = null

                        // ✅ Clear error flag when field is empty
                        (itemView.parent?.parent as? RecyclerView)?.adapter?.let { adapter ->
                            if (adapter is EntryConsumptionDataAdapter) {
                                adapter.setValidationError(is3phField, false)
                            }
                        }
                        return
                    }

                    // Validate HH:MM format
                    if (!isValidTimeFormat(timeStr)) {
                        currentField.error = "Invalid format. Use HH:MM (00:00 to 24:00)"
                        onTimeChanged(timeStr)  // ✅ FIX: value preserve karo

                        // ✅ SET error flag
                        (itemView.parent?.parent as? RecyclerView)?.adapter?.let { adapter ->
                            if (adapter is EntryConsumptionDataAdapter) {
                                adapter.setValidationError(is3phField, true)
                            }
                        }
                        return
                    }

                    // Parse current field value
                    val currentMinutes = parseTimeToMinutes(timeStr)

                    // ✅ CRITICAL: Check if current field alone > 24:00
                    if (currentMinutes > 1440) {
                        currentField.error = "❌ 24:00 se zyada allowed nahi hai!"
                        onTimeChanged(timeStr)  // ✅ FIX: value preserve karo (clear mat karo), error flag block karega submit

                        // ✅ SET error flag
                        (itemView.parent?.parent as? RecyclerView)?.adapter?.let { adapter ->
                            if (adapter is EntryConsumptionDataAdapter) {
                                adapter.setValidationError(is3phField, true)
                            }
                        }

                        android.widget.Toast.makeText(
                            currentField.context,
                            "❌ Time 24:00 se zyada nahi ho sakta!",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()

                        return
                    }

                    // Parse other field value
                    val otherTimeStr = otherField.text.toString().trim()
                    val otherMinutes = if (otherTimeStr.isNotEmpty() && isValidTimeFormat(otherTimeStr)) {
                        parseTimeToMinutes(otherTimeStr)
                    } else {
                        0
                    }

                    // ✅ CRITICAL: Validate total <= 24:00 (1440 minutes)
                    val totalMinutes = currentMinutes + otherMinutes
                    if (totalMinutes > 1440) {
                        val totalTime = convertMinutesToTime(totalMinutes)
                        val current3ph = if (currentField.id == etSupply3ph.id) timeStr else otherTimeStr
                        val current1ph = if (currentField.id == etSupply1ph.id) timeStr else otherTimeStr

                        currentField.error = "Total > 24:00 exceeds!"
                        otherField.error = "Total > 24:00 exceeds!"
                        onTimeChanged(timeStr)  // ✅ FIX: value preserve karo, error flag block karega submit

                        // ✅ SET error flag for current field
                        (itemView.parent?.parent as? RecyclerView)?.adapter?.let { adapter ->
                            if (adapter is EntryConsumptionDataAdapter) {
                                adapter.setValidationError(is3phField, true)
                            }
                        }

                        android.widget.Toast.makeText(
                            currentField.context,
                            "❌ Total exceeds 24:00!\n3PH=$current3ph + 1PH=$current1ph = $totalTime",
                            android.widget.Toast.LENGTH_LONG
                        ).show()

                        return
                    }

                    // ✅ All validations passed - clear errors, clear flags, update data
                    currentField.error = null
                    otherField.error = null

                    // ✅ CLEAR error flag when valid
                    (itemView.parent?.parent as? RecyclerView)?.adapter?.let { adapter ->
                        if (adapter is EntryConsumptionDataAdapter) {
                            adapter.setValidationError(is3phField, false)
                        }
                    }

                    onTimeChanged(timeStr)  // ✅ Save valid data
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }


        /**
         * ✅ FIXED: Validate HH:MM format - STRICT VALIDATION
         * - Hours: 00-24
         * - Minutes: 00-59 (NOT 60-99!)
         * - Special: 24:00 allowed, 24:01+ NOT allowed
         */
        private fun isValidTimeFormat(time: String): Boolean {
            // ✅ CRITICAL: Use [0-5][0-9] to allow only 00-59 for minutes
            if (!time.matches(Regex("^\\d{1,2}:[0-5][0-9]$"))) return false

            val parts = time.split(":")
            val hours = parts[0].toIntOrNull() ?: return false
            val minutes = parts[1].toIntOrNull() ?: return false

            // ✅ Additional validation:
            // - Hours: 0-24
            // - Minutes: 0-59
            // - Exception: 24:00 is valid, 24:01+ is invalid
            return hours in 0..24 && minutes in 0..59 && !(hours == 24 && minutes > 0)
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
         * ✅ Convert minutes to HH:MM
         */
        private fun convertMinutesToTime(totalMinutes: Int): String {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return String.format("%02d:%02d", hours, minutes)
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