
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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentDailyParameterEntryBinding
import com.apc.kptcl.databinding.ItemDailyParameterVerticalBinding
import com.apc.kptcl.home.adapter.DailyDataAdapter
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

class DailyParameterViewFragment : Fragment() {

    private var _binding: FragmentDailyParameterEntryBinding? = null
    private val binding get() = _binding!!

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private lateinit var adapter: DailyDataAdapter
    private var selectedFeederId: String? = null
    private var selectedFeederName: String? = null

    // Store all data from API
    private var allConsumptionData = listOf<DailyConsumptionData>()

    // Store feeder list from API
    private var feederList = listOf<FeederItem>()

    companion object {
        private const val TAG = "DailyParameterView"
        private const val API_BASE_URL = "http://62.72.59.119"
        private const val FEEDER_LIST_URL = "$API_BASE_URL:5000/api/feeder/list"
        private const val CONSUMPTION_URL = "$API_BASE_URL:4000/api/feeder/consumption"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDailyParameterEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDatePicker()
        setupRecyclerView()
        setupButtons()
        updateDateDisplay()

        // Check if user is logged in and has token
        if (!SessionManager.isLoggedIn(requireContext())) {
            Snackbar.make(
                binding.root,
                "Please login first",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        // Auto load feeder list on fragment load
        loadFeederList()
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
    }

    private fun setupFeederDropdown() {
        if (feederList.isEmpty()) {
            Log.w(TAG, "Feeder list is empty, cannot setup dropdown")
            return
        }

        // Create display list with feeder name and code
        val displayList = feederList.map { "${it.feederName} (${it.feederCode})" }

        Log.d(TAG, "Setting up dropdown with ${displayList.size} items")
        displayList.forEachIndexed { index, item ->
            Log.d(TAG, "Dropdown item $index: $item")
        }

        val feederAdapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item_black,
            displayList
        )

        feederAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_black)

        binding.actvStation.adapter = feederAdapter

        binding.actvStation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedFeeder = feederList[position]
                selectedFeederId = selectedFeeder.feederId
                selectedFeederName = selectedFeeder.feederName

                Log.d(TAG, "Selected feeder: $selectedFeederName (ID: $selectedFeederId)")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                Log.d(TAG, "No feeder selected")
            }
        }

        Log.d(TAG, "Dropdown setup complete")
    }

    private fun setupRecyclerView() {
        adapter = DailyDataAdapter()
        binding.rvDailyData.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@DailyParameterViewFragment.adapter
        }
    }

    private fun setupButtons() {
        binding.btnSearch.setOnClickListener {
            searchData()
        }
    }

    /**
     * Step 1: Load feeder list from API
     */
    private fun loadFeederList() {
        binding.btnSearch.isEnabled = false
        binding.btnSearch.text = "Loading Feeders..."

        lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                if (token.isEmpty()) {
                    throw Exception("No authentication token found. Please login again.")
                }

                Log.d(TAG, "Fetching feeder list with token")

                // Fetch on IO thread
                val fetchedList = withContext(Dispatchers.IO) {
                    fetchFeederList(token)
                }

                // Update UI on Main thread
                feederList = fetchedList

                Log.d(TAG, "Loaded ${feederList.size} feeders from API")

                // Setup dropdown with fetched data (on Main thread)
                setupFeederDropdown()

                Snackbar.make(
                    binding.root,
                    "Loaded ${feederList.size} feeders. Select one to view data.",
                    Snackbar.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error loading feeders: ${e.message}", e)
                Snackbar.make(
                    binding.root,
                    "Error loading feeders: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            } finally {
                binding.btnSearch.isEnabled = true
                binding.btnSearch.text = "SEARCH"
            }
        }
    }

    /**
     * Step 2: Search/load consumption data for selected feeder
     */
    private fun searchData() {
        if (selectedFeederId == null) {
            Snackbar.make(
                binding.root,
                "Please select a feeder first",
                Snackbar.LENGTH_SHORT
            ).show()
            return
        }

        binding.btnSearch.isEnabled = false
        binding.btnSearch.text = "Loading Data..."

        lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                if (token.isEmpty()) {
                    throw Exception("No authentication token found. Please login again.")
                }

                val selectedDate = dateFormat.format(calendar.time)

                Log.d(TAG, "🔍 Searching consumption data for feeder: $selectedFeederId on date: $selectedDate")

                // Fetch consumption data
                val consumptionData = withContext(Dispatchers.IO) {
                    fetchConsumptionData(token, selectedFeederId!!, selectedDate)
                }

                // Store for later use
                allConsumptionData = consumptionData

                Log.d(TAG, "📥 Received ${consumptionData.size} consumption records from API")

                if (consumptionData.isEmpty()) {
                    Snackbar.make(
                        binding.root,
                        "No data found for selected feeder and date",
                        Snackbar.LENGTH_SHORT
                    ).show()

                    // Clear existing data
                    adapter.submitList(emptyList())
                } else {
                    // GET CATEGORY FROM FEEDER LIST IF API DOESN'T PROVIDE IT
                    val selectedFeederCategory = feederList.find {
                        it.feederId == selectedFeederId
                    }?.feederCategory ?: ""

                    Log.d(TAG, "🏷️ Selected feeder category from feeder list: '$selectedFeederCategory'")

                    // Convert to display rows
                    val displayRows = consumptionData.map { data ->
                        // Use category from API if available, otherwise use from feeder list
                        val finalCategory = if (data.feederCategory.isNotEmpty()) {
                            Log.d(TAG, "  ✅ Feeder '${data.feederName}' - Using category from API: '${data.feederCategory}'")
                            data.feederCategory
                        } else {
                            Log.d(TAG, "  ⚠️ Feeder '${data.feederName}' - API category empty, using from feeder list: '$selectedFeederCategory'")
                            selectedFeederCategory
                        }

                        DailyDataRow(
                            feederName = data.feederName,
                            feederCode = data.feederCode,
                            feederCategory = finalCategory,
                            remark = data.remark,
                            totalConsumption = data.totalConsumption,
                            supply3PH = data.supply3ph,
                            supply1PH = data.supply1ph
                        )
                    }

                    // LOG FINAL DISPLAY ROWS
                    Log.d(TAG, "📋 Final display rows being sent to adapter:")
                    displayRows.forEachIndexed { index, row ->
                        Log.d(TAG, "  Row $index - Feeder: '${row.feederName}', Category: '${row.feederCategory}', Code: '${row.feederCode}'")
                    }

                    // Update adapter
                    adapter.submitList(displayRows)

                    Snackbar.make(
                        binding.root,
                        "Loaded ${displayRows.size} records",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading consumption data", e)
                Snackbar.make(
                    binding.root,
                    "Error: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            } finally {
                binding.btnSearch.isEnabled = true
                binding.btnSearch.text = "SEARCH"
            }
        }
    }

    /**
     * Fetch feeder list from API
     */
    private suspend fun fetchFeederList(token: String): List<FeederItem> = withContext(Dispatchers.IO) {
        val url = URL(FEEDER_LIST_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.doInput = true

            val responseCode = connection.responseCode
            Log.d(TAG, "Feeder list API response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                Log.d(TAG, "Feeder list response: ${response.take(200)}...")
                parseFeederList(response)
            } else {
                val errorStream = connection.errorStream
                val errorMessage = if (errorStream != null) {
                    val errorReader = BufferedReader(InputStreamReader(errorStream))
                    errorReader.readText()
                } else {
                    "HTTP Error: $responseCode"
                }
                Log.e(TAG, "Feeder list error: $errorMessage")
                throw Exception(errorMessage)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parse feeder list JSON response
     */
    private fun parseFeederList(jsonString: String): List<FeederItem> {
        val list = mutableListOf<FeederItem>()
        val jsonObject = JSONObject(jsonString)

        val success = jsonObject.optBoolean("success", false)
        if (!success) {
            throw Exception(jsonObject.optString("message", "Failed to fetch feeder list"))
        }

        val dataArray = jsonObject.getJSONArray("data")

        for (i in 0 until dataArray.length()) {
            val item = dataArray.getJSONObject(i)
            val feederId = item.optString("FEEDER_CODE", "")
            val feederName = item.optString("FEEDER_NAME", "")
            val feederCode = item.optString("FEEDER_CODE", "")

            list.add(
                FeederItem(
                    feederId = feederId,
                    feederName = feederName,
                    feederCode = feederCode,
                    feederCategory = item.optString("FEEDER_CATEGORY", "")
            )
            )
            Log.d(TAG, "Added feeder: $feederName ($feederCode)")
        }

        Log.d(TAG, "Parsed ${list.size} feeders")
        return list
    }

    /**
     * Fetch consumption data for specific feeder and date
     */
    private suspend fun fetchConsumptionData(
        token: String,
        feederId: String,
        date: String
    ): List<DailyConsumptionData> = withContext(Dispatchers.IO) {
        val url = URL(CONSUMPTION_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.doOutput = true
            connection.doInput = true

            // Create JSON body
            val jsonBody = JSONObject().apply {
                put("feeder_id", feederId)
                put("date", date)
            }

            Log.d(TAG, "Consumption API request: $jsonBody")

            // Send request
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(jsonBody.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            Log.d(TAG, "Consumption API response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                Log.d(TAG, "Consumption response: ${response.take(200)}...")
                parseConsumptionData(response)
            } else {
                val errorStream = connection.errorStream
                val errorMessage = if (errorStream != null) {
                    val errorReader = BufferedReader(InputStreamReader(errorStream))
                    errorReader.readText()
                } else {
                    "HTTP Error: $responseCode"
                }
                Log.e(TAG, "Consumption error: $errorMessage")
                throw Exception(errorMessage)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parse consumption data JSON response
     */
    /**
     * Parse consumption data JSON response
     */
    /**
     * Parse consumption data JSON response
     */
    private fun parseConsumptionData(jsonString: String): List<DailyConsumptionData> {
        val list = mutableListOf<DailyConsumptionData>()
        val jsonObject = JSONObject(jsonString)

        val success = jsonObject.optBoolean("success", false)
        if (!success) {
            throw Exception(jsonObject.optString("message", "Failed to fetch consumption data"))
        }

        val dataArray = jsonObject.getJSONArray("data")

        Log.d(TAG, "📊 Parsing ${dataArray.length()} consumption records from API")

        for (i in 0 until dataArray.length()) {
            val item = dataArray.getJSONObject(i)
            val category = item.optString("FEEDER_CATEGORY", "")
            val feederName = item.optString("FEEDER_NAME", "")

            // LOG EACH ROW
            Log.d(TAG, "  Row $i - Feeder: '$feederName', Category: '$category'")

            list.add(
                DailyConsumptionData(
                    id = item.optString("ID", ""),
                    date = item.optString("DATE", ""),
                    stationName = item.optString("STATION_NAME", ""),
                    feederName = feederName,
                    feederCode = item.optString("FEEDER_CODE", ""),
                    feederCategory = category,
                    remark = item.optString("REMARK", ""),
                    totalConsumption = if (item.isNull("TOTAL_CONSUMPTION")) "" else item.optString("TOTAL_CONSUMPTION", ""),
                    supply3ph = if (item.isNull("SUPPLY_3PH")) "" else item.optString("SUPPLY_3PH", ""),
                    supply1ph = if (item.isNull("SUPPLY_1PH")) "" else item.optString("SUPPLY_1PH", "")
                )
            )
        }

        Log.d(TAG, "✅ Parsed ${list.size} consumption records")
        return list
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * Data class for feeder list item
 */
data class FeederItem(
    val feederId: String,
    val feederName: String,
    val feederCode: String,
    val feederCategory: String
)

/**
 * Data class for consumption data
 */
data class DailyConsumptionData(
    val id: String,
    val date: String,
    val stationName: String,
    val feederName: String,
    val feederCode: String,
    val feederCategory: String,
    val remark: String,
    var totalConsumption: String,
    var supply3ph: String,
    var supply1ph: String
)

/**
 * Data class for display row
 */
data class DailyDataRow(
    val feederName: String,
    var feederCode: String,
    var feederCategory: String,
    var remark: String,
    var totalConsumption: String,
    var supply3PH: String,
    var supply1PH: String
)