package com.apc.kptcl.home.users.dashboard

import android.graphics.*
import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentFeederStatusDashboardBinding
import com.apc.kptcl.utils.ApiErrorHandler
import com.apc.kptcl.utils.SessionManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import androidx.lifecycle.ViewModelProvider

// ============================================================
// DATA MODELS
// ============================================================

enum class DayStatus {
    FULLY_FILLED,
    PARTIAL,
    MISSING,
    FUTURE,
    NO_DATA
}

data class DayStatusInfo(
    val date: String,
    val status: DayStatus,
    val totalFeeders: Int = 0,
    val filledHourly: Int = 0,
    val filledDaily: Int = 0,
    val missingHourlyFeeders: List<String> = emptyList(),
    val missingDailyFeeders: List<String> = emptyList()
)

data class FeederItem(
    val feederCode: String?,
    val feederName: String,
    val feederCategory: String
)

// ============================================================
// FRAGMENT
// ============================================================

class FeederStatusDashboardFragment : Fragment() {

    private var _binding: FragmentFeederStatusDashboardBinding? = null
    private val binding get() = _binding!!

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayMonthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

    private var currentCalendar = Calendar.getInstance()
    private var allFeeders = mutableListOf<FeederItem>()
    private val dayStatusMap = mutableMapOf<String, DayStatusInfo>()

    private lateinit var calendarAdapter: CalendarDayAdapter

    private var countFull = 0
    private var countPartial = 0
    private var countMissing = 0
    private lateinit var viewModel: FeederStatusViewModel

    companion object {
        private const val TAG = "FeederDashboard"
        private const val BASE_URL = "http://62.72.59.119:9009"
        private const val TIMEOUT = 15000
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeederStatusDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!SessionManager.isLoggedIn(requireContext())) {
            Snackbar.make(binding.root, "Please login first", Snackbar.LENGTH_LONG).show()
            return
        }

        // Dynamic station name — JWT token se directly read karo
        val stationName = getStationNameFromSession()
        binding.tvDashboardTitle.text = stationName
        requireActivity().title = stationName

        viewModel = ViewModelProvider(requireActivity())[FeederStatusViewModel::class.java]

        setupCalendar()
        setupMonthNavigation()
        setupRefreshButton()  // ← FAB setup

        // Cache check — agar same month ka data hai toh load mat karo
        if (viewModel.isCacheValid(currentCalendar)) {
            allFeeders = viewModel.allFeeders
            dayStatusMap.putAll(viewModel.dayStatusMap)
            updateCalendarUI()
            updateTrendChart()
        } else {
            fetchFeederListThenStatus()
        }
    }

    // ============================================================
    // STATION NAME — JWT se directly read karo
    // ============================================================

    private fun getStationNameFromSession(): String {
        val saved = SessionManager.getStationName(requireContext())
        if (saved.isNotEmpty()) return saved

        return try {
            val token = SessionManager.getToken(requireContext())
            val payload = token.split(".")[1]
            val decoded = android.util.Base64.decode(
                payload.replace('-', '+').replace('_', '/'),
                android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            val json = org.json.JSONObject(String(decoded))
            json.optString("station_name", "").ifEmpty {
                json.optString("username", "Feeder Status Dashboard")
            }
        } catch (e: Exception) {
            Log.e(TAG, "JWT decode failed: ${e.message}")
            "Feeder Status Dashboard"
        }
    }

    // ============================================================
    // CALENDAR SETUP
    // ============================================================

    private fun setupCalendar() {
        calendarAdapter = CalendarDayAdapter(emptyList()) { dayInfo ->
            if (dayInfo.status != DayStatus.FUTURE) navigateToEdit(dayInfo)
        }

        binding.rvCalendar.apply {
            layoutManager = GridLayoutManager(requireContext(), 7)
            adapter = calendarAdapter
        }

        updateMonthTitle()
    }

    private fun setupMonthNavigation() {
        binding.btnPrevMonth.setOnClickListener {
            currentCalendar.add(Calendar.MONTH, -1)
            updateMonthTitle()
            loadMonthStatus()
        }

        binding.btnNextMonth.setOnClickListener {
            val now = Calendar.getInstance()
            if (currentCalendar.get(Calendar.YEAR) < now.get(Calendar.YEAR) ||
                (currentCalendar.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                        currentCalendar.get(Calendar.MONTH) < now.get(Calendar.MONTH))
            ) {
                currentCalendar.add(Calendar.MONTH, 1)
                updateMonthTitle()
                loadMonthStatus()
            }
        }
    }

    // ============================================================
    // FLOATING REFRESH BUTTON
    // ============================================================

    private fun setupRefreshButton() {
        binding.fabRefresh.setOnClickListener {
            val monthLabel = displayMonthFormat.format(currentCalendar.time)

            // Sirf current month ka cache invalidate karo, baaki months safe rahenge
            viewModel.invalidateMonth(currentCalendar)

            // Local maps bhi clear karo — stale data na dikhe
            dayStatusMap.clear()

            Snackbar.make(
                binding.root,
                "Refreshing $monthLabel...",
                Snackbar.LENGTH_SHORT
            ).show()

            // Feeder list already available hai toh seedha month status fetch karo
            if (allFeeders.isNotEmpty()) {
                loadMonthStatus()
            } else {
                fetchFeederListThenStatus()
            }
        }
    }

    private fun updateMonthTitle() {
        binding.tvMonthYear.text = displayMonthFormat.format(currentCalendar.time)
    }

    // ============================================================
    // DATA FETCHING
    // ============================================================

    private fun fetchFeederListThenStatus() {
        val token = SessionManager.getToken(requireContext())
        showLoading(true)

        lifecycleScope.launch {
            try {
                allFeeders = withContext(Dispatchers.IO) { fetchFeederList(token) }.toMutableList()
                Log.d(TAG, "Feeders loaded: ${allFeeders.size}")
                loadMonthStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
                showLoading(false)
                Snackbar.make(binding.root, ApiErrorHandler.handle(e), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun loadMonthStatus() {
        val token = SessionManager.getToken(requireContext())
        showLoading(true)

        lifecycleScope.launch {
            try {
                val calCopy = currentCalendar.clone() as Calendar
                calCopy.set(Calendar.DAY_OF_MONTH, 1)

                val today = Calendar.getInstance()
                val daysInMonth = calCopy.getActualMaximum(Calendar.DAY_OF_MONTH)
                val todayStr = dateFormat.format(today.time)

                val dayList = mutableListOf<String>()
                for (day in 1..daysInMonth) {
                    calCopy.set(Calendar.DAY_OF_MONTH, day)
                    val dateStr = dateFormat.format(calCopy.time)
                    if (dateStr <= todayStr) dayList.add(dateStr)
                }

                val newStatusMap = mutableMapOf<String, DayStatusInfo>()
                dayList.chunked(5).forEach { chunk ->
                    val deferreds = chunk.map { date ->
                        async(Dispatchers.IO) { date to fetchDayStatus(token, date) }
                    }
                    deferreds.awaitAll().forEach { (date, status) ->
                        newStatusMap[date] = status
                    }
                }

                dayStatusMap.clear()
                dayStatusMap.putAll(newStatusMap)

                // Cache save karo
                viewModel.saveCache(allFeeders, dayStatusMap, currentCalendar)

                updateCalendarUI()
                updateTrendChart()

            } catch (e: Exception) {
                Log.e(TAG, "Month status error", e)
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun fetchDayStatus(token: String, date: String): DayStatusInfo =
        withContext(Dispatchers.IO) {
            try {
                val hourlyFilled = mutableListOf<String>()
                val hourlyMissing = mutableListOf<String>()
                for (feeder in allFeeders) {
                    if (checkHourlyFilled(token, date, feeder)) hourlyFilled.add(feeder.feederName)
                    else hourlyMissing.add(feeder.feederName)
                }

                val dailyFilled = mutableListOf<String>()
                val dailyMissing = mutableListOf<String>()
                for (feeder in allFeeders) {
                    if (checkDailyFilled(token, date, feeder)) dailyFilled.add(feeder.feederName)
                    else dailyMissing.add(feeder.feederName)
                }

                val noDataAtAll = hourlyFilled.isEmpty() && dailyFilled.isEmpty()
                val status = when {
                    noDataAtAll -> DayStatus.MISSING
                    hourlyMissing.isEmpty() && dailyMissing.isEmpty() -> DayStatus.FULLY_FILLED
                    else -> DayStatus.PARTIAL
                }

                DayStatusInfo(
                    date = date,
                    status = status,
                    totalFeeders = allFeeders.size,
                    filledHourly = hourlyFilled.size,
                    filledDaily = dailyFilled.size,
                    missingHourlyFeeders = hourlyMissing,
                    missingDailyFeeders = dailyMissing
                )
            } catch (e: Exception) {
                DayStatusInfo(date = date, status = DayStatus.NO_DATA)
            }
        }

    private fun checkHourlyFilled(token: String, date: String, feeder: FeederItem): Boolean {
        return try {
            val connection = (URL("$BASE_URL/api/feeder/hourly").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = TIMEOUT; readTimeout = TIMEOUT
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

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(BufferedReader(InputStreamReader(connection.inputStream)).readText())
                val data = json.optJSONArray("data") ?: return false
                if (data.length() == 0) return false

                val PARAMS = setOf("IB", "IR", "IY", "MW", "MVAR")
                val HOURS = (0..23).map { String.format("%02d", it) }.toSet()
                val paramMap = mutableMapOf<String, Set<String>>()

                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val param = item.optString("PARAMETER", "")
                    paramMap[param] = HOURS.filter { h ->
                        item.optString(h, "").let { it.isNotEmpty() && it != "null" }
                    }.toSet()
                }
                PARAMS.all { paramMap[it]?.containsAll(HOURS) == true }
            } else false
        } catch (e: Exception) { false }
    }

    private fun checkDailyFilled(token: String, date: String, feeder: FeederItem): Boolean {
        return try {
            val connection = (URL("$BASE_URL/api/feeder/consumption").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = TIMEOUT; readTimeout = TIMEOUT
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

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(BufferedReader(InputStreamReader(connection.inputStream)).readText())
                val data = json.optJSONArray("data") ?: return false
                if (data.length() == 0) return false
                data.length() > 0
            } else false
        } catch (e: Exception) { false }
    }

    private suspend fun fetchFeederList(token: String): List<FeederItem> =
        withContext(Dispatchers.IO) {
            val connection = (URL("$BASE_URL/api/feeder/list").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = TIMEOUT; readTimeout = TIMEOUT
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(BufferedReader(InputStreamReader(connection.inputStream)).readText())
                val data = json.optJSONArray("data") ?: return@withContext emptyList()
                (0 until data.length()).map { i ->
                    val item = data.getJSONObject(i)
                    val code = if (item.isNull("FEEDER_CODE")) null
                    else item.optString("FEEDER_CODE", "").takeIf { it.isNotEmpty() }
                    FeederItem(code, item.optString("FEEDER_NAME", ""), item.optString("FEEDER_CATEGORY", ""))
                }
            } else {
                throw Exception(ApiErrorHandler.fromErrorStream(connection.errorStream, connection.responseCode))
            }
        }

    // ============================================================
    // UI UPDATES
    // ============================================================

    private fun updateCalendarUI() {
        val calCopy = currentCalendar.clone() as Calendar
        calCopy.set(Calendar.DAY_OF_MONTH, 1)
        val todayStr = dateFormat.format(Calendar.getInstance().time)
        val daysInMonth = calCopy.getActualMaximum(Calendar.DAY_OF_MONTH)

        val firstDayOfWeek = calCopy.get(Calendar.DAY_OF_WEEK)
        val offset = if (firstDayOfWeek == Calendar.SUNDAY) 6 else firstDayOfWeek - 2

        val calendarItems = mutableListOf<CalendarItem>()
        repeat(offset) { calendarItems.add(CalendarItem.Empty) }

        for (day in 1..daysInMonth) {
            calCopy.set(Calendar.DAY_OF_MONTH, day)
            val dateStr = dateFormat.format(calCopy.time)
            val isFuture = dateStr > todayStr
            val status = if (isFuture) DayStatus.FUTURE else dayStatusMap[dateStr]?.status ?: DayStatus.MISSING
            val dayInfo = dayStatusMap[dateStr] ?: DayStatusInfo(dateStr, status)
            calendarItems.add(CalendarItem.Day(day, dayInfo))
        }

        calendarAdapter.updateItems(calendarItems)

        countFull    = dayStatusMap.values.count { it.status == DayStatus.FULLY_FILLED }
        countPartial = dayStatusMap.values.count { it.status == DayStatus.PARTIAL }
        countMissing = dayStatusMap.values.count { it.status == DayStatus.MISSING }

        binding.tvLegendFull.text    = "✅ Fully Filled: $countFull"
        // Point 5: Partial is now red — data not completely filled
        binding.tvLegendPartial.text = "🔴 Partial (Incomplete): $countPartial"
        binding.tvLegendMissing.text = "❌ Missing: $countMissing"
    }

    private fun updateTrendChart() {
        val sorted = dayStatusMap.entries
            .filter { it.value.status != DayStatus.FUTURE }
            .sortedBy { it.key }

        if (sorted.isEmpty()) return

        val points = sorted.map { (dateStr, info) ->
            val dayNum = dateStr.substring(8).toIntOrNull() ?: 0
            val total = info.totalFeeders.coerceAtLeast(1)

            val hourlyLevel = when {
                info.filledHourly == total -> 2f
                info.filledHourly > 0     -> 1f
                else                      -> 0f
            }
            val dailyLevel = when {
                info.filledDaily == total -> 2f
                info.filledDaily > 0     -> 1f
                else                     -> 0f
            }
            TrendDataPoint(dayNum, hourlyLevel, dailyLevel)
        }

        binding.lineTrendChart.setStationName(getStationNameFromSession())
        binding.lineTrendChart.setData(points)
    }

    // ============================================================
    // NAVIGATION
    // ============================================================

    private fun navigateToEdit(dayInfo: DayStatusInfo) {
        if (dayInfo.status == DayStatus.FULLY_FILLED) {
            showDayDetailDialog(dayInfo)
            return
        }
        findNavController().navigate(
            R.id.action_feederstatusFragment_to_editMissingDataFragment,
            bundleOf(
                "date" to dayInfo.date,
                "missing_hourly_feeders" to dayInfo.missingHourlyFeeders.toTypedArray(),
                "missing_daily_feeders" to dayInfo.missingDailyFeeders.toTypedArray(),
                "total_feeders" to dayInfo.totalFeeders
            )
        )
    }

    private fun showDayDetailDialog(dayInfo: DayStatusInfo) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("✅ ${dayInfo.date} - Fully Filled")
            .setMessage(
                "All feeders have complete data.\n\n" +
                        "Hourly: ${dayInfo.filledHourly}/${dayInfo.totalFeeders} feeders\n" +
                        "Daily: ${dayInfo.filledDaily}/${dayInfo.totalFeeders} feeders"
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Edit Anyway") { _, _ ->
                findNavController().navigate(
                    R.id.action_feederstatusFragment_to_editMissingDataFragment,
                    bundleOf(
                        "date" to dayInfo.date,
                        "missing_hourly_feeders" to emptyArray<String>(),
                        "missing_daily_feeders" to emptyArray<String>(),
                        "total_feeders" to dayInfo.totalFeeders
                    )
                )
            }
            .show()
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.rvCalendar.visibility  = if (show) View.INVISIBLE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ============================================================
// CALENDAR SEALED CLASS + ADAPTER
// ============================================================

sealed class CalendarItem {
    object Empty : CalendarItem()
    data class Day(val dayNumber: Int, val statusInfo: DayStatusInfo) : CalendarItem()
}

class CalendarDayAdapter(
    private var items: List<CalendarItem>,
    private val onDayClick: (DayStatusInfo) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_EMPTY = 0
        private const val TYPE_DAY = 1
    }

    fun updateItems(newItems: List<CalendarItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) =
        if (items[position] is CalendarItem.Empty) TYPE_EMPTY else TYPE_DAY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_EMPTY)
            EmptyViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_empty, parent, false))
        else
            DayViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_day, parent, false), onDayClick)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is DayViewHolder && item is CalendarItem.Day) holder.bind(item)
    }

    override fun getItemCount() = items.size

    class EmptyViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class DayViewHolder(
        view: View,
        private val onDayClick: (DayStatusInfo) -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val tvDay: TextView = view.findViewById(R.id.tvDayNumber)
        private val tvStatus: TextView = view.findViewById(R.id.tvDayStatus)

        fun bind(item: CalendarItem.Day) {
            val info = item.statusInfo
            tvDay.text = item.dayNumber.toString()

            val (bgColor, statusText, textColor) = when (info.status) {
                DayStatus.FULLY_FILLED -> Triple(Color.parseColor("#4CAF50"), "✅", Color.BLACK)
                // Point 5: PARTIAL also shown in red — feeder data not fully filled
                DayStatus.PARTIAL      -> Triple(Color.parseColor("#F44336"), "⚠️", Color.WHITE)
                DayStatus.MISSING      -> Triple(Color.parseColor("#F44336"), "❌", Color.BLACK)
                DayStatus.FUTURE       -> Triple(Color.parseColor("#E0E0E0"), "",   Color.parseColor("#9E9E9E"))
                DayStatus.NO_DATA      -> Triple(Color.parseColor("#BDBDBD"), "?",  Color.BLACK)
            }

            itemView.setBackgroundColor(bgColor)
            tvDay.setTextColor(textColor)
            tvStatus.text = statusText
            tvStatus.setTextColor(textColor)

            itemView.setOnClickListener {
                if (info.status != DayStatus.FUTURE) onDayClick(info)
            }
        }
    }
}

// ============================================================
// TREND CHART DATA MODEL
// ============================================================

data class TrendDataPoint(
    val day: Int,
    val hourlyLevel: Float,
    val dailyLevel: Float
)

// ============================================================
// LINE TREND CHART — Custom View
// ============================================================

class LineTrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var dataPoints = listOf<TrendDataPoint>()
    private var stationName: String = "Monthly Status Trend"

    fun setStationName(name: String) {
        stationName = if (name.isNotEmpty()) name else "Monthly Status Trend"
        invalidate()
    }

    private val padL = 150f
    private val padR = 30f
    private val padT = 100f
    private val padB = 60f

    private val paintHourly = linePaint("#E91E8C")
    private val paintDaily  = linePaint("#1565C0")

    private fun linePaint(hex: String) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(hex)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val axisPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E"); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val gridPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEEEEE"); strokeWidth = 1f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555"); textSize = 24f; textAlign = Paint.Align.CENTER
    }
    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555"); textSize = 22f; textAlign = Paint.Align.CENTER
    }
    private val titlePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121"); textSize = 26f; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }

    fun setData(points: List<TrendDataPoint>) {
        dataPoints = points
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val chartW = w - padL - padR
        val chartH = h - padT - padB

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val yFull    = padT
        val yPartial = padT + chartH / 2f
        val yMissing = padT + chartH

        fun statusToY(level: Float) = padT + chartH - (level / 2f) * chartH

        val n = dataPoints.size
        fun dayToX(i: Int): Float =
            if (n <= 1) padL + chartW / 2f
            else padL + (i.toFloat() / (n - 1)) * chartW

        listOf(
            "FULL"      to yFull,
            "PARTIAL"   to yPartial,
            "NOT-START" to yMissing
        ).forEach { (label, y) ->
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
            canvas.drawLine(padL - 6f, y, padL, y, axisPaint)
            canvas.drawText(label, padL / 2f, y + 9f, yLabelPaint)
        }

        canvas.drawLine(padL, padT, padL, padT + chartH, axisPaint)
        canvas.drawLine(padL, padT + chartH, w - padR, padT + chartH, axisPaint)

        fun drawLine(paint: Paint, getValue: (TrendDataPoint) -> Float) {
            if (dataPoints.size < 2) return
            val path = Path()
            dataPoints.forEachIndexed { i, pt ->
                val x = dayToX(i)
                val y = statusToY(getValue(pt))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }

        drawLine(paintHourly) { it.hourlyLevel }
        drawLine(paintDaily)  { it.dailyLevel }

        dataPoints.forEachIndexed { i, pt ->
            val x = dayToX(i)
            listOf(
                Color.parseColor("#E91E8C") to pt.hourlyLevel,
                Color.parseColor("#1565C0") to pt.dailyLevel
            ).forEach { (color, level) ->
                dotPaint.color = color
                canvas.drawCircle(x, statusToY(level), 7f, dotPaint)
                dotPaint.color = Color.WHITE
                canvas.drawCircle(x, statusToY(level), 3f, dotPaint)
            }
        }

        dataPoints.forEachIndexed { i, pt ->
            if (i % 3 == 0 || i == dataPoints.lastIndex) {
                canvas.drawText(pt.day.toString(), dayToX(i), padT + chartH + 40f, xLabelPaint)
            }
        }

        canvas.drawText(stationName, padL + chartW / 2f, 32f, titlePaint)

        val legendColors = listOf(
            "#E91E8C" to "Hourly Status",
            "#1565C0" to "Daily Status"
        )
        val legendPaintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; style = Paint.Style.STROKE }
        val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; textAlign = Paint.Align.LEFT }

        val totalLegendWidth = 2 * 170f
        var lx = padL + (chartW - totalLegendWidth) / 2f
        val ly = padT - 52f

        legendColors.forEach { (hex, label) ->
            legendPaintLine.color = Color.parseColor(hex)
            legendTextPaint.color = Color.parseColor(hex)
            canvas.drawLine(lx, ly, lx + 28f, ly, legendPaintLine)
            dotPaint.color = Color.parseColor(hex)
            canvas.drawCircle(lx + 14f, ly, 5f, dotPaint)
            canvas.drawText(label, lx + 34f, ly + 8f, legendTextPaint)
            lx += 170f
        }
    }
}