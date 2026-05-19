package com.apc.kptcl.home.users.division

// FILE: DivisionHourlyViewFragment.kt

import android.app.DatePickerDialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentDivisionHourlyViewBinding
import com.apc.kptcl.utils.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class DivisionHourlyViewFragment : Fragment() {

    private var _binding: FragmentDivisionHourlyViewBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val TAG = "DivisionHourlyView"
        private const val TIMEOUT = 30000
        private val HOURS = listOf(
            "00","01","02","03","04","05","06","07","08",
            "09","10","11","12","13","14","15","16","17",
            "18","19","20","21","22","23"
        )
    }

    private var selectedDate      = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    private var selectedStation   = "ALL" // ALL = all division stations
    private var selectedParameter = "MW"
    private var stationList       = listOf<String>()
    private val parameterList     = listOf("IB", "IR", "IY", "MW", "MVAR")

    // Loaded rows — kept for Excel export
    private var loadedRows: List<HourlyRow> = emptyList()

    // Hour filter — which hours to show in table
    private var selectedHourRange = "ALL"
    private val hourRanges = linkedMapOf(
        "ALL"   to HOURS,
        "00-06" to listOf("00","01","02","03","04","05"),
        "06-12" to listOf("06","07","08","09","10","11"),
        "12-18" to listOf("12","13","14","15","16","17"),
        "18-24" to listOf("18","19","20","21","22","23")
    )

    data class HourlyRow(
        val date: String,
        val stationName: String,
        val feederName: String,
        val feederCode: String,
        val feederCategory: String,
        val peakLoad: String,
        val peakHour: String,
        val parameter: String,
        val hours: Map<String, String?> // h00..h23
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDivisionHourlyViewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val stations = arguments?.getStringArrayList(DivisionHomeFragment.KEY_STATIONS)
        stationList = stations ?: emptyList()

        setupBackButton()
        setupDatePicker()
        updateDateDisplay()
        setupStationSpinner()
        setupParameterSpinner()
        setupSearchButton()
        setupDownloadButton()
        setupHourFilter()
    }

    // ── BACK ─────────────────────────────────────────────────────

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            findNavController().navigate(R.id.divisionHomeFragment, null,
                navOptions { popUpTo(R.id.divisionHomeFragment) { inclusive = true } })
        }
    }

    // ── DATE ─────────────────────────────────────────────────────

    private fun setupDatePicker() {
        binding.btnPickDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(requireContext(), { _, y, m, d ->
                selectedDate = String.format("%04d-%02d-%02d", y, m + 1, d)
                updateDateDisplay()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                .apply { datePicker.maxDate = System.currentTimeMillis() }
                .show()
        }
    }

    private fun updateDateDisplay() {
        try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(selectedDate)
            binding.tvSelectedDate.text =
                SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(parsed!!)
        } catch (e: Exception) { binding.tvSelectedDate.text = selectedDate }
    }

    // ── STATION SPINNER — "ALL" + each station ───────────────────

    private fun setupStationSpinner() {
        val list = mutableListOf("ALL") + stationList
        if (stationList.isNotEmpty()) {
            binding.spinnerStation.visibility = View.VISIBLE
            binding.etStation.visibility      = View.GONE
            val adapter = ArrayAdapter(requireContext(),
                android.R.layout.simple_spinner_item, list)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            binding.spinnerStation.adapter = adapter
            selectedStation = "ALL"
            binding.spinnerStation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedStation = list[pos]
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        } else {
            binding.spinnerStation.visibility = View.GONE
            binding.etStation.visibility      = View.VISIBLE
        }
    }

    // ── PARAMETER SPINNER ────────────────────────────────────────

    private fun setupParameterSpinner() {
        val pa = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, parameterList)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerParameter.adapter = pa
        binding.spinnerParameter.setSelection(parameterList.indexOf("MW"))
        binding.spinnerParameter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedParameter = parameterList[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ── SEARCH ───────────────────────────────────────────────────

    private fun setupSearchButton() {
        binding.btnSearch.setOnClickListener {
            if (stationList.isEmpty()) {
                selectedStation = binding.etStation.text.toString().trim()
                if (selectedStation.isEmpty()) {
                    Toast.makeText(requireContext(), "Please enter a station", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            loadData()
        }
    }

    // ── DOWNLOAD ─────────────────────────────────────────────────

    private fun setupDownloadButton() {
        binding.btnDownloadExcel.setOnClickListener {
            if (loadedRows.isEmpty()) {
                Toast.makeText(requireContext(), "No data to download", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            exportCsv()
        }
    }

    // ── LOAD DATA ────────────────────────────────────────────────

    private fun loadData() {
        val token     = SessionManager.getToken(requireContext())
        val serverUrl = SessionManager.getServerUrl(requireContext())
        if (token.isEmpty() || serverUrl.isEmpty()) {
            Toast.makeText(requireContext(), "Session expired.", Toast.LENGTH_LONG).show()
            return
        }

        binding.progressBar.visibility    = View.VISIBLE
        binding.scrollVertical.visibility = View.GONE
        binding.tvEmpty.visibility        = View.GONE
        binding.tvResultTitle.visibility  = View.GONE
        binding.tvRowSubtitle.visibility  = View.GONE
        binding.btnDownloadExcel.visibility = View.GONE
        binding.scrollHourFilter.visibility = View.GONE
        binding.tvRowCount.text           = ""
        loadedRows = emptyList()

        lifecycleScope.launch {
            try {
                val rows = fetchData(serverUrl, token)
                binding.progressBar.visibility = View.GONE
                if (rows.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                } else {
                    loadedRows = rows
                    val stationLabel = if (selectedStation == "ALL") "All Stations" else selectedStation
                    binding.tvResultTitle.text = "Hourly · $stationLabel · $selectedParameter · $selectedDate"
                    binding.tvResultTitle.visibility  = View.VISIBLE
                    binding.tvRowSubtitle.text = "Showing ${rows.size} rows"
                    binding.tvRowSubtitle.visibility  = View.VISIBLE
                    binding.tvRowCount.text = "${rows.size} rows"
                    binding.btnDownloadExcel.visibility = View.VISIBLE
                    binding.scrollHourFilter.visibility = View.VISIBLE
                    buildTable(rows)
                    binding.scrollVertical.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Load error", e)
                binding.progressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "Failed to load data.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun fetchData(serverUrl: String, token: String): List<HourlyRow> =
        withContext(Dispatchers.IO) {
            if (selectedStation == "ALL") {
                // All division stations — use new endpoint
                fetchAllStations(serverUrl, token)
            } else {
                // Single station — use existing peak endpoint
                fetchSingleStation(serverUrl, token, selectedStation)
            }
        }

    private fun fetchAllStations(serverUrl: String, token: String): List<HourlyRow> {
        val urlStr = "$serverUrl/api/division/hourly/all?date=$selectedDate&parameter=$selectedParameter"
        Log.d(TAG, "Fetching all: $urlStr")
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.apply {
                requestMethod = "GET"; connectTimeout = TIMEOUT; readTimeout = TIMEOUT
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                parseAllStationsResponse(body)
            } else { Log.w(TAG, "HTTP ${conn.responseCode}"); emptyList() }
        } finally { conn.disconnect() }
    }

    private fun fetchSingleStation(serverUrl: String, token: String, station: String): List<HourlyRow> {
        val encoded = java.net.URLEncoder.encode(station, "UTF-8")
        val urlStr = "$serverUrl/api/feeder/hourly/peak?date=$selectedDate&station=$encoded&parameter=$selectedParameter"
        Log.d(TAG, "Fetching single: $urlStr")
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.apply {
                requestMethod = "GET"; connectTimeout = TIMEOUT; readTimeout = TIMEOUT
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                parsePeakResponse(body, station)
            } else emptyList()
        } finally { conn.disconnect() }
    }

    private fun parseAllStationsResponse(json: String): List<HourlyRow> {
        return try {
            val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val hours = mutableMapOf<String, String?>()
                HOURS.forEach { h -> hours[h] = o.optString("h$h", "—").let { if (it == "null" || it.isEmpty()) "—" else it } }
                HourlyRow(
                    date          = o.optString("DATE", selectedDate),
                    stationName   = o.optString("STATION_NAME", "—"),
                    feederName    = o.optString("FEEDER_NAME", "—"),
                    feederCode    = o.optString("FEEDER_CODE", "—"),
                    feederCategory= o.optString("FEEDER_CATEGORY", "—"),
                    peakLoad      = o.optString("PEAK_LOAD", "—"),
                    peakHour      = o.optString("PEAK_HOUR", "—"),
                    parameter     = o.optString("PARAMETER", selectedParameter),
                    hours         = hours
                )
            }
        } catch (e: Exception) { Log.e(TAG, "Parse error", e); emptyList() }
    }

    private fun parsePeakResponse(json: String, station: String): List<HourlyRow> {
        // Peak endpoint doesn't return hour-by-hour — we show what we have
        return try {
            val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HourlyRow(
                    date          = selectedDate,
                    stationName   = station,
                    feederName    = o.optString("FEEDER_NAME", "—"),
                    feederCode    = o.optString("FEEDER_CODE", "—"),
                    feederCategory= o.optString("FEEDER_CATEGORY", "—"),
                    peakLoad      = o.optString("PEAK_LOAD_HIGHEST_MW", "—"),
                    peakHour      = o.optString("PEAK_HOUR", "—"),
                    parameter     = selectedParameter,
                    hours         = HOURS.associateWith { "—" }
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // ── HOUR FILTER CHIPS ────────────────────────────────────────

    private fun setupHourFilter() {
        val ctx = requireContext()
        val chipGroup = binding.chipGroup
        chipGroup.removeAllViews()
        val dp6 = dpToPx(ctx, 6f)
        val dp12 = dpToPx(ctx, 12f)

        hourRanges.keys.forEach { label ->
            val chip = TextView(ctx).apply {
                text = label
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp12, dp6, dp12, dp6)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(dp6, 0, 0, 0) }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedHourRange = label
                    updateChipStyles()
                    // Rebuild table with new hour filter
                    if (loadedRows.isNotEmpty()) buildTable(loadedRows)
                }
            }
            chipGroup.addView(chip)
        }
        updateChipStyles()
    }

    private fun updateChipStyles() {
        val chipGroup = binding.chipGroup
        hourRanges.keys.forEachIndexed { i, label ->
            val chip = chipGroup.getChildAt(i) as? TextView ?: return@forEachIndexed
            val selected = label == selectedHourRange
            chip.setBackgroundColor(
                if (selected) Color.parseColor("#00838F") else Color.parseColor("#E0F7FA")
            )
            chip.setTextColor(
                if (selected) Color.WHITE else Color.parseColor("#00838F")
            )
        }
    }

    // ── BUILD PORTAL-STYLE TABLE ──────────────────────────────────

    private fun buildTable(rows: List<HourlyRow>) {
        val container = binding.tableContainer
        container.removeAllViews()

        val ctx = requireContext()
        val dp1 = dpToPx(ctx, 1f)
        val dp4 = dpToPx(ctx, 4f)

        // Hours to display based on selected filter
        val visibleHours = hourRanges[selectedHourRange] ?: HOURS

        // Fixed column widths (dp)
        val colWidths = mapOf(
            "DATE"      to 90,
            "STATION"   to 120,
            "FEEDER"    to 130,
            "CODE"      to 100,
            "CATEGORY"  to 90,
            "PEAK_LOAD" to 90,
            "PEAK_HOUR" to 70,
            "PARAM"     to 60
        ) + HOURS.associate { it to 60 }

        // ── HEADER ROW ───────────────────────────────────────────
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#FFF8E1"))
            setPadding(0, dp4, 0, dp4)
        }

        fun addHeader(text: String, widthDp: Int) {
            headerRow.addView(TextView(ctx).apply {
                this.text = text
                textSize = 9f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#B71C1C"))
                gravity = Gravity.CENTER
                setPadding(dp4, dp4, dp4, dp4)
                layoutParams = LinearLayout.LayoutParams(dpToPx(ctx, widthDp.toFloat()), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.setMargins(dp1, 0, dp1, 0) }
            })
        }

        addHeader("DATE",        colWidths["DATE"]!!)
        addHeader("STATION",     colWidths["STATION"]!!)
        addHeader("FEEDER NAME", colWidths["FEEDER"]!!)
        addHeader("FEEDER CODE", colWidths["CODE"]!!)
        addHeader("CATEGORY",    colWidths["CATEGORY"]!!)
        addHeader("PEAK LOAD",   colWidths["PEAK_LOAD"]!!)
        addHeader("PEAK HOUR",   colWidths["PEAK_HOUR"]!!)
        addHeader("PARAM",       colWidths["PARAM"]!!)
        visibleHours.forEach { h ->
            addHeader("${h}-${String.format("%02d", (h.toInt()+1) % 24)}hr", colWidths[h]!!)
        }

        container.addView(headerRow)

        // Divider
        container.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp1)
        })

        // ── DATA ROWS ────────────────────────────────────────────
        rows.forEachIndexed { index, row ->
            val dataRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(if (index % 2 == 0) Color.WHITE else Color.parseColor("#FAFAFA"))
                setPadding(0, dp4, 0, dp4)
            }

            fun addCell(text: String, widthDp: Int, highlight: Boolean = false) {
                dataRow.addView(TextView(ctx).apply {
                    this.text = text
                    textSize = 9f
                    setTextColor(if (highlight) Color.WHITE else Color.parseColor("#333333"))
                    gravity = Gravity.CENTER
                    setPadding(dp4, dp4, dp4, dp4)
                    if (highlight) setBackgroundColor(Color.parseColor("#FF8F00"))
                    layoutParams = LinearLayout.LayoutParams(dpToPx(ctx, widthDp.toFloat()), LinearLayout.LayoutParams.WRAP_CONTENT)
                        .also { it.setMargins(dp1, 0, dp1, 0) }
                })
            }

            addCell(row.date,           colWidths["DATE"]!!)
            addCell(row.stationName,    colWidths["STATION"]!!)
            addCell(row.feederName,     colWidths["FEEDER"]!!)
            addCell(row.feederCode,     colWidths["CODE"]!!)
            addCell(row.feederCategory, colWidths["CATEGORY"]!!)
            addCell(row.peakLoad,       colWidths["PEAK_LOAD"]!!)
            addCell(row.peakHour,       colWidths["PEAK_HOUR"]!!)
            addCell(row.parameter,      colWidths["PARAM"]!!)

            // Hour columns — FIX: only highlight if value is NOT empty/dash AND matches peak hour
            visibleHours.forEach { h ->
                val value   = row.hours[h] ?: "—"
                val hasData = value != "—" && value.isNotBlank()
                // peakHour format from backend: "09-10" — check if h matches start
                val isPeak  = hasData && row.peakHour.startsWith(h)
                addCell(value, colWidths[h]!!, highlight = isPeak)
            }

            container.addView(dataRow)

            container.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp1)
            })
        }
    }

    // ── EXCEL (CSV) EXPORT ───────────────────────────────────────

    private fun exportCsv() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                // Header
                sb.append("DATE,STATION,FEEDER NAME,FEEDER CODE,CATEGORY,PEAK LOAD,PEAK HOUR,PARAMETER")
                HOURS.forEach { h -> sb.append(",${h}-${String.format("%02d",(h.toInt()+1)%24)}hr") }
                sb.append("\n")
                // Rows
                loadedRows.forEach { row ->
                    sb.append("${row.date},${row.stationName},\"${row.feederName}\",${row.feederCode},${row.feederCategory},${row.peakLoad},${row.peakHour},${row.parameter}")
                    HOURS.forEach { h -> sb.append(",${row.hours[h] ?: ""}") }
                    sb.append("\n")
                }

                val fileName = "hourly_${selectedDate}_${selectedParameter}.csv"
                val saved = saveCsv(requireContext(), fileName, sb.toString())

                withContext(Dispatchers.Main) {
                    if (saved) {
                        Toast.makeText(requireContext(), "Saved: $fileName\n(Downloads folder)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), "Export failed. Try again.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveCsv(ctx: Context, fileName: String, content: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                true
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, fileName).writeText(content)
                true
            }
        } catch (e: Exception) { Log.e(TAG, "saveCsv failed", e); false }
    }

    // ── UTILS ─────────────────────────────────────────────────────

    private fun dpToPx(ctx: Context, dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.resources.displayMetrics).toInt()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}