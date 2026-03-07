package com.apc.kptcl.home.users.dashboard

import android.graphics.Color
import android.os.Bundle
import android.text.*
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentEditMissingDataBinding
import com.apc.kptcl.utils.ApiErrorHandler
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

// ============================================================
// DATA MODELS
// ============================================================

data class HourlyRowData(
    val hour: String,                                          // "00".."23"
    val parameters: MutableMap<String, String> = mutableMapOf(),
    val isLocked: Boolean = false                              // NEW: future hour lock
)

data class DailyRowData(
    val feederName: String,
    val feederCode: String?,
    val feederCategory: String,
    var totalConsumption: String = "",
    var supply3ph: String = "",
    var supply1ph: String = "",
    var remark: String = "PROPER"
)

data class FeederHourlyState(
    val feederName: String,
    val feederCode: String?,
    val rows: MutableList<HourlyRowData>
)

// ============================================================
// HELPER: Compute max allowed hour for a given date
// Agar date == aaj  → current hour tak allow (hour "00" = 1:00)
// Agar date < aaj   → sab 24 hours allow (maxHour = 23)
// ============================================================
object HourLockHelper {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * Returns the maximum DB hour index (0–23) that is allowed to be edited.
     * -1 means all locked (shouldn't happen in normal flow).
     * 23 means all unlocked (past date).
     */
    fun maxAllowedHour(dateStr: String): Int {
        val today = dateFormat.format(Calendar.getInstance().time)
        return if (dateStr < today) {
            23  // Past date: all 24 hours editable
        } else {
            // Today: allow hours up to current hour
            // DB hour "00" = slot 1:00,  "01" = slot 2:00, etc.
            // Slot N:00 data should be entered AFTER N:00 has passed
            // So we allow hour index = currentHour - 1
            // e.g., current time = 14:30 → hours 00..13 allowed (i.e., slots 1:00..14:00)
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            currentHour - 1   // can be -1 at midnight → all locked, handle in adapter
        }
    }
}

// ============================================================
// FRAGMENT
// ============================================================

class EditMissingDataFragment : Fragment() {

    private var _binding: FragmentEditMissingDataBinding? = null
    private val binding get() = _binding!!

    private var date: String = ""
    private var missingHourlyFeeders: Array<String> = emptyArray()
    private var missingDailyFeeders: Array<String> = emptyArray()

    private val allFeeders = mutableListOf<FeederItem>()
    private val hourlyStates = mutableListOf<FeederHourlyState>()
    private val dailyRows = mutableListOf<DailyRowData>()
    private var selectedHourlyFeederIndex = 0

    private lateinit var hourlyAdapter: MissingHourlyAdapter
    private lateinit var dailyAdapter: MissingDailyAdapter

    companion object {
        private const val TAG = "EditMissing"
        private const val BASE_URL = "http://62.72.59.119:9009"
        private const val TIMEOUT = 15000
        private val PARAMETERS = listOf("IB", "IR", "IY", "MW", "MVAR")
        private val HOURS = (0..23).map { String.format("%02d", it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditMissingDataBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        date = arguments?.getString("date") ?: ""
        missingHourlyFeeders = arguments?.getStringArray("missing_hourly_feeders") ?: emptyArray()
        missingDailyFeeders = arguments?.getStringArray("missing_daily_feeders") ?: emptyArray()

        if (date.isEmpty()) {
            Toast.makeText(context, "Date missing", Toast.LENGTH_SHORT).show()
            // Cache invalidate karo taaki feeder status fresh load ho
            val vm = ViewModelProvider(requireActivity())[FeederStatusViewModel::class.java]
            vm.invalidate()

            findNavController().navigateUp()
            return
        }

        binding.tvEditDate.text = "Editing: $date"
        binding.tvMissingSummary.text = buildMissingSummary()

        setupHourlySection()
        setupDailySection()
        setupButtons()
        loadAllData()
    }

    private fun buildMissingSummary(): String {
        val parts = mutableListOf<String>()
        if (missingHourlyFeeders.isNotEmpty())
            parts.add("⚡ ${missingHourlyFeeders.size} hourly feeders incomplete")
        if (missingDailyFeeders.isNotEmpty())
            parts.add("📊 ${missingDailyFeeders.size} daily feeders incomplete")
        return if (parts.isEmpty()) "✅ No missing data" else parts.joinToString("\n")
    }

    // ============================================================
    // SETUP
    // ============================================================

    private fun setupHourlySection() {
        hourlyAdapter = MissingHourlyAdapter(
            onFirstEmptyFound = { recyclerView, position ->
                scrollToAndFocusHourly(recyclerView, position)
            }
        )
        binding.rvHourlyData.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = hourlyAdapter
        }
    }

    private fun setupDailySection() {
        dailyAdapter = MissingDailyAdapter(
            onFirstEmptyFound = { recyclerView, position ->
                scrollToAndFocusDaily(recyclerView, position)
            }
        )
        binding.rvDailyData.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = dailyAdapter
        }
    }

    private fun setupButtons() {
        binding.btnSubmitHourly.setOnClickListener { submitHourlyData() }
        binding.btnSubmitDaily.setOnClickListener { submitDailyData() }
        binding.btnSubmitAll.setOnClickListener { submitAll() }
    }

    // ============================================================
    // DATA LOADING
    // ============================================================

    private fun loadAllData() {
        val token = SessionManager.getToken(requireContext())
        showLoading(true)

        lifecycleScope.launch {
            try {
                allFeeders.clear()
                allFeeders.addAll(withContext(Dispatchers.IO) { fetchFeederList(token) })

                setupFeederTabs()

                hourlyStates.clear()
                for (feeder in allFeeders) {
                    val rows = withContext(Dispatchers.IO) { fetchHourlyData(token, date, feeder) }
                    hourlyStates.add(FeederHourlyState(feeder.feederName, feeder.feederCode, rows))
                }

                dailyRows.clear()
                for (feeder in allFeeders) {
                    dailyRows.add(withContext(Dispatchers.IO) { fetchDailyData(token, date, feeder) })
                }

                if (hourlyStates.isNotEmpty()) showHourlyForFeeder(0)

                dailyAdapter.submitList(dailyRows.toMutableList())

                binding.root.post { autoScrollToFirstEmpty() }

            } catch (e: Exception) {
                Log.e(TAG, "Load error", e)
                Snackbar.make(binding.root, ApiErrorHandler.handle(e), Snackbar.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun setupFeederTabs() {
        binding.feederTabLayout.removeAllViews()

        allFeeders.forEachIndexed { index, feeder ->
            val isMissingHourly = missingHourlyFeeders.contains(feeder.feederName)
            val tab = layoutInflater.inflate(R.layout.item_feeder_tab, binding.feederTabLayout, false)
            val tvTab = tab.findViewById<TextView>(R.id.tvTabName)

            tvTab.text = "${feeder.feederName}${if (isMissingHourly) " ⚠️" else " ✅"}"
            tvTab.setBackgroundColor(
                if (isMissingHourly) Color.parseColor("#FFF3E0")
                else Color.parseColor("#E8F5E9")
            )

            tab.setOnClickListener {
                selectedHourlyFeederIndex = index
                highlightTab(index)
                showHourlyForFeeder(index)
            }

            binding.feederTabLayout.addView(tab)
        }

        if (allFeeders.isNotEmpty()) highlightTab(0)
    }

    private fun highlightTab(selectedIndex: Int) {
        for (i in 0 until binding.feederTabLayout.childCount) {
            val child = binding.feederTabLayout.getChildAt(i)
            val tv = child.findViewById<TextView>(R.id.tvTabName)
            child.isSelected = (i == selectedIndex)
            tv?.setTextColor(
                if (i == selectedIndex) Color.WHITE else Color.parseColor("#333333")
            )
            child.alpha = if (i == selectedIndex) 1.0f else 0.6f
        }
    }

    private fun showHourlyForFeeder(index: Int) {
        if (index >= hourlyStates.size) return
        val state = hourlyStates[index]
        hourlyAdapter.submitList(state.rows)

        binding.rvHourlyData.post {
            val firstEmptyPos = findFirstEmptyHourlyRow(state.rows)
            if (firstEmptyPos >= 0) {
                binding.rvHourlyData.scrollToPosition(firstEmptyPos)
            }
        }
    }

    // Only count unlocked+empty rows as "first empty"
    private fun findFirstEmptyHourlyRow(rows: List<HourlyRowData>): Int {
        rows.forEachIndexed { index, row ->
            if (!row.isLocked && PARAMETERS.any { row.parameters[it].isNullOrEmpty() }) return index
        }
        return -1
    }

    private fun autoScrollToFirstEmpty() {
        val firstHourlyEmpty = findFirstEmptyInAllFeeders()
        val firstDailyEmpty = findFirstEmptyDailyRow()

        when {
            firstHourlyEmpty >= 0 -> {
                binding.nestedScrollView.smoothScrollTo(0, binding.tvHourlyTitle.top)
            }
            firstDailyEmpty >= 0 -> {
                binding.nestedScrollView.smoothScrollTo(0, binding.tvDailyTitle.top)
                binding.rvDailyData.post {
                    binding.rvDailyData.smoothScrollToPosition(firstDailyEmpty)
                }
            }
        }
    }

    private fun findFirstEmptyInAllFeeders(): Int {
        hourlyStates.forEachIndexed { feederIdx, state ->
            val emptyRow = findFirstEmptyHourlyRow(state.rows)
            if (emptyRow >= 0) {
                selectedHourlyFeederIndex = feederIdx
                highlightTab(feederIdx)
                showHourlyForFeeder(feederIdx)
                return emptyRow
            }
        }
        return -1
    }

    private fun findFirstEmptyDailyRow(): Int {
        dailyRows.forEachIndexed { index, row ->
            // Only treat truly empty string as incomplete; 0 / 0.0 are valid DB values
            if (row.totalConsumption.isEmpty()) return index
        }
        return -1
    }

    // ============================================================
    // NETWORK CALLS
    // ============================================================

    private fun fetchFeederList(token: String): List<FeederItem> {
        val url = URL("$BASE_URL/api/feeder/list")
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("Failed to fetch feeders: ${connection.responseCode}")
        }

        val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        val json = JSONObject(response)
        val data = json.optJSONArray("data") ?: return emptyList()
        val feeders = mutableListOf<FeederItem>()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val code = if (item.isNull("FEEDER_CODE")) null
            else item.optString("FEEDER_CODE", "").takeIf { it.isNotEmpty() }
            feeders.add(
                FeederItem(
                    feederCode = code,
                    feederName = item.optString("FEEDER_NAME", ""),
                    feederCategory = item.optString("FEEDER_CATEGORY", "")
                )
            )
        }
        return feeders
    }

    private fun fetchHourlyData(token: String, date: String, feeder: FeederItem): MutableList<HourlyRowData> {
        val url = URL("$BASE_URL/api/feeder/hourly")
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            doOutput = true
        }

        val body = JSONObject().apply {
            put("date", date)
            feeder.feederCode?.let { put("feeder_id", it) }
            put("feeder_name", feeder.feederName)
        }

        OutputStreamWriter(connection.outputStream).use { it.write(body.toString()); it.flush() }

        // Compute max allowed hour for this date
        val maxHour = HourLockHelper.maxAllowedHour(date)

        val rows = mutableListOf<HourlyRowData>()
        HOURS.forEach { h ->
            val hourIdx = h.toInt()
            rows.add(HourlyRowData(h, mutableMapOf(), isLocked = hourIdx > maxHour))
        }

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(response)
            val data = json.optJSONArray("data") ?: return rows

            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val param = item.optString("PARAMETER", "")
                HOURS.forEach { hour ->
                    val value = item.optString(hour, "")
                    if (value.isNotEmpty() && value != "null") {
                        val rowIndex = hour.toInt()
                        if (rowIndex < rows.size) {
                            rows[rowIndex].parameters[param] = value
                        }
                    }
                }
            }
        }

        return rows
    }

    private fun fetchDailyData(token: String, date: String, feeder: FeederItem): DailyRowData {
        val url = URL("$BASE_URL/api/feeder/consumption")
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            doOutput = true
        }

        val body = JSONObject().apply {
            put("date", date)
            feeder.feederCode?.let { put("feeder_id", it) }
            put("feeder_name", feeder.feederName)
            put("limit", 1)
        }

        OutputStreamWriter(connection.outputStream).use { it.write(body.toString()); it.flush() }

        val defaultRow = DailyRowData(
            feederName = feeder.feederName,
            feederCode = feeder.feederCode,
            feederCategory = feeder.feederCategory
        )

        if (connection.responseCode != HttpURLConnection.HTTP_OK) return defaultRow

        val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        val json = JSONObject(response)
        val data = json.optJSONArray("data") ?: return defaultRow
        if (data.length() == 0) return defaultRow

        val item = data.getJSONObject(0)
        return DailyRowData(
            feederName = feeder.feederName,
            feederCode = feeder.feederCode,
            feederCategory = feeder.feederCategory,
            totalConsumption = item.optString("TOTAL_CONSUMPTION", ""),
            supply3ph = item.optString("SUPPLY_3PH", ""),
            supply1ph = item.optString("SUPPLY_1PH", ""),
            remark = item.optString("REMARK", "PROPER")
        )
    }

    // ============================================================
    // SUBMIT
    // ============================================================

    private fun submitHourlyData() {
        val state = hourlyStates.getOrNull(selectedHourlyFeederIndex) ?: return
        val token = SessionManager.getToken(requireContext())

        lifecycleScope.launch {
            showLoading(true)
            try {
                val result = withContext(Dispatchers.IO) { saveHourlyData(token, date, state) }

                if (result.first) {
                    Toast.makeText(context, "✅ Hourly data saved!", Toast.LENGTH_SHORT).show()
                    ViewModelProvider(requireActivity())[FeederStatusViewModel::class.java].invalidate()
                    val updatedRows = withContext(Dispatchers.IO) {
                        fetchHourlyData(token, date, allFeeders[selectedHourlyFeederIndex])
                    }
                    hourlyStates[selectedHourlyFeederIndex].rows.clear()
                    hourlyStates[selectedHourlyFeederIndex].rows.addAll(updatedRows)
                    showHourlyForFeeder(selectedHourlyFeederIndex)
                } else {
                    showErrorDialog(result.second)
                }
            } catch (e: Exception) {
                showErrorDialog(ApiErrorHandler.handle(e))
            } finally {
                showLoading(false)
            }
        }
    }

    private fun submitDailyData() {
        val token = SessionManager.getToken(requireContext())

        lifecycleScope.launch {
            showLoading(true)
            try {
                val result = withContext(Dispatchers.IO) { saveDailyData(token, date, dailyRows) }

                if (result.first) {
                    Toast.makeText(context, "✅ Daily data saved!", Toast.LENGTH_SHORT).show()
                    ViewModelProvider(requireActivity())[FeederStatusViewModel::class.java].invalidate()
                    dailyRows.clear()
                    for (feeder in allFeeders) {
                        dailyRows.add(withContext(Dispatchers.IO) { fetchDailyData(token, date, feeder) })
                    }
                    dailyAdapter.submitList(dailyRows.toMutableList())
                } else {
                    showErrorDialog(result.second)
                }
            } catch (e: Exception) {
                showErrorDialog(ApiErrorHandler.handle(e))
            } finally {
                showLoading(false)
            }
        }
    }

    private fun submitAll() {
        val token = SessionManager.getToken(requireContext())

        lifecycleScope.launch {
            showLoading(true)
            val errors = mutableListOf<String>()

            try {
                for (state in hourlyStates) {
                    val result = withContext(Dispatchers.IO) { saveHourlyData(token, date, state) }
                    if (!result.first) errors.add("Hourly (${state.feederName}): ${result.second}")
                }

                val dailyResult = withContext(Dispatchers.IO) { saveDailyData(token, date, dailyRows) }
                if (!dailyResult.first) errors.add("Daily: ${dailyResult.second}")

                if (errors.isEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("✅ All Data Saved!")
                        .setMessage("Hourly and Daily data saved successfully for $date")
                        .setPositiveButton("OK") { _, _ ->
                            ViewModelProvider(requireActivity())[FeederStatusViewModel::class.java].invalidate()
                            findNavController().navigateUp()
                        }
                        .show()
                } else {
                    showErrorDialog("Some errors occurred:\n\n${errors.joinToString("\n")}")
                }

            } catch (e: Exception) {
                showErrorDialog(ApiErrorHandler.handle(e))
            } finally {
                showLoading(false)
            }
        }
    }

    private fun saveHourlyData(token: String, date: String, state: FeederHourlyState): Pair<Boolean, String> {
        val url = URL("$BASE_URL/api/feeder/hourly-entry/save")
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            doOutput = true
        }

        val rowsArray = JSONArray()
        for (param in PARAMETERS) {
            val hoursObj = JSONObject()
            // Skip locked rows when saving
            for (row in state.rows.filter { !it.isLocked }) {
                val value = row.parameters[param] ?: ""
                if (value.isNotEmpty()) hoursObj.put(row.hour, value)
            }
            rowsArray.put(JSONObject().apply {
                put("date", date)
                state.feederCode?.let { put("feeder_code", it) }
                put("feeder_name", state.feederName)
                put("parameter", param)
                put("hours", hoursObj)
            })
        }

        val body = JSONObject().put("rows", rowsArray)
        OutputStreamWriter(connection.outputStream).use { it.write(body.toString()); it.flush() }

        return if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(response)
            val allowAutofill = json.optBoolean("allow_autofill", false)
            if (allowAutofill) {
                Pair(false, json.optString("message", "Autofill required"))
            } else {
                Pair(json.optBoolean("success", false), json.optString("message", ""))
            }
        } else {
            val errorMsg = ApiErrorHandler.fromErrorStream(connection.errorStream, connection.responseCode)
            Pair(false, errorMsg)
        }
    }

    private fun saveDailyData(token: String, date: String, rows: List<DailyRowData>): Pair<Boolean, String> {
        val url = URL("$BASE_URL/api/feeder/consumption/save")
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            doOutput = true
        }

        val rowsArray = JSONArray()
        for (row in rows) {
            rowsArray.put(JSONObject().apply {
                put("date", date)
                row.feederCode?.let { put("feeder_code", it) }
                put("feeder_name", row.feederName)
                put("feeder_category", row.feederCategory)
                put("total_consumption", row.totalConsumption.toDoubleOrNull() ?: 0.0)
                put("supply_3ph", row.supply3ph.ifEmpty { "00:00" })
                put("supply_1ph", row.supply1ph.ifEmpty { "00:00" })
                put("remark", row.remark.ifEmpty { "PROPER" })
            })
        }

        val body = JSONObject().put("rows", rowsArray)
        OutputStreamWriter(connection.outputStream).use { it.write(body.toString()); it.flush() }

        return if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(response)
            Pair(json.optBoolean("success", false), json.optString("message", ""))
        } else {
            val errorMsg = ApiErrorHandler.fromErrorStream(connection.errorStream, connection.responseCode)
            Pair(false, errorMsg)
        }
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private fun scrollToAndFocusHourly(recyclerView: RecyclerView, position: Int) {
        recyclerView.scrollToPosition(position)
        recyclerView.post {
            val vh = recyclerView.findViewHolderForAdapterPosition(position)
            (vh as? MissingHourlyAdapter.HourlyRowViewHolder)?.focusFirstEmptyField()
        }
    }

    private fun scrollToAndFocusDaily(recyclerView: RecyclerView, position: Int) {
        recyclerView.scrollToPosition(position)
        recyclerView.post {
            val vh = recyclerView.findViewHolderForAdapterPosition(position)
            (vh as? MissingDailyAdapter.DailyViewHolder)?.focusFirstEmptyField()
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSubmitAll.isEnabled = !show
        binding.btnSubmitHourly.isEnabled = !show
        binding.btnSubmitDaily.isEnabled = !show
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("❌ Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ============================================================
// HOURLY ADAPTER
// NEW: isLocked rows → grey background, fields disabled, not focusable
// ============================================================

class MissingHourlyAdapter(
    private val onFirstEmptyFound: (RecyclerView, Int) -> Unit
) : RecyclerView.Adapter<MissingHourlyAdapter.HourlyRowViewHolder>() {

    private var rows: MutableList<HourlyRowData> = mutableListOf()
    private var firstEmptyNotified = false

    fun submitList(newRows: MutableList<HourlyRowData>) {
        rows = newRows
        firstEmptyNotified = false
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HourlyRowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_missing_hourly_row, parent, false)
        return HourlyRowViewHolder(view)
    }

    override fun onBindViewHolder(holder: HourlyRowViewHolder, position: Int) {
        holder.bind(rows[position])

        // Auto-focus: only for unlocked+empty rows
        val row = rows[position]
        val isEmpty = !row.isLocked && PARAMETERS.any { row.parameters[it].isNullOrEmpty() }
        if (isEmpty && !firstEmptyNotified) {
            firstEmptyNotified = true
            holder.itemView.post {
                (holder.itemView.parent as? RecyclerView)?.let { rv ->
                    onFirstEmptyFound(rv, position)
                }
            }
        }
    }

    override fun getItemCount() = rows.size

    companion object {
        val PARAMETERS = listOf("IB", "IR", "IY", "MW", "MVAR")
    }

    inner class HourlyRowViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val tvHour: TextView = view.findViewById(R.id.tvHour)
        private val etIB: EditText = view.findViewById(R.id.etIB)
        private val etIR: EditText = view.findViewById(R.id.etIR)
        private val etIY: EditText = view.findViewById(R.id.etIY)
        private val etMW: EditText = view.findViewById(R.id.etMW)
        private val etMVAR: EditText = view.findViewById(R.id.etMVAR)

        private lateinit var currentRow: HourlyRowData

        private val paramToEditText: Map<String, EditText> by lazy {
            mapOf("IB" to etIB, "IR" to etIR, "IY" to etIY, "MW" to etMW, "MVAR" to etMVAR)
        }

        fun bind(row: HourlyRowData) {
            currentRow = row

            val hourInt = row.hour.toIntOrNull() ?: 0
            tvHour.text = String.format("%02d:00", hourInt + 1)

            if (row.isLocked) {
                // ── LOCKED ROW: grey, not editable ──
                itemView.setBackgroundColor(Color.parseColor("#F5F5F5"))
                tvHour.setTextColor(Color.parseColor("#9E9E9E"))

                val lockedFields = listOf(etIB, etIR, etIY, etMW, etMVAR)
                lockedFields.forEach { et ->
                    et.isEnabled = false
                    et.isFocusable = false
                    et.setBackgroundColor(Color.parseColor("#EEEEEE"))
                    et.setTextColor(Color.parseColor("#BDBDBD"))
                    et.setText("")
                    et.hint = "—"
                }
            } else {
                // ── EDITABLE ROW ──
                tvHour.setTextColor(Color.BLACK)

                val hasAnyEmpty = PARAMETERS.any { currentRow.parameters[it].isNullOrEmpty() }
                itemView.setBackgroundColor(
                    if (hasAnyEmpty) Color.parseColor("#FFEBEE") else Color.WHITE
                )

                setupField(etIB, "IB", integerOnly = true, allowNegative = false)
                setupField(etIR, "IR", integerOnly = true, allowNegative = false)
                setupField(etIY, "IY", integerOnly = true, allowNegative = false)
                setupField(etMW, "MW", integerOnly = false, allowNegative = false)
                setupField(etMVAR, "MVAR", integerOnly = false, allowNegative = true)

                setupEnterNavigation()
            }
        }

        fun focusFirstEmptyField() {
            if (currentRow.isLocked) return
            PARAMETERS.forEach { param ->
                if (currentRow.parameters[param].isNullOrEmpty()) {
                    paramToEditText[param]?.requestFocus()
                    return
                }
            }
        }

        private fun setupField(
            editText: EditText,
            param: String,
            integerOnly: Boolean,
            allowNegative: Boolean
        ) {
            (editText.tag as? TextWatcher)?.let { editText.removeTextChangedListener(it) }

            editText.isEnabled = true
            editText.isFocusableInTouchMode = true

            editText.inputType = when {
                integerOnly -> InputType.TYPE_CLASS_NUMBER
                allowNegative -> InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
                else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }

            val value = currentRow.parameters[param] ?: ""
            editText.setText(value)

            if (value.isEmpty()) {
                editText.setBackgroundColor(Color.parseColor("#FFCDD2"))
                editText.setTextColor(Color.BLACK)
                editText.hint = "—"
            } else {
                editText.setBackgroundColor(Color.WHITE)
                editText.setTextColor(Color.BLACK)
                editText.hint = ""
            }

            val watcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    currentRow.parameters[param] = s.toString()
                    if (s.toString().isNotEmpty()) {
                        editText.setBackgroundColor(Color.WHITE)
                        editText.hint = ""
                    } else {
                        editText.setBackgroundColor(Color.parseColor("#FFCDD2"))
                        editText.hint = "—"
                    }
                    val anyEmpty = PARAMETERS.any { currentRow.parameters[it].isNullOrEmpty() }
                    itemView.setBackgroundColor(
                        if (anyEmpty) Color.parseColor("#FFEBEE") else Color.WHITE
                    )
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }

            editText.addTextChangedListener(watcher)
            editText.tag = watcher
        }

        private fun setupEnterNavigation() {
            val editTexts = listOf(etIB, etIR, etIY, etMW, etMVAR)

            editTexts.forEachIndexed { index, et ->
                et.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_NEXT ||
                        actionId == EditorInfo.IME_ACTION_DONE
                    ) {
                        val nextEmpty = editTexts.drop(index + 1)
                            .firstOrNull { it.text.toString().isEmpty() }

                        nextEmpty?.requestFocus() ?: run {
                            val rv = itemView.parent as? RecyclerView
                            // Jump to next UNLOCKED row
                            var nextPos = adapterPosition + 1
                            while (nextPos < itemCount) {
                                rv?.scrollToPosition(nextPos)
                                rv?.post {
                                    val nextVh = rv.findViewHolderForAdapterPosition(nextPos)
                                    val vh = nextVh as? HourlyRowViewHolder
                                    if (vh != null && !vh.currentRow.isLocked) {
                                        vh.focusFirstEmptyField()
                                    }
                                }
                                break
                            }
                        }
                        true
                    } else false
                }
            }
        }
    }
}

// ============================================================
// DAILY ADAPTER (unchanged structure, same as before)
// ============================================================

class MissingDailyAdapter(
    private val onFirstEmptyFound: (RecyclerView, Int) -> Unit
) : RecyclerView.Adapter<MissingDailyAdapter.DailyViewHolder>() {

    private var rows: MutableList<DailyRowData> = mutableListOf()
    private var firstEmptyNotified = false

    fun submitList(newRows: MutableList<DailyRowData>) {
        rows = newRows
        firstEmptyNotified = false
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DailyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_missing_daily_row, parent, false)
        return DailyViewHolder(view)
    }

    override fun onBindViewHolder(holder: DailyViewHolder, position: Int) {
        holder.bind(rows[position])

        val row = rows[position]
        val isEmpty = row.totalConsumption.isEmpty()

        if (isEmpty && !firstEmptyNotified) {
            firstEmptyNotified = true
            holder.itemView.post {
                (holder.itemView.parent as? RecyclerView)?.let { rv ->
                    onFirstEmptyFound(rv, position)
                }
            }
        }
    }

    override fun getItemCount() = rows.size

    inner class DailyViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val tvFeederName: TextView = view.findViewById(R.id.tvFeederName)
        private val tvCategory: TextView = view.findViewById(R.id.tvCategory)
        private val etTotal: EditText = view.findViewById(R.id.etTotalConsumption)
        private val etSupply3ph: EditText = view.findViewById(R.id.etSupply3ph)
        private val etSupply1ph: EditText = view.findViewById(R.id.etSupply1ph)
        private val etRemark: EditText = view.findViewById(R.id.etRemark)
        private val tvStatus: TextView = view.findViewById(R.id.tvStatus)

        private lateinit var currentRow: DailyRowData

        fun bind(row: DailyRowData) {
            currentRow = row
            tvFeederName.text = row.feederName
            tvCategory.text = row.feederCategory

            // Only truly empty = not yet fetched from DB; 0 / 0.0 are valid saved values
            val isEmpty = row.totalConsumption.isEmpty()

            itemView.setBackgroundColor(if (isEmpty) Color.parseColor("#FFEBEE") else Color.WHITE)
            tvStatus.text = if (isEmpty) "⚠️ INCOMPLETE" else "✅ FILLED"
            tvStatus.setTextColor(
                if (isEmpty) Color.parseColor("#FF6F00") else Color.parseColor("#2E7D32")
            )

            setupTextField(etTotal, row.totalConsumption) { currentRow.totalConsumption = it }
            // Show "00:00" as-is so user can see the saved value; don't blank it out
            setupTimeField(etSupply3ph, row.supply3ph) { currentRow.supply3ph = it }
            setupTimeField(etSupply1ph, row.supply1ph) { currentRow.supply1ph = it }
            setupTextField(etRemark, row.remark) { currentRow.remark = it }
        }

        fun focusFirstEmptyField() {
            when {
                etTotal.text.toString().isEmpty() -> etTotal.requestFocus()
                etSupply3ph.text.toString().isEmpty() -> etSupply3ph.requestFocus()
                etSupply1ph.text.toString().isEmpty() -> etSupply1ph.requestFocus()
                else -> etRemark.requestFocus()
            }
        }

        private fun setupTextField(et: EditText, value: String, onChange: (String) -> Unit) {
            (et.tag as? TextWatcher)?.let { et.removeTextChangedListener(it) }
            et.setText(value)
            val watcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { onChange(s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            et.addTextChangedListener(watcher)
            et.tag = watcher
        }

        private fun setupTimeField(et: EditText, value: String, onChange: (String) -> Unit) {
            (et.tag as? TextWatcher)?.let { et.removeTextChangedListener(it) }
            et.inputType = InputType.TYPE_CLASS_NUMBER
            et.setText(value)
            val watcher = object : TextWatcher {
                private var isFormatting = false
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isFormatting) return
                    isFormatting = true
                    val digits = s.toString().filter { it.isDigit() }.take(4)
                    val formatted = if (digits.length >= 4)
                        "${digits.substring(0, 2)}:${digits.substring(2, 4)}"
                    else digits
                    et.setText(formatted)
                    et.setSelection(formatted.length)
                    isFormatting = false
                    onChange(formatted)
                }
            }
            et.addTextChangedListener(watcher)
            et.tag = watcher
        }
    }
}