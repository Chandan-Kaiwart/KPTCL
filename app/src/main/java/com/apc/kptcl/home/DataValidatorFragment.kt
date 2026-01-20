package com.apc.kptcl.home

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentDataValidatorBinding
import com.apc.kptcl.databinding.ItemStationValidatorBinding
import com.apc.kptcl.utils.JWTUtils
import com.apc.kptcl.utils.SessionManager
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*

class DataValidatorFragment : Fragment() {

    private var _binding: FragmentDataValidatorBinding? = null
    private val binding get() = _binding!!

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    private val apiDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private lateinit var stationAdapter: StationValidatorAdapter

    // ✅ Store raw API data
    private var allReportsData: List<ExceptionalReportItem> = emptyList()
    private var processedStations: List<StationValidatorItem> = emptyList()

    // ✅ Store adapter reference to avoid crash
    private var filterAdapter: ArrayAdapter<String>? = null

    // ✅ API setup
    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("http://62.72.59.119:7000/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val apiService by lazy {
        retrofit.create(ExceptionalReportApiService::class.java)
    }

    companion object {
        private const val TAG = "DataValidatorFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDataValidatorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ✅ Check if user is DCC
        if (!isDCCUser()) {
            Toast.makeText(context, "This feature is only available for DCC users", Toast.LENGTH_LONG).show()
            activity?.onBackPressedDispatcher?.onBackPressed()
            return
        }

        setupToolbar()
        setupDatePicker()
        setupFilterDropdown()
        setupRecyclerView()
        setupClickListeners()
        updateDateDisplay()

        // ✅ Load data on fragment creation
        loadExceptionalReports()
    }

    /**
     * ✅ Check if logged-in user is DCC
     */
    private fun isDCCUser(): Boolean {
        val token = SessionManager.getToken(requireContext())
        val payload = JWTUtils.decodeToken(token)
        return payload?.role?.lowercase() == "dcc"
    }

    private fun setupToolbar() {
//        binding.toolbar.setNavigationOnClickListener {
//            activity?.onBackPressedDispatcher?.onBackPressed()
//        }
    }

    private fun setupDatePicker() {
        binding.etDate.setOnClickListener {
            showDatePicker()
        }
    }

    private fun showDatePicker() {
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                updateDateDisplay()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateDateDisplay() {
        binding.etDate.setText(dateFormat.format(calendar.time))
        binding.tvDataDate.text = "${apiDateFormat.format(calendar.time)}"
    }

    /**
     * ✅ FIXED: Setup filter dropdown properly to avoid crash
     */
    private fun setupFilterDropdown() {
        val stations = mutableListOf("All")
        filterAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, stations)
        binding.actvStationFilter.setAdapter(filterAdapter)
        binding.actvStationFilter.setText("All", false)

        // ✅ FIXED: Use text change listener instead of item click
        binding.actvStationFilter.setOnItemClickListener { parent, _, position, _ ->
            try {
                val selectedStation = parent.getItemAtPosition(position) as? String ?: "All"
                filterByStation(selectedStation)
            } catch (e: Exception) {
                Log.e(TAG, "Error in filter selection", e)
                filterByStation("All")
            }
        }
    }

    /**
     * ✅ FIXED: Update filter dropdown safely
     */
    private fun updateFilterDropdown() {
        try {
            val uniqueStations = processedStations.map { it.stationName }.distinct().sorted()
            val stations = mutableListOf("All") + uniqueStations

            // ✅ Create new adapter
            filterAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, stations)
            binding.actvStationFilter.setAdapter(filterAdapter)

            Log.d(TAG, "Filter dropdown updated with ${stations.size} stations")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating filter dropdown", e)
        }
    }

    private fun filterByStation(station: String) {
        if (station == "All") {
            stationAdapter.submitList(processedStations)
        } else {
            val filtered = processedStations.filter { it.stationName == station }
            stationAdapter.submitList(filtered)
        }
    }

    private fun setupRecyclerView() {
        stationAdapter = StationValidatorAdapter()
        binding.rvStations.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = stationAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnSearch.setOnClickListener {
            searchDataByDate()
        }

//        binding.tvDownloadFilled.setOnClickListener {
//            downloadCSV("filled")
//        }
//
//        binding.tvDownloadPartial.setOnClickListener {
//            downloadCSV("partial")
//        }
//
//        binding.tvDownloadNotStarted.setOnClickListener {
//            downloadCSV("not_started")
//        }

        binding.cardFilledComplete.setOnClickListener {
            filterByStatus("FILLED COMPLETELY")
        }

        binding.cardPartialFilled.setOnClickListener {
            filterByStatus("PARTIALLY FILLED")
        }

        binding.cardNotStarted.setOnClickListener {
            filterByStatus("NOT STARTED YET")
        }
    }

    /**
     * ✅ Filter stations by status
     */
    private fun filterByStatus(status: String) {
        val filtered = processedStations.filter { it.status == status }
        stationAdapter.submitList(filtered)
        Toast.makeText(context, "Showing $status stations (${filtered.size})", Toast.LENGTH_SHORT).show()
    }

    /**
     * ✅ Search data by selected date
     */
    private fun searchDataByDate() {
        val selectedDate = apiDateFormat.format(calendar.time)
        Log.d(TAG, "Searching data for date: $selectedDate")

        binding.btnSearch.isEnabled = false
        binding.btnSearch.text = "Loading..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())
                val response = apiService.getReportsByDate("Bearer $token", selectedDate)

                if (response.isSuccessful && response.body() != null) {
                    val apiResponse = response.body()!!

                    if (apiResponse.success) {
                        allReportsData = apiResponse.data
                        processAndDisplayData()
                        Toast.makeText(context, "Data loaded for $selectedDate", Toast.LENGTH_SHORT).show()
                    } else {
                        showError("No data found for selected date")
                    }
                } else {
                    showError("Error: ${response.code()} - ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching reports by date", e)
                showError("Network error: ${e.message}")
            } finally {
                binding.btnSearch.isEnabled = true
                binding.btnSearch.text = "Search"
            }
        }
    }

    /**
     * ✅ Load all exceptional reports from API
     */
    private fun loadExceptionalReports() {
        Log.d(TAG, "Loading exceptional reports...")

        // Show loading state
        binding.btnSearch.isEnabled = false
        binding.btnSearch.text = "Loading..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                if (token.isEmpty()) {
                    showError("Session expired. Please login again.")
                    return@launch
                }

                Log.d(TAG, "Calling API with token: ${token.take(50)}...")

                val response = apiService.getAllReports("Bearer $token")

                Log.d(TAG, "Response code: ${response.code()}")

                if (response.isSuccessful && response.body() != null) {
                    val apiResponse = response.body()!!

                    Log.d(TAG, "Success: ${apiResponse.success}, Count: ${apiResponse.count}")

                    if (apiResponse.success) {
                        allReportsData = apiResponse.data
                        processAndDisplayData()

                        Toast.makeText(
                            context,
                            "✅ Loaded ${apiResponse.count} exceptional reports",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        showError("API returned success=false")
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "API Error: ${response.code()} - $errorBody")
                    showError("Error: ${response.code()} - ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error loading reports", e)
                showError("Network error: ${e.message}")
            } finally {
                binding.btnSearch.isEnabled = true
                binding.btnSearch.text = "Search"
            }
        }
    }

    /**
     * ✅ Process raw API data and calculate station-wise statistics
     */
    private fun processAndDisplayData() {
        Log.d(TAG, "Processing ${allReportsData.size} records...")

        // Group by station
        val stationMap = allReportsData.groupBy { it.STATION_NAME }

        val stations = mutableListOf<StationValidatorItem>()

        stationMap.forEach { (stationName, reports) ->
            // Separate reports by table type
            val hourlyReports = reports.filter { it.TABLENAME.contains("hourly", ignoreCase = true) }
            val consumptionReports = reports.filter { it.TABLENAME.contains("consumption", ignoreCase = true) }

            // Calculate status for each table
            val hourlyStatus = calculateTableStatus(hourlyReports)
            val consumptionStatus = calculateTableStatus(consumptionReports)

            // Overall station status
            val overallStatus = when {
                hourlyStatus == "FULL" && consumptionStatus == "FULL" -> "FILLED COMPLETELY"
                hourlyStatus == "NOT STARTED" && consumptionStatus == "NOT STARTED" -> "NOT STARTED YET"
                else -> "PARTIALLY FILLED"
            }

            stations.add(
                StationValidatorItem(
                    stationName = stationName,
                    feederHourly = hourlyStatus,
                    feederConsumption = consumptionStatus,
                    status = overallStatus
                )
            )
        }

        processedStations = stations.sortedBy { it.stationName }

        // Update UI
        stationAdapter.submitList(processedStations)
        updateFilterDropdown()
        updateSummaryCards()

        Log.d(TAG, "Processed ${stations.size} unique stations")
    }

    /**
     * ✅ Calculate status for a table based on reports
     */
    private fun calculateTableStatus(reports: List<ExceptionalReportItem>): String {
        if (reports.isEmpty()) return "NOT STARTED"

        val missingDateCount = reports.count { it.MISSINGDATE == "YES" }

        return when {
            missingDateCount == 0 -> "FULL"
            missingDateCount == reports.size -> "NOT STARTED"
            else -> "PARTIAL"
        }
    }

    /**
     * ✅ Update summary cards with calculated statistics
     */
    private fun updateSummaryCards() {
        val totalStations = processedStations.size
        val filledCount = processedStations.count { it.status == "FILLED COMPLETELY" }
        val partialCount = processedStations.count { it.status == "PARTIALLY FILLED" }
        val notStartedCount = processedStations.count { it.status == "NOT STARTED YET" }

        val completionRate = if (totalStations > 0) {
            (filledCount.toFloat() / totalStations) * 100
        } else {
            0f
        }

        updateSummary(
            totalStations = totalStations,
            filledCount = filledCount,
            partialCount = partialCount,
            notStartedCount = notStartedCount,
            completionRate = completionRate
        )
    }

    private fun downloadCSV(type: String) {
        val dataToExport = when (type) {
            "filled" -> processedStations.filter { it.status == "FILLED COMPLETELY" }
            "partial" -> processedStations.filter { it.status == "PARTIALLY FILLED" }
            "not_started" -> processedStations.filter { it.status == "NOT STARTED YET" }
            else -> processedStations
        }

        Toast.makeText(context, "Downloading $type CSV... (${dataToExport.size} records)", Toast.LENGTH_SHORT).show()
        // TODO: Implement actual CSV download
    }

    private fun updateSummary(
        totalStations: Int,
        filledCount: Int,
        partialCount: Int,
        notStartedCount: Int,
        completionRate: Float
    ) {
        binding.tvTotalStations.text = "$totalStations"
        binding.tvFilledCount.text = filledCount.toString()
        binding.tvPartialCount.text = partialCount.toString()
        binding.tvNotStartedCount.text = notStartedCount.toString()
        binding.tvCompletionRate.text = String.format("%.1f%%", completionRate)
        binding.progressCompletion.progress = completionRate.toInt()
    }

    private fun showError(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        Log.e(TAG, message)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==========================================
    // DATA CLASSES
    // ==========================================

    /**
     * Processed station data for display
     */
    data class StationValidatorItem(
        val stationName: String,
        val feederHourly: String,      // FULL, PARTIAL, NOT STARTED
        val feederConsumption: String, // FULL, PARTIAL, NOT STARTED
        val status: String              // FILLED COMPLETELY, PARTIALLY FILLED, NOT STARTED YET
    )

    // ==========================================
    // ADAPTER
    // ==========================================

    inner class StationValidatorAdapter : RecyclerView.Adapter<StationValidatorAdapter.ViewHolder>() {

        private var items: List<StationValidatorItem> = emptyList()

        fun submitList(newItems: List<StationValidatorItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemStationValidatorBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemStationValidatorBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: StationValidatorItem) {
                binding.tvStationName.text = item.stationName
                binding.tvFeederHourly.text = item.feederHourly
                binding.tvFeederConsumption.text = item.feederConsumption
                binding.tvStatus.text = item.status

                // Set status color
                val statusColor = when (item.status) {
                    "FILLED COMPLETELY" -> android.graphics.Color.parseColor("#4CAF50")
                    "PARTIALLY FILLED" -> android.graphics.Color.parseColor("#FFC107")
                    "NOT STARTED YET" -> android.graphics.Color.parseColor("#F44336")
                    else -> android.graphics.Color.parseColor("#666666")
                }
                binding.tvStatus.setTextColor(statusColor)

                // Set feeder hourly color
                val hourlyColor = when (item.feederHourly) {
                    "FULL" -> android.graphics.Color.parseColor("#4CAF50")
                    "PARTIAL" -> android.graphics.Color.parseColor("#FFC107")
                    else -> android.graphics.Color.parseColor("#666666")
                }
                binding.tvFeederHourly.setTextColor(hourlyColor)

                // Set consumption color
                val consumptionColor = when (item.feederConsumption) {
                    "FULL" -> android.graphics.Color.parseColor("#4CAF50")
                    "PARTIAL" -> android.graphics.Color.parseColor("#FFC107")
                    else -> android.graphics.Color.parseColor("#666666")
                }
                binding.tvFeederConsumption.setTextColor(consumptionColor)
            }
        }
    }
}