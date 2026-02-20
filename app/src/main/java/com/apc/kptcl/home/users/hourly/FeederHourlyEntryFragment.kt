package com.apc.kptcl.home.users.hourly

import android.app.DatePickerDialog
import android.graphics.Color
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
import com.apc.kptcl.utils.ApiErrorHandler

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
        private const val FEEDER_LIST_URL = "http://62.72.59.119:9009/api/feeder/list"
        private const val FETCH_URL = "http://62.72.59.119:9009/api/feeder/hourly"
        private const val SAVE_URL = "http://62.72.59.119:9009/api/feeder/hourly-entry/save"
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
                Snackbar.make(binding.root, ApiErrorHandler.handle(e), Snackbar.LENGTH_LONG).show()
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
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                parseFeedersResponse(response)
            } else {
                // ✅ FIXED: Was "throw Exception("Failed: $responseCode")"
                val errorMsg = ApiErrorHandler.fromErrorStream(connection.errorStream, responseCode)
                throw Exception(errorMsg)
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

        station = jsonObject.optString("username", "")
        val dataArray = jsonObject.optJSONArray("data")

        if (dataArray != null) {
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)

                // ✅ FIXED: Properly handle null FEEDER_CODE
                val feederCode = if (item.isNull("FEEDER_CODE")) {
                    null
                } else {
                    val code = item.optString("FEEDER_CODE", "")
                    if (code.isEmpty()) null else code
                }

                val feederName = item.optString("FEEDER_NAME", "")
                val feederCategory = item.optString("FEEDER_CATEGORY", "")

                feeders.add(
                    FeederData(
                        feederCode = feederCode,  // ✅ Now nullable
                        feederName = feederName,
                        feederCategory = feederCategory
                    )
                )

                Log.d(TAG, "Added feeder: $feederName (${feederCode ?: "NO CODE"})")
            }
        }

        return FeedersResponse(station, feeders)
    }

    private fun setupFeederDropdown() {
        if (allFeeders.isEmpty()) return

        // ✅ FIXED: Show code if available, else show "NO CODE"
        val feederDisplayList = allFeeders.map {
            "${it.feederName} (${it.feederCode ?: "NO CODE"})"
        }

        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item_black,
            feederDisplayList
        )
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_black)

        binding.actvFeeder.adapter = adapter

        // ✅ Default select first feeder
        if (allFeeders.isNotEmpty()) {
            selectedFeeder = allFeeders[0]
            binding.actvFeeder.setSelection(0)
            Log.d(TAG, "✅ Default selected: ${selectedFeeder?.feederName} (${selectedFeeder?.feederCode ?: "NO CODE"})")

            // ✅ Load data for first feeder automatically
            loadFeederData()
        }

        binding.actvFeeder.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < allFeeders.size) {
                    selectedFeeder = allFeeders[position]
                    Log.d(TAG, "🔹 Selected: ${selectedFeeder?.feederName} (${selectedFeeder?.feederCode ?: "NO CODE"})")
                    loadFeederData()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedFeeder = null
                HourlyDataAdapter().clearData()
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

        Log.d(TAG, "📄 Loading data for ${feeder.feederName} (Code: ${feeder.feederCode ?: "NONE"}) on $date")

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // ✅ FIXED: Pass both code and name
                    fetchFeederData(date, feeder.feederCode, feeder.feederName)
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

                    // Compute max allowed hour for this date
                    val maxAllowedHour = computeMaxAllowedHour(date)

                    // Create rows for all 24 hours (00-23)
                    for (hour in 0..23) {
                        val hourKey = String.format("%02d", hour)
                        rowList.add(
                            HourlyDataRow(
                                hour = hourKey,
                                parameters = hourMap[hourKey] ?: mutableMapOf(),
                                isLocked = hour > maxAllowedHour
                            )
                        )
                    }
                } else {
                    // New mode - empty rows
                    val maxAllowedHour = computeMaxAllowedHour(date)
                    for (hour in 0..23) {
                        rowList.add(
                            HourlyDataRow(
                                hour = String.format("%02d", hour),
                                parameters = mutableMapOf(),
                                isLocked = hour > maxAllowedHour
                            )
                        )
                    }
                }

                // ✅ FIXED: Pass nullable code
                adapter.submitList(rowList, feeder.feederName, feeder.feederCode)
                Toast.makeText(context, "Data loaded", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading data", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ✅ FIXED: Accept nullable feederCode and feederName
    private suspend fun fetchFeederData(
        date: String,
        feederCode: String?,
        feederName: String
    ): List<ExistingHourlyData> = withContext(Dispatchers.IO) {
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
                feederCode?.let { put("feeder_id", it) }
                put("feeder_name", feederName)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
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
                            val value = item.optString(key, "")
                            if (value.isNotEmpty()) hoursMap[key] = value
                        }
                    }
                    result.add(ExistingHourlyData(parameter, hoursMap))
                }
                result
            } else {
                // ✅ FIXED: Was silently returning emptyList() and logging raw error
                // Now we still return empty list (no existing data = new entry mode)
                // but log properly without exposing IP
                Log.w(TAG, "No existing hourly data found (HTTP $responseCode)")
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

        Log.d(TAG, "💾 Submitting ${rows.size} rows for ${feeder.feederName} (Code: ${feeder.feederCode ?: "NONE"})")

        lifecycleScope.launch {
            try {
                showLoading(true)

                val result = withContext(Dispatchers.IO) {
                    // ✅ FIXED: Pass both code and name
                    saveData(date, feeder.feederCode, feeder.feederName, rows)
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
                showError(ApiErrorHandler.handle(e))

            } finally {
                showLoading(false)
            }
        }
    }

    // ✅ FIXED: Accept nullable feederCode and feederName
    private suspend fun saveData(
        date: String,
        feederCode: String?,
        feederName: String,
        rows: List<HourlyDataRow>
    ): SaveResult = withContext(Dispatchers.IO) {
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
                for (row in rows.filter { !it.isLocked }) {
                    val value = row.parameters[parameter] ?: ""
                    if (value.isNotEmpty() && value != "null") {
                        hoursObject.put(row.hour, value)
                    }
                }
                rowsArray.put(JSONObject().apply {
                    put("date", date)
                    feederCode?.let { put("feeder_code", it) }
                    put("feeder_name", feederName)
                    put("parameter", parameter)
                    put("hours", hoursObject)
                })
            }

            val requestBody = JSONObject().put("rows", rowsArray)

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val jsonResponse = JSONObject(response)
                SaveResult(
                    jsonResponse.optBoolean("success", false),
                    jsonResponse.optString("message", "")
                )
            } else {
                // ✅ FIXED: Was "Server error: $responseCode - $errorMessage" (leaked raw JSON + code)
                val errorMsg = ApiErrorHandler.fromErrorStream(connection.errorStream, responseCode)
                SaveResult(false, errorMsg)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun showLoading(show: Boolean) {
        binding.btnSubmit.isEnabled = !show
        binding.btnSubmit.text = if (show) "Submitting..." else "SUBMIT"
    }

    /**
     * Past date  → maxAllowedHour = 23  (sab 24 hours editable)
     * Aaj ki date → maxAllowedHour = currentHour - 1
     *   e.g. abhi 15:30 baj rahe → hours 00-14 editable, 15-23 locked
     *   abhi 00:xx baj rahe    → maxAllowedHour = -1 → sab locked
     */
    private fun computeMaxAllowedHour(dateStr: String): Int {
        val today = dateFormat.format(Calendar.getInstance().time)
        return if (dateStr < today) {
            23  // Past date: sab allowed
        } else {
            // Aaj ya future (shouldn't be future due to date picker restriction)
            Calendar.getInstance().get(Calendar.HOUR_OF_DAY) - 1
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

data class FeedersResponse(
    val station: String,
    val feeders: List<FeederData>
)

// ✅ FIXED: Made feederCode nullable
data class FeederData(
    val feederCode: String?,  // ✅ Now nullable
    val feederName: String,
    val feederCategory: String
)

data class HourlyDataRow(
    val hour: String,  // "00" to "23" - database format
    val parameters: MutableMap<String, String>,
    val isLocked: Boolean = false  // true = future/current hour, entry not allowed
)

data class ExistingHourlyData(
    val parameter: String,
    val hours: Map<String, String>
)

data class SaveResult(
    val success: Boolean,
    val message: String
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
    private var feederCode: String? = null  // ✅ Made nullable

    // ✅ FIXED: Accept nullable code
    fun submitList(list: List<HourlyDataRow>, name: String, code: String?) {
        rows.clear()
        rows.addAll(list)
        feederName = name
        feederCode = code
        notifyDataSetChanged()

        Log.d("HourlyDataAdapter", "✅ Submitted ${list.size} rows for $name (Code: ${code ?: "NONE"})")
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
    // XML: item_feeder_hourly_row.xml
    // IDs: tvHour, etIR, etIY, etIB, etMW, etMVAR
    // Drawables: cell_border (tvHour), table_cell_border_editable (EditTexts)
    class DataViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvHour: TextView = itemView.findViewById(R.id.tvHour)
        private val etIR:   EditText = itemView.findViewById(R.id.etIR)
        private val etIY:   EditText = itemView.findViewById(R.id.etIY)
        private val etIB:   EditText = itemView.findViewById(R.id.etIB)
        private val etMW:   EditText = itemView.findViewById(R.id.etMW)
        private val etMVAR: EditText = itemView.findViewById(R.id.etMVAR)

        private lateinit var currentRow: HourlyDataRow

        fun bind(row: HourlyDataRow) {
            currentRow = row

            val dbHourInt = row.hour.toInt()
            val displayHour = dbHourInt + 1
            tvHour.text = "$displayHour:00"

            if (row.isLocked) {
                // ── LOCKED: future / current hour — grey, not editable ──
                itemView.setBackgroundColor(Color.parseColor("#F0F0F0"))
                tvHour.setTextColor(Color.parseColor("#AAAAAA"))
                tvHour.setBackgroundColor(Color.parseColor("#E8E8E8"))

                listOf(etIR, etIY, etIB, etMW, etMVAR).forEach { et ->
                    (et.tag as? TextWatcher)?.let { et.removeTextChangedListener(it) }
                    et.setText("")
                    et.hint = "—"
                    et.isEnabled = false
                    et.isFocusable = false
                    et.setBackgroundColor(Color.parseColor("#E8E8E8"))
                    et.setTextColor(Color.parseColor("#AAAAAA"))
                    et.setHintTextColor(Color.parseColor("#BBBBBB"))
                }
            } else {
                // ── EDITABLE: original XML drawables restore karo ──
                itemView.setBackgroundColor(Color.TRANSPARENT)
                tvHour.setTextColor(Color.BLACK)
                tvHour.setBackgroundResource(R.drawable.cell_border)

                listOf(etIR, etIY, etIB, etMW, etMVAR).forEach { et ->
                    et.isEnabled = true
                    et.isFocusableInTouchMode = true
                    et.setTextColor(Color.BLACK)
                    et.setHintTextColor(Color.parseColor("#999999"))
                    et.setBackgroundResource(R.drawable.table_cell_border_editable)
                }

                // Parameter binding — XML ke IDs ke mutabiq
                setupParameterInput(etIR,   "IR")
                setupParameterInput(etIY,   "IY")
                setupParameterInput(etIB,   "IB")
                setupParameterInput(etMW,   "MW")
                setupParameterInput(etMVAR, "MVAR")
            }
        }

        private fun setupParameterInput(editText: EditText, parameterName: String) {
            // Old watcher hata do pehle
            (editText.tag as? TextWatcher)?.let { editText.removeTextChangedListener(it) }

            // Input type + filters — parameter ke hisaab se
            when (parameterName) {
                "IB", "IR", "IY" -> {
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    editText.filters   = arrayOf(IntegerInputFilter(allowNegative = false))
                }
                "MW" -> {
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    editText.filters   = arrayOf(DecimalInputFilter(allowNegative = false, maxDecimalPlaces = 8))
                }
                "MVAR" -> {
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    editText.filters   = arrayOf(DecimalInputFilter(allowNegative = true, maxDecimalPlaces = 8))
                }
            }

            // Existing value set karo (hint XML se aata hai, override mat karo)
            editText.setText(currentRow.parameters[parameterName] ?: "")

            // TextWatcher — data capture
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

// ============================================
// INPUT FILTERS
// ============================================

class IntegerInputFilter(private val allowNegative: Boolean) : InputFilter {
    override fun filter(
        source: CharSequence?,
        start: Int,
        end: Int,
        dest: Spanned?,
        dstart: Int,
        dend: Int
    ): CharSequence? {
        if (source == null || dest == null) return null

        val newText = dest.toString().substring(0, dstart) +
                source.toString().substring(start, end) +
                dest.toString().substring(dend)

        if (newText.isEmpty()) return null
        if (newText == "-" && allowNegative) return null

        return try {
            newText.toInt()
            null // Accept
        } catch (e: NumberFormatException) {
            "" // Reject
        }
    }
}

class DecimalInputFilter(
    private val allowNegative: Boolean,
    private val maxDecimalPlaces: Int = 8
) : InputFilter {
    override fun filter(
        source: CharSequence?,
        start: Int,
        end: Int,
        dest: Spanned?,
        dstart: Int,
        dend: Int
    ): CharSequence? {
        if (source == null || dest == null) return null

        val newText = dest.toString().substring(0, dstart) +
                source.toString().substring(start, end) +
                dest.toString().substring(dend)

        if (newText.isEmpty()) return null
        if (newText == "-" && allowNegative) return null
        if (newText == ".") return null

        // Check decimal places
        if (newText.contains(".")) {
            val parts = newText.split(".")
            if (parts.size > 2) return ""
            if (parts.size == 2 && parts[1].length > maxDecimalPlaces) return ""
        }

        return try {
            newText.toDouble()
            null // Accept
        } catch (e: NumberFormatException) {
            "" // Reject
        }
    }
}