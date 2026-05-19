package com.apc.kptcl.home.users.division

import android.app.DatePickerDialog
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentDivisionDailyViewBinding
import com.apc.kptcl.databinding.ItemDailyReadonlyBinding
import com.apc.kptcl.utils.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class DivisionDailyViewFragment : Fragment() {

    private var _binding: FragmentDivisionDailyViewBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val TAG = "DivisionDailyView"
        private const val TIMEOUT = 15000
        private const val MAX_DISPLAY = 50  // ✅ Screen pe max 50 rows
    }

    private var selectedDate    = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    private var selectedStation = ""
    private var stationList     = listOf<String>()

    private val rowList     = mutableListOf<DailyRow>() // Screen pe dikhne wale (max 50)
    private val fullRowList = mutableListOf<DailyRow>() // Saare rows (Excel ke liye)
    private lateinit var adapter: DailyReadOnlyAdapter

    data class DailyRow(
        val stationName: String,  // ✅ ALL mode ke liye
        val feederName: String,
        val totalConsumption: String,
        val supply1pm: String,
        val supply2pm: String
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDivisionDailyViewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val stations = arguments?.getStringArrayList(DivisionHomeFragment.KEY_STATIONS)
        stationList = stations ?: emptyList()

        setupBackButton()
        setupDatePicker()
        updateDateDisplay()
        setupStationInput()
        setupRecyclerView()
        setupSearchButton()
        setupDownloadButton()
    }

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            findNavController().navigate(R.id.divisionHomeFragment, null,
                navOptions { popUpTo(R.id.divisionHomeFragment) { inclusive = true } })
        }
    }

    private fun setupDatePicker() {
        binding.btnPickDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(requireContext(), { _, y, m, d ->
                selectedDate = String.format("%04d-%02d-%02d", y, m + 1, d)
                updateDateDisplay()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
    }

    private fun updateDateDisplay() {
        try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(selectedDate)
            binding.tvSelectedDate.text =
                SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(parsed!!)
        } catch (e: Exception) { binding.tvSelectedDate.text = selectedDate }
    }

    private fun setupStationInput() {
        if (stationList.isNotEmpty()) {
            binding.spinnerStation.visibility = View.VISIBLE
            binding.etStation.visibility      = View.GONE

            // ✅ "ALL" option add karo at top
            val spinnerItems = listOf("ALL") + stationList
            val sa = ArrayAdapter(requireContext(),
                android.R.layout.simple_spinner_item, spinnerItems)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            binding.spinnerStation.adapter = sa
            selectedStation = "ALL"  // Default = ALL

            binding.spinnerStation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedStation = spinnerItems[pos]
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        } else {
            binding.spinnerStation.visibility = View.GONE
            binding.etStation.visibility      = View.VISIBLE
            val session = SessionManager.getStationName(requireContext())
            if (session.isNotEmpty()) { binding.etStation.setText(session); selectedStation = session }
        }
    }

    private fun setupRecyclerView() {
        adapter = DailyReadOnlyAdapter(rowList)
        binding.rvDaily.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDaily.adapter = adapter
    }

    private fun setupSearchButton() {
        binding.btnSearch.setOnClickListener {
            selectedStation = if (stationList.isNotEmpty()) {
                val spinnerItems = listOf("ALL") + stationList
                spinnerItems.getOrElse(binding.spinnerStation.selectedItemPosition) { "ALL" }
            } else {
                binding.etStation.text.toString().trim()
            }
            loadDailyData()
        }
    }

    // ── EXCEL DOWNLOAD ────────────────────────────────────────────

    private fun setupDownloadButton() {
        binding.btnDownloadExcel.setOnClickListener {
            if (fullRowList.isEmpty()) {
                Toast.makeText(requireContext(), "No data to download", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            downloadExcel()
        }
    }

    private fun downloadExcel() {
        try {
            val fileName = "daily_consumption_${selectedStation}_${selectedDate}.csv"
            val csvContent = buildCsvContent()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = requireContext().contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    requireContext().contentResolver.openOutputStream(it)?.use { os ->
                        os.write(csvContent.toByteArray())
                    }
                    Toast.makeText(requireContext(),
                        "✅ Downloaded: $fileName\n(Check Downloads folder)",
                        Toast.LENGTH_LONG).show()
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = java.io.File(dir, fileName)
                file.writeText(csvContent)
                Toast.makeText(requireContext(),
                    "✅ Downloaded: $fileName",
                    Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excel download failed", e)
            Toast.makeText(requireContext(), "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildCsvContent(): String {
        val sb = StringBuilder()
        sb.appendLine("Station,Feeder Name,Total Consumption (kWh),Supply 3PH,Supply 1PH")
        for (row in fullRowList) {
            sb.appendLine("${row.stationName},${row.feederName},${row.totalConsumption},${row.supply1pm},${row.supply2pm}")
        }
        return sb.toString()
    }

    // ── LOAD DATA ────────────────────────────────────────────────

    private fun loadDailyData() {
        val token     = SessionManager.getToken(requireContext())
        val serverUrl = SessionManager.getServerUrl(requireContext())
        if (token.isEmpty() || serverUrl.isEmpty()) {
            Toast.makeText(requireContext(), "Session expired.", Toast.LENGTH_LONG).show()
            return
        }

        binding.progressBar.visibility      = View.VISIBLE
        binding.rvDaily.visibility          = View.GONE
        binding.tvEmpty.visibility          = View.GONE
        binding.tvResultTitle.visibility    = View.GONE
        binding.cardSummary.visibility      = View.GONE
        binding.btnDownloadExcel.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // ✅ ALL = saari stations ka data fetch karo
                val allRows = if (selectedStation == "ALL") {
                    val combined = mutableListOf<DailyRow>()
                    for (station in stationList) {
                        combined.addAll(fetchForStation(serverUrl, token, station))
                    }
                    combined
                } else {
                    fetchForStation(serverUrl, token, selectedStation)
                }

                if (_binding == null) return@launch
                binding.progressBar.visibility = View.GONE

                if (allRows.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                } else {
                    // ✅ Full list save karo (Excel ke liye)
                    fullRowList.clear()
                    fullRowList.addAll(allRows)

                    // ✅ Screen pe max 50 dikhao
                    rowList.clear()
                    rowList.addAll(allRows.take(MAX_DISPLAY))
                    adapter.notifyDataSetChanged()

                    val totalKwh = allRows.sumOf {
                        it.totalConsumption.replace(" kWh", "").toLongOrNull() ?: 0L
                    }
                    val kwhFmt = if (totalKwh >= 1000) "${totalKwh/1000}k" else totalKwh.toString()

                    binding.tvResultTitle.text =
                        "Showing ${rowList.size} of ${allRows.size} feeders · $selectedStation · $selectedDate"
                    binding.tvResultTitle.visibility    = View.VISIBLE
                    binding.cardSummary.visibility      = View.VISIBLE
                    binding.tvSummaryFeederCount.text   = allRows.size.toString()
                    binding.tvSummaryTotalKwh.text      = "$kwhFmt kWh"
                    binding.tvRowCount.text             = "${allRows.size} feeders"
                    binding.rvDaily.visibility          = View.VISIBLE
                    binding.btnDownloadExcel.visibility = View.VISIBLE  // ✅ Show download button
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading daily data", e)
                if (_binding == null) return@launch
                binding.progressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "Failed to load data.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ✅ Single station ka data fetch karo
    private suspend fun fetchForStation(serverUrl: String, token: String, station: String): List<DailyRow> =
        withContext(Dispatchers.IO) {
            try {
                val urlStr = "$serverUrl/api/feeder/daily/consumption" +
                        "?date=$selectedDate" +
                        "&station=${station.replace(" ", "%20")}"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                try {
                    conn.apply {
                        requestMethod = "GET"; connectTimeout = TIMEOUT; readTimeout = TIMEOUT
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Accept", "application/json")
                    }
                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                        parseDaily(body, station)
                    } else emptyList()
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                Log.w(TAG, "Fetch failed for $station: ${e.message}")
                emptyList()
            }
        }

    private fun parseDaily(json: String, stationName: String): List<DailyRow> {
        return try {
            val root  = JSONObject(json)
            val array = root.optJSONArray("data") ?: return emptyList()
            val rows  = mutableListOf<DailyRow>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                rows.add(DailyRow(
                    stationName      = stationName,
                    feederName       = o.optString("FEEDER_NAME", "—"),
                    totalConsumption = o.optLong("TOTAL_CONSUMPTION_KWH", 0L).toString(),
                    supply1pm        = o.optString("SUPPLY_3PH_HRS_MINS", "—"),
                    supply2pm        = o.optString("SUPPLY_1PH_HRS_MINS", "—")
                ))
            }
            rows
        } catch (e: Exception) { Log.e(TAG, "Parse error", e); emptyList() }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    // ── ADAPTER ───────────────────────────────────────────────────

    inner class DailyReadOnlyAdapter(private val items: List<DailyRow>) :
        RecyclerView.Adapter<DailyReadOnlyAdapter.VH>() {

        inner class VH(val b: ItemDailyReadonlyBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemDailyReadonlyBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            with(holder.b) {
                tvFeederName.text       = "${row.stationName} · ${row.feederName}"  // ✅ Station bhi dikhao
                tvTotalConsumption.text = "${row.totalConsumption} kWh"
                tvSupply1pm.text        = row.supply1pm
                tvSupply2pm.text        = row.supply2pm
            }
        }
    }
}