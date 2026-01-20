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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentElogEntryBinding
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
class DailyEditFragment : Fragment() {

    private var _binding: FragmentElogEntryBinding? = null
    private val binding get() = _binding!!

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

    private lateinit var adapter: DailyEditAdapter

    private var stationName: String = ""
    private val allFeeders = mutableListOf<FeederData>()
    private var selectedFeeder: FeederData? = null

    companion object {
        private const val TAG = "DailyEdit"
        private const val FEEDER_LIST_URL = "http://62.72.59.119:7000/api/feeder/list"
        private const val FETCH_URL = "http://62.72.59.119:7000/api/feeder/consumption/edit/fetch"
        private const val SAVE_URL = "http://62.72.59.119:7000/api/feeder/consumption/edit/save"
        private const val TIMEOUT = 15000
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentElogEntryBinding.inflate(inflater, container, false)
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
        binding.etDate.setText(displayDateFormat.format(calendar.time))
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

        datePickerDialog.show()
    }

    private fun updateDateDisplay() {
        binding.etDate.setText(displayDateFormat.format(calendar.time))
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

        val feederDisplayList = allFeeders.map {
            "${it.feederName} (${it.feederCode})"
        }

        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item_black,
            feederDisplayList
        )
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_black)

        binding.actvStation.adapter = adapter

        binding.actvStation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < allFeeders.size) {
                    selectedFeeder = allFeeders[position]
                    Log.d(TAG, "✅ Selected: ${selectedFeeder?.feederName}")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                Log.d(TAG, "No feeder selected")
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = DailyEditAdapter()
        binding.rvElogData.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = this@DailyEditFragment.adapter
        }
    }

    private fun setupButtons() {
        binding.btnSearch.setOnClickListener {
            searchData()
        }

        binding.btnSubmitChanges.setOnClickListener {
            submitData()
        }

    }

    private fun searchData() {
        if (selectedFeeder == null) {
            Toast.makeText(context, "Please select a feeder", Toast.LENGTH_SHORT).show()
            return
        }

        val feeder = selectedFeeder!!
        val token = SessionManager.getToken(requireContext())

        if (token.isEmpty()) {
            Snackbar.make(binding.root, "Token missing", Snackbar.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "🔍 Searching data...")
        showLoading(true)

        lifecycleScope.launch {
            try {
                val selectedDate = dateFormat.format(calendar.time)

                val result = withContext(Dispatchers.IO) {
                    fetchExistingData(token, selectedDate, feeder.feederCode)
                }

                if (result.success) {
                    displayData(result.data, feeder)
                    Toast.makeText(context, "Data loaded successfully", Toast.LENGTH_SHORT).show()
                } else {
                    // No existing data - show empty form
                    displayData(null, feeder)
                    Toast.makeText(context, "No existing data. Ready for new entry.", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error", e)
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun fetchExistingData(
        token: String,
        date: String,
        feederCode: String
    ): FetchResult = withContext(Dispatchers.IO) {
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

            // Send request body as JSON
            val requestBody = JSONObject().apply {
                put("date", date)
                put("feeder_code", feederCode)
            }

            Log.d(TAG, "📤 Fetch Request:\n${requestBody.toString(2)}")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "📡 Fetch response: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }

                Log.d(TAG, "📥 Fetch Response: $response")

                val jsonObject = JSONObject(response)
                val success = jsonObject.optBoolean("success", false)

                if (success) {
                    val mode = jsonObject.optString("mode", "NEW")
                    val dataArray = jsonObject.optJSONArray("data")

                    if (mode == "EDIT" && dataArray != null && dataArray.length() > 0) {
                        // Extract first row from array
                        val dataObject = dataArray.getJSONObject(0)

                        // EXTRACT CATEGORY - ye important hai
                        val category = dataObject.optString("FEEDER_CATEGORY", "")

                        Log.d(TAG, "📊 Fetched data - Category: '$category'")

                        val data = ExistingDailyData(
                            feederName = dataObject.optString("FEEDER_NAME", ""),
                            feederCode = dataObject.optString("FEEDER_CODE", ""),
                            feederCategory = category,  // ← Ye line check karo
                            totalConsumption = dataObject.optString("TOTAL_CONSUMPTION", ""),
                            supply3ph = dataObject.optString("SUPPLY_3PH", ""),
                            supply1ph = dataObject.optString("SUPPLY_1PH", ""),
                            remark = dataObject.optString("REMARK", "")
                        )
                        Log.d(TAG, "✅ Found existing data with category: '${data.feederCategory}'")
                        FetchResult(true, data)
                    } else {
                        // NEW mode - no existing data
                        Log.d(TAG, "ℹ️ No existing data - showing empty form")
                        FetchResult(false, null)
                    }
                } else {
                    FetchResult(false, null)
                }
            } else {
                FetchResult(false, null)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun displayData(data: ExistingDailyData?, feeder: FeederData) {
        if (data == null) {
            // Show empty form with feeder info
            Log.d(TAG, "📝 Displaying NEW form - Feeder: ${feeder.feederName}, Category: '${feeder.feederCategory}'")

            val column = DailyEditColumn(
                feederName = feeder.feederName,
                feederCode = feeder.feederCode,
                feederCategory = feeder.feederCategory,  // ← Use category from feeder list
                totalConsumption = "",
                supply3ph = "",
                supply1ph = "",
                remark = ""
            )
            adapter.submitList(listOf(column))
        } else {
            // Show existing data
            // If API doesn't return category, use from feeder list
            val finalCategory = if (data.feederCategory.isNotEmpty()) {
                Log.d(TAG, "✅ Using category from API: '${data.feederCategory}'")
                data.feederCategory
            } else {
                Log.d(TAG, "⚠️ API category empty, using from feeder: '${feeder.feederCategory}'")
                feeder.feederCategory
            }

            Log.d(TAG, "📝 Displaying EDIT form - Feeder: ${data.feederName}, Category: '$finalCategory'")

            val column = DailyEditColumn(
                feederName = data.feederName,
                feederCode = data.feederCode,
                feederCategory = finalCategory,  // ← Use final category
                totalConsumption = data.totalConsumption,
                supply3ph = data.supply3ph,
                supply1ph = data.supply1ph,
                remark = data.remark
            )
            adapter.submitList(listOf(column))
        }
    }

    private fun submitData() {
        if (selectedFeeder == null) {
            Toast.makeText(context, "Please select a feeder", Toast.LENGTH_SHORT).show()
            return
        }

        val columns = adapter.getColumns()
        if (columns.isEmpty()) {
            Toast.makeText(context, "No data to submit", Toast.LENGTH_SHORT).show()
            return
        }

        val column = columns[0]
        val token = SessionManager.getToken(requireContext())

        if (token.isEmpty()) {
            Snackbar.make(binding.root, "Token missing", Snackbar.LENGTH_LONG).show()
            return
        }

        // Validate
        if (column.totalConsumption.isEmpty()) {
            Toast.makeText(context, "Total consumption is required", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Confirm Update")
            .setMessage("Update daily consumption data?\n\nFeeder: ${column.feederName}\nTotal: ${column.totalConsumption} kWh")
            .setPositiveButton("Update") { _, _ ->
                submitToAPI(column)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun submitToAPI(column: DailyEditColumn) {
        val token = SessionManager.getToken(requireContext())

        Log.d(TAG, "🚀 Submitting update...")
        showLoading(true)

        lifecycleScope.launch {
            try {
                val selectedDate = dateFormat.format(calendar.time)

                val result = withContext(Dispatchers.IO) {
                    submitToSaveAPI(token, selectedDate, column)
                }

                if (result.success) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Success")
                        .setMessage("✓ Daily data updated!\n\nFeeder: ${column.feederName}\nDate: $selectedDate")
                        .setPositiveButton("OK") { _, _ ->
                            // Clear form
                            selectedFeeder = null
                            adapter.clearData()

                            // ✅ NAVIGATE TO HOMEPAGE
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

    private suspend fun submitToSaveAPI(
        token: String,
        date: String,
        column: DailyEditColumn
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

            val requestBody = JSONObject().apply {
                put("date", date)
                put("feeder_code", column.feederCode)
                put("total_consumption", column.totalConsumption.toDoubleOrNull() ?: 0.0)
                put("remark", column.remark)
            }

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



// RecyclerView Adapter

class DailyEditAdapter : RecyclerView.Adapter<DailyEditAdapter.ViewHolder>() {

    private val columns = mutableListOf<DailyEditColumn>()

    fun submitList(list: List<DailyEditColumn>) {
        columns.clear()
        columns.addAll(list)
        notifyDataSetChanged()
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
        private val etRemark: EditText = itemView.findViewById(R.id.etRemark)
        private val etTotal: EditText = itemView.findViewById(R.id.etTotal)
        private val spinnerSupply3ph: Spinner = itemView.findViewById(R.id.spinnerSupply3ph)
        private val spinnerSupply1ph: Spinner = itemView.findViewById(R.id.spinnerSupply1ph)

        private lateinit var currentColumn: DailyEditColumn

        fun bind(column: DailyEditColumn) {
            currentColumn = column

            // ADD THIS LOG
            Log.d("DailyEdit", "🔍 Binding column - Feeder: ${column.feederName}, Category: '${column.feederCategory}'")

            tvFeeder.text = column.feederName
            tvCode.text = column.feederCode
            tvCategory.text = column.feederCategory  // ← Ye line check karo

            etRemark.setText(column.remark)
            etTotal.setText(column.totalConsumption)

            // Setup time spinners
            setupTimeSpinners()

            // Set spinner selections
            setSpinnerSelection(spinnerSupply3ph, column.supply3ph)
            setSpinnerSelection(spinnerSupply1ph, column.supply1ph)

            setupTextWatcher(etRemark) { currentColumn.remark = it }
            setupTextWatcher(etTotal) { currentColumn.totalConsumption = it }

            // Spinner listeners
            setupSpinnerListener(spinnerSupply3ph) { currentColumn.supply3ph = it }
            setupSpinnerListener(spinnerSupply1ph) { currentColumn.supply1ph = it }
        }

        private fun setupTimeSpinners() {
            val timeOptions = mutableListOf<String>()
            timeOptions.add("--Select or type--")

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
                    // Set text color to black after selection
                    (view as? TextView)?.setTextColor(android.graphics.Color.BLACK)

                    if (position > 0) {
                        onSelected(parent?.getItemAtPosition(position).toString())
                    } else {
                        onSelected("")
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        private fun setupTextWatcher(editText: EditText, onTextChanged: (String) -> Unit) {
            editText.tag?.let { oldWatcher ->
                if (oldWatcher is TextWatcher) {
                    editText.removeTextChangedListener(oldWatcher)
                }
            }

            val watcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    onTextChanged(s.toString())
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }

            editText.addTextChangedListener(watcher)
            editText.tag = watcher
        }
    }
}