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
import com.apc.kptcl.databinding.FragmentDataValidatorBinding
import com.apc.kptcl.databinding.ItemStationValidatorBinding
import com.apc.kptcl.utils.JWTUtils
import com.apc.kptcl.utils.SessionManager
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*
import androidx.navigation.fragment.findNavController
// NOTE: ExceptionalReportApiService, ValidatorApiResponse, StationStatusItem
//       are all defined in ExceptionalReportApiService.kt — NO duplicates here

class DataValidatorFragment : Fragment() {

    private var _binding: FragmentDataValidatorBinding? = null
    private val binding get() = _binding!!

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    private val apiDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private lateinit var stationAdapter: StationValidatorAdapter

    private var processedStations: List<StationValidatorItem> = emptyList()
    private var filterAdapter: ArrayAdapter<String>? = null

    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("http://31.97.237.169:9009/")
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

        if (!isAllowedUser()) {
            Toast.makeText(context, "This feature is only available for DCC/Division users", Toast.LENGTH_LONG).show()
            activity?.onBackPressedDispatcher?.onBackPressed()
            return
        }
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }
        setupDatePicker()
        setupFilterDropdown()
        setupRecyclerView()
        setupClickListeners()
        updateDateDisplay()
        loadData()
    }

    // ==========================================
    // USER ROLE CHECK
    // ==========================================

    private fun isAllowedUser(): Boolean {
        val token = SessionManager.getToken(requireContext())
        val payload = JWTUtils.decodeToken(token)
        val role = payload?.role?.lowercase()
        return role == "dcc" || role == "division"
    }

    // ==========================================
    // SETUP
    // ==========================================

    private fun setupDatePicker() {
        binding.etDate.setOnClickListener {
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
    }

    private fun updateDateDisplay() {
        binding.etDate.setText(dateFormat.format(calendar.time))
        binding.tvDataDate.text = apiDateFormat.format(calendar.time)
    }

    private fun setupFilterDropdown() {
        val stations = mutableListOf("All")
        filterAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, stations)
        binding.actvStationFilter.setAdapter(filterAdapter)
        binding.actvStationFilter.setText("All", false)

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

    private fun updateFilterDropdown() {
        try {
            val uniqueStations = processedStations.map { it.stationName }.distinct().sorted()
            val stations = mutableListOf("All") + uniqueStations
            filterAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, stations)
            binding.actvStationFilter.setAdapter(filterAdapter)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating filter dropdown", e)
        }
    }

    private fun setupRecyclerView() {
        stationAdapter = StationValidatorAdapter()
        binding.rvStations.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            adapter = stationAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnSearch.setOnClickListener { loadData() }

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

    // ==========================================
    // FILTER
    // ==========================================

    private fun filterByStation(station: String) {
        if (station == "All") {
            stationAdapter.submitList(processedStations)
        } else {
            stationAdapter.submitList(processedStations.filter { it.stationName == station })
        }
    }

    private fun filterByStatus(status: String) {
        val filtered = processedStations.filter { it.status == status }
        stationAdapter.submitList(filtered)
        Toast.makeText(context, "Showing: $status (${filtered.size})", Toast.LENGTH_SHORT).show()
    }

    // ==========================================
    // API CALL
    // ==========================================

    private fun loadData() {
        val selectedDate = apiDateFormat.format(calendar.time)
        Log.d(TAG, "Loading data for date: $selectedDate")

        binding.btnSearch.isEnabled = false
        binding.btnSearch.text = "Loading..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                if (token.isEmpty()) {
                    showError("Session expired. Please login again.")
                    return@launch
                }

                val response = apiService.getValidatorSummary("Bearer $token", selectedDate)
                Log.d(TAG, "Response code: ${response.code()}")

                if (response.isSuccessful && response.body() != null) {
                    val apiResponse = response.body()!!
                    Log.d(TAG, "Success: ${apiResponse.success}, Count: ${apiResponse.count}")

                    if (apiResponse.success) {
                        if (apiResponse.data.isEmpty()) {
                            showError("No data found for $selectedDate")
                            resetUI()
                        } else {
                            processAndDisplayData(apiResponse.data)
                            Toast.makeText(context, "Loaded ${apiResponse.count} stations", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        showError("API returned success=false")
                        resetUI()
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "API Error: ${response.code()} - $errorBody")
                    showError("Error: ${response.code()} - ${response.message()}")
                    resetUI()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                showError("Network error: ${e.message}")
                resetUI()
            } finally {
                binding.btnSearch.isEnabled = true
                binding.btnSearch.text = "Search"
            }
        }
    }

    // ==========================================
    // DATA PROCESSING
    // ==========================================

    private fun processAndDisplayData(data: List<StationStatusItem>) {
        Log.d(TAG, "Processing ${data.size} station records...")

        val stations = data.map { item ->

            val feederHourly = when (item.HOURLY_STATUS.uppercase().trim()) {
                "FILLED"       -> "FULL"
                "MISSING"      -> "NOT STARTED"
                "PARTIAL DATA" -> "PARTIAL DATA"
                else           -> "PARTIAL DATA"
            }

            val feederConsumption = when (item.DAILY_STATUS.uppercase().trim()) {
                "FILLED"       -> "FULL"
                "MISSING"      -> "NOT STARTED"
                "PARTIAL DATA" -> "PARTIAL DATA"
                else           -> "PARTIAL DATA"
            }

            val overallStatus = when {
                item.HOURLY_STATUS.uppercase() == "FILLED" &&
                        item.DAILY_STATUS.uppercase()  == "FILLED"  -> "FILLED COMPLETELY"

                item.HOURLY_STATUS.uppercase() == "MISSING" &&
                        item.DAILY_STATUS.uppercase()  == "MISSING"  -> "NOT STARTED YET"

                else -> "PARTIALLY FILLED"
            }

            StationValidatorItem(
                stationName       = item.STATION_NAME,
                feederHourly      = feederHourly,
                feederConsumption = feederConsumption,
                status            = overallStatus
            )
        }.sortedBy { it.stationName }

        processedStations = stations
        stationAdapter.submitList(processedStations)
        updateFilterDropdown()
        updateSummaryCards()

        Log.d(TAG, "Processed ${stations.size} stations successfully")
    }

    // ==========================================
    // SUMMARY CARDS
    // ==========================================

    private fun updateSummaryCards() {
        val totalStations   = processedStations.size
        val filledCount     = processedStations.count { it.status == "FILLED COMPLETELY" }
        val partialCount    = processedStations.count { it.status == "PARTIALLY FILLED" }
        val notStartedCount = processedStations.count { it.status == "NOT STARTED YET" }

        val completionRate = if (totalStations > 0) {
            (filledCount.toFloat() / totalStations) * 100
        } else 0f

        binding.tvTotalStations.text        = totalStations.toString()
        binding.tvFilledCount.text          = filledCount.toString()
        binding.tvPartialCount.text         = partialCount.toString()
        binding.tvNotStartedCount.text      = notStartedCount.toString()
        binding.tvCompletionRate.text       = String.format("%.1f%%", completionRate)
        binding.progressCompletion.progress = completionRate.toInt()
    }

    private fun resetUI() {
        processedStations = emptyList()
        stationAdapter.submitList(emptyList())
        binding.tvTotalStations.text        = "0"
        binding.tvFilledCount.text          = "0"
        binding.tvPartialCount.text         = "0"
        binding.tvNotStartedCount.text      = "0"
        binding.tvCompletionRate.text       = "0.0%"
        binding.progressCompletion.progress = 0
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
    // DATA CLASS — Display model only
    // ==========================================

    data class StationValidatorItem(
        val stationName: String,
        val feederHourly: String,       // FULL / PARTIAL DATA / NOT STARTED
        val feederConsumption: String,  // FULL / PARTIAL DATA / NOT STARTED
        val status: String              // FILLED COMPLETELY / PARTIALLY FILLED / NOT STARTED YET
    )

    // ==========================================
    // RECYCLERVIEW ADAPTER
    // ==========================================

    inner class StationValidatorAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<StationValidatorAdapter.ViewHolder>() {

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
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

            fun bind(item: StationValidatorItem) {
                binding.tvStationName.text       = item.stationName
                binding.tvFeederHourly.text      = item.feederHourly
                binding.tvFeederConsumption.text = item.feederConsumption
                binding.tvStatus.text            = item.status

                binding.tvStatus.setTextColor(
                    when (item.status) {
                        "FILLED COMPLETELY" -> android.graphics.Color.parseColor("#4CAF50")
                        "PARTIALLY FILLED"  -> android.graphics.Color.parseColor("#FFC107")
                        "NOT STARTED YET"   -> android.graphics.Color.parseColor("#F44336")
                        else                -> android.graphics.Color.parseColor("#666666")
                    }
                )

                binding.tvFeederHourly.setTextColor(
                    when (item.feederHourly) {
                        "FULL"         -> android.graphics.Color.parseColor("#4CAF50")
                        "PARTIAL DATA" -> android.graphics.Color.parseColor("#FFC107")
                        "NOT STARTED"  -> android.graphics.Color.parseColor("#F44336")
                        else           -> android.graphics.Color.parseColor("#666666")
                    }
                )

                binding.tvFeederConsumption.setTextColor(
                    when (item.feederConsumption) {
                        "FULL"         -> android.graphics.Color.parseColor("#4CAF50")
                        "PARTIAL DATA" -> android.graphics.Color.parseColor("#FFC107")
                        "NOT STARTED"  -> android.graphics.Color.parseColor("#F44336")
                        else           -> android.graphics.Color.parseColor("#666666")
                    }
                )
            }
        }
    }
}