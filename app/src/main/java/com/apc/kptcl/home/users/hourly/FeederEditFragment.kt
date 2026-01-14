package com.apc.kptcl.home.users.hourly

import android.app.AlertDialog
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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentFeederEditBinding
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

class FeederEditFragment : Fragment() {

    private var _binding: FragmentFeederEditBinding? = null
    private val binding get() = _binding!!

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private lateinit var adapter: HourlyEditAdapter

    private var stationName: String = ""
    private val allFeeders = mutableListOf<FeederData>()
    private var selectedFeeder: FeederData? = null

    private val parameters = listOf("IB", "IR", "IY", "MW", "MVAR")
    private val numberOfHours = 24

    companion object {
        private const val TAG = "FeederEdit"
        private const val FEEDER_LIST_URL = "http://62.72.59.119:5000/api/feeder/list"
        private const val FETCH_URL = "http://62.72.59.119:4007/api/feeder/hourly/edit/fetch"
        private const val SAVE_URL = "http://62.72.59.119:4007/api/feeder/hourly/edit/save"
        private const val TIMEOUT = 15000
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeederEditBinding.inflate(inflater, container, false)
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

            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        // ✅ FUTURE DATES DISABLE - Present date se aage select nahi hoga
        datePickerDialog.datePicker.maxDate = System.currentTimeMillis()

        datePickerDialog.show()
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

        val feederAdapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item_black,
            feederDisplayList
        )

        feederAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_black)

        binding.actvStation.adapter = feederAdapter

        binding.actvStation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < allFeeders.size) {
                    selectedFeeder = allFeeders[position]
                    Log.d(TAG, "✅ Selected: ${selectedFeeder?.feederName}")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerView() {
        // ✅ VERTICAL layout like Entry Fragment
        adapter = HourlyEditAdapter()
        binding.rvFeederData.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            this.adapter = this@FeederEditFragment.adapter
        }
    }

    private fun setupButtons() {
        binding.btnSearch.setOnClickListener {
            searchData()
        }

        binding.btnSubmitChanges.setOnClickListener {
            submitChanges()
        }

    }

    private fun searchData() {
        if (selectedFeeder == null) {
            Toast.makeText(context, "Please select a feeder", Toast.LENGTH_SHORT).show()
            return
        }

        val feeder = selectedFeeder!!
        val selectedDate = dateFormat.format(calendar.time)

        Log.d(TAG, "🔍 Searching data for: $selectedDate, ${feeder.feederCode}")

        showLoading(true)

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    fetchExistingData(selectedDate, feeder.feederCode)
                }

                if (result.success) {
                    if (result.mode == "EDIT") {
                        Log.d(TAG, "✅ Found existing data: ${result.data.size} parameters")
                        loadExistingData(result.data, feeder)
                    } else {
                        Log.d(TAG, "ℹ️ No existing data - showing empty form")
                        loadEmptyData(feeder)
                    }
                } else {
                    showError("Failed to fetch data")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error searching", e)
                showError("Error: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun fetchExistingData(date: String, feederCode: String): FetchResult =
        withContext(Dispatchers.IO) {
            val token = SessionManager.getToken(requireContext())
            val url = URL(FETCH_URL)
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

                val requestBody = JSONObject().apply {
                    put("date", date)
                    put("feeder_code", feederCode)
                }

                Log.d(TAG, "📤 Fetch Request:\n${requestBody.toString(2)}")

                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                val response = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }

                Log.d(TAG, "📥 Fetch Response: $response")

                val jsonResponse = JSONObject(response)
                val success = jsonResponse.optBoolean("success", false)
                val mode = jsonResponse.optString("mode", "NEW")
                val dataArray = jsonResponse.optJSONArray("data")

                val existingData = mutableListOf<ExistingHourlyData>()

                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val item = dataArray.getJSONObject(i)
                        val parameter = item.optString("PARAMETER", "")
                        val hours = mutableMapOf<String, String>()

                        for (h in 0 until 24) {
                            val hourKey = String.format("%02d", h)
                            val value = item.opt(hourKey)
                            if (value != null && value.toString().isNotEmpty() && value.toString() != "null") {
                                hours[hourKey] = value.toString()
                            }
                        }

                        existingData.add(
                            ExistingHourlyData(
                                parameter = parameter,
                                hours = hours
                            )
                        )
                    }
                }

                FetchResult(success, mode, existingData)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Fetch Error", e)
                FetchResult(false, "ERROR", emptyList())
            } finally {
                connection.disconnect()
            }
        }

    private fun loadExistingData(existingData: List<ExistingHourlyData>, feeder: FeederData) {
        // ✅ Create ROW data for each HOUR (like Entry Fragment)
        val rows = mutableListOf<HourlyEditRow>()

        for (hour in 0 until numberOfHours) {
            val hourStr = String.format("%02d", hour)
            val parameterValues = mutableMapOf<String, String>()

            // Get values for each parameter at this hour
            parameters.forEach { param ->
                val existingRow = existingData.find { it.parameter == param }
                parameterValues[param] = existingRow?.hours?.get(hourStr) ?: ""
            }

            rows.add(
                HourlyEditRow(
                    hour = hourStr,
                    parameters = parameterValues
                )
            )
        }

        adapter.submitList(rows, feeder.feederName, feeder.feederCode)
    }

    private fun loadEmptyData(feeder: FeederData) {
        val rows = mutableListOf<HourlyEditRow>()

        for (hour in 0 until numberOfHours) {
            val hourStr = String.format("%02d", hour)
            val parameterValues = mutableMapOf<String, String>()

            parameters.forEach { param ->
                parameterValues[param] = ""
            }

            rows.add(
                HourlyEditRow(
                    hour = hourStr,
                    parameters = parameterValues
                )
            )
        }

        adapter.submitList(rows, feeder.feederName, feeder.feederCode)
    }

    private fun submitChanges() {
        if (selectedFeeder == null) {
            Toast.makeText(context, "Please select a feeder first", Toast.LENGTH_SHORT).show()
            return
        }

        val rows = adapter.getHourlyData()
        if (rows.isEmpty()) {
            Toast.makeText(context, "No data to submit", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Confirm Update")
            .setMessage("Update hourly data for ${selectedFeeder?.feederName}?")
            .setPositiveButton("Update") { _, _ ->
                performSubmit(rows)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performSubmit(rows: List<HourlyEditRow>) {
        val selectedDate = dateFormat.format(calendar.time)
        val token = SessionManager.getToken(requireContext())

        Log.d(TAG, "🚀 Submitting updates...")
        showLoading(true)

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    submitToAPI(token, selectedDate, selectedFeeder!!.feederCode, rows)
                }

                if (result.success) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Success")
                        .setMessage("✓ Hourly data updated!\n\nFeeder: ${selectedFeeder?.feederName}\nDate: $selectedDate")
                        .setPositiveButton("OK") { _, _ ->
                            selectedFeeder = null
                            adapter.clearData()

                            findNavController().popBackStack()
                        }
                        .show()
                } else {
                    showError("Update failed: ${result.message}")
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
        rows: List<HourlyEditRow>
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
                    put("parameter", param)

                    val hoursObject = JSONObject()

                    rows.forEach { row ->
                        val hourKey = row.hour
                        val value = row.parameters[param]
                        if (!value.isNullOrEmpty()) {
                            hoursObject.put(hourKey, value.toDoubleOrNull() ?: 0.0)
                        }
                    }

                    put("hours", hoursObject)
                }
                rowsArray.put(rowObject)
            }

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
        binding.btnSearch.isEnabled = !show
        binding.btnSubmitChanges.isEnabled = !show
        binding.btnSearch.text = if (show) "Searching..." else "SEARCH"
        binding.btnSubmitChanges.text = if (show) "Updating..." else "SUBMIT CHANGES"
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

// ✅ Data Classes




data class FetchResult(
    val success: Boolean,
    val mode: String,
    val data: List<ExistingHourlyData>
)

data class ExistingHourlyData(
    val parameter: String,
    val hours: Map<String, String>
)

// ✅ Changed from HourlyEditColumn to HourlyEditRow (like Entry Fragment)
data class HourlyEditRow(
    val hour: String,
    val parameters: MutableMap<String, String>
)



// ✅ RecyclerView Adapter - ROW BASED (like Entry Fragment)
class HourlyEditAdapter : RecyclerView.Adapter<HourlyEditAdapter.ViewHolder>() {

    private val rows = mutableListOf<HourlyEditRow>()
    private var feederName: String = ""
    private var feederCode: String = ""

    fun submitList(list: List<HourlyEditRow>, name: String, code: String) {
        rows.clear()
        rows.addAll(list)
        feederName = name
        feederCode = code
        notifyDataSetChanged()
    }

    fun getHourlyData(): List<HourlyEditRow> = rows

    fun clearData() {
        rows.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_feeder_hourly_row, parent, false) // ✅ Use same layout as Entry
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

        private lateinit var currentRow: HourlyEditRow

        fun bind(row: HourlyEditRow) {
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

            // Set current value
            editText.setText(currentRow.parameters[parameterName] ?: "")

            // Add new watcher
            val watcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    currentRow.parameters[parameterName] = s.toString()
                    Log.d("HourlyEdit", "Hour ${currentRow.hour} - $parameterName = '${s.toString()}'")
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }

            editText.addTextChangedListener(watcher)
            editText.tag = watcher
        }
    }
}