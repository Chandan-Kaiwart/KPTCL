package com.apc.kptcl.home.users.division

// FILE: DivisionHomeFragment.kt
// PATH: app/src/main/java/com/apc/kptcl/home/DivisionHomeFragment.kt

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.apc.kptcl.MainActivity
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentDivisionHomeBinding
import com.apc.kptcl.utils.JWTUtils
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

class DivisionHomeFragment : Fragment() {

    private var _binding: FragmentDivisionHomeBinding? = null
    private val binding get() = _binding!!

    private var divisionStations: List<String> = emptyList()

    // Selected date for kWh — default = today
    private var selectedDateCal: Calendar = Calendar.getInstance()

    companion object {
        private const val TAG = "DivisionHomeFragment"
        private const val TIMEOUT = 15000
        const val KEY_STATIONS = "division_stations"
    }

    // yyyy-MM-dd format for API
    private val apiDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    // dd/MM/yyyy format for display
    private val displayDateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDivisionHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        disableDrawer()
        displayUserInfo()
        updateDateTime()
        setupCardListeners()
        setupLogoutButton()
        setupDatePicker()
        loadStats()
    }

    // ── DRAWER ───────────────────────────────────────────────────

    private fun disableDrawer() {
        try { (activity as? MainActivity)?.lockDrawer() }
        catch (e: Exception) { Log.e(TAG, "Error locking drawer", e) }
    }

    // ── HEADER ───────────────────────────────────────────────────

    private fun displayUserInfo() {
        try {
            val token = SessionManager.getToken(requireContext())
            if (token.isEmpty()) return
            val payload = JWTUtils.decodeToken(token) ?: return

            binding.tvUsername.text    = "User: ${payload.username}"
            binding.tvRole.text        = "Role: Division"
            binding.tvEscom.text       = "ESCOM: ${payload.escom}"
            binding.tvTokenExpiry.text = "Expires: ${JWTUtils.getExpiryTime(token)}"

            val isExpired = JWTUtils.isTokenExpired(token)
            if (isExpired) {
                binding.tvTokenStatus.text = "● Session Expired"
                binding.tvTokenStatus.setBackgroundColor(0xFFC62828.toInt())
            } else {
                binding.tvTokenStatus.text = "● Session Active"
                binding.tvTokenStatus.setBackgroundColor(0xFF2E7D32.toInt())
            }
            binding.tvTokenStatus.visibility = View.VISIBLE

        } catch (e: Exception) { Log.e(TAG, "Error displaying user info", e) }
    }

    private fun updateDateTime() {
        val now = Date()
        binding.tvDate.text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(now)
        binding.tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
    }

    // ── DATE PICKER ──────────────────────────────────────────────

    private fun setupDatePicker() {
        // Set initial label to "Today"
        binding.tvSelectedDate.text = "Today"

        binding.layoutDatePicker.setOnClickListener {
            val cal = selectedDateCal
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    selectedDateCal.set(year, month, day)
                    val displayLabel = displayDateFmt.format(selectedDateCal.time)
                    binding.tvSelectedDate.text = displayLabel

                    // Update kWh label and reload kWh for selected date
                    val apiDate = apiDateFmt.format(selectedDateCal.time)
                    binding.tvKwhLabel.text = "kWh ($displayLabel)"
                    binding.tvKwhToday.text = "..."
                    loadKwhForDate(apiDate)
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).apply {
                // Max date = today, no future dates
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
    }

    // ── STATS — /api/division/stats + /api/division/stations ─────

    private fun loadStats() {
        val token     = SessionManager.getToken(requireContext())
        val serverUrl = SessionManager.getServerUrl(requireContext())
        if (token.isEmpty() || serverUrl.isEmpty()) return

        lifecycleScope.launch {
            try {
                val stats   = fetchDivisionStats(serverUrl, token)
                val tickets = fetchAllTickets(serverUrl, token)  // ✅ Add karo

                val totalCount  = tickets.size
                val activeCount = tickets.count { it.uppercase().contains("ACTIVE") }

                binding.tvTotalTickets.text   = totalCount.toString()   // ✅ 181
                binding.tvPendingTickets.text = activeCount.toString()  // ✅ Live/Active count
                binding.tvFeeders.text        = stats.stationCount.toString()

                divisionStations = fetchDivisionStations(serverUrl, token)
                val todayApi = apiDateFmt.format(Calendar.getInstance().time)
                loadKwhForDate(todayApi)

            } catch (e: Exception) {
                Log.e(TAG, "Error loading stats: ${e.message}", e)
            }
        }
    }

    private suspend fun fetchAllTickets(serverUrl: String, token: String): List<String> =
        withContext(Dispatchers.IO) {
            val conn = URL("$serverUrl/api/division/tickets").openConnection() as HttpURLConnection
            try {
                conn.apply {
                    requestMethod = "GET"; connectTimeout = TIMEOUT; readTimeout = TIMEOUT
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val arr  = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
                    (0 until arr.length()).map { i ->
                        arr.getJSONObject(i).optString("TICKET_STATUS", "")
                    }
                } else emptyList()
            } finally { conn.disconnect() }
        }

    private fun loadKwhForDate(date: String) {
        val token     = SessionManager.getToken(requireContext())
        val serverUrl = SessionManager.getServerUrl(requireContext())
        if (token.isEmpty() || serverUrl.isEmpty() || divisionStations.isEmpty()) {
            binding.tvKwhToday.text = "—"
            return
        }

        lifecycleScope.launch {
            try {
                var totalKwh = 0.0
                for (station in divisionStations) {
                    totalKwh += fetchStationKwh(serverUrl, token, station, date)
                }
                val display = if (totalKwh >= 1_000_000) {
                    String.format("%.2f MU", totalKwh / 1_000_000.0)
                } else if (totalKwh >= 1_000) {
                    String.format("%.1f K", totalKwh / 1_000.0)
                } else {
                    String.format("%.0f", totalKwh)
                }

                // ✅ FIX: Check karo ki fragment still alive hai
                if (_binding == null) return@launch

                binding.tvKwhToday.text = display

            } catch (e: Exception) {
                Log.e(TAG, "Error loading kWh: ${e.message}", e)

                // ✅ FIX: Yahan bhi check karo
                if (_binding == null) return@launch

                binding.tvKwhToday.text = "—"
            }
        }
    }

    private suspend fun fetchStationKwh(
        serverUrl: String, token: String, station: String, date: String
    ): Double = withContext(Dispatchers.IO) {
        try {
            val url = "$serverUrl/api/feeder/daily/consumption?date=$date&station=${
                java.net.URLEncoder.encode(station, "UTF-8")
            }"
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.apply {
                    requestMethod = "GET"; connectTimeout = TIMEOUT; readTimeout = TIMEOUT
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = JSONObject(body)
                    val arr  = json.optJSONArray("data") ?: return@withContext 0.0
                    var sum  = 0.0
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        sum += obj.optDouble("TOTAL_CONSUMPTION_KWH", 0.0)
                    }
                    sum
                } else 0.0
            } finally { conn.disconnect() }
        } catch (e: Exception) {
            Log.w(TAG, "kWh fetch failed for $station: ${e.message}")
            0.0
        }
    }

    data class DivisionStats(
        val totalTickets: Int,
        val pendingTickets: Int,
        val stationCount: Int
    )

    private suspend fun fetchDivisionStats(serverUrl: String, token: String): DivisionStats =
        withContext(Dispatchers.IO) {
            val conn = URL("$serverUrl/api/division/stats").openConnection() as HttpURLConnection
            try {
                conn.apply {
                    requestMethod = "GET"; connectTimeout = TIMEOUT; readTimeout = TIMEOUT
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = JSONObject(body)
                    val s    = json.optJSONObject("stats")
                    DivisionStats(
                        totalTickets   = s?.optInt("total_tickets",   0) ?: 0,
                        pendingTickets = s?.optInt("pending_tickets", 0) ?: 0,
                        stationCount   = s?.optInt("station_count",   0) ?: 0
                    )
                } else DivisionStats(0, 0, 0)
            } finally { conn.disconnect() }
        }

    private suspend fun fetchDivisionStations(serverUrl: String, token: String): List<String> =
        withContext(Dispatchers.IO) {
            val conn = URL("$serverUrl/api/division/stations").openConnection() as HttpURLConnection
            try {
                conn.apply {
                    requestMethod = "GET"; connectTimeout = TIMEOUT; readTimeout = TIMEOUT
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body  = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json  = JSONObject(body)
                    val array = json.optJSONArray("stations")
                    val list  = mutableListOf<String>()
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            val name = array.optString(i, "")
                            if (name.isNotEmpty()) list.add(name)
                        }
                    }
                    Log.d(TAG, "Parsed ${list.size} stations")
                    list
                } else {
                    Log.e(TAG, "Stations fetch failed: HTTP ${conn.responseCode}")
                    emptyList()
                }
            } finally { conn.disconnect() }
        }

    // ── NAV CARD CLICKS ──────────────────────────────────────────

    private fun setupCardListeners() {
        binding.cardViewReports.setOnClickListener {
            navigateWithStations(R.id.action_divisionHomeFragment_to_reportFragment)
        }
        binding.cardViewTickets.setOnClickListener {
            navigateWithStations(R.id.action_divisionHomeFragment_to_divisionTicketViewFragment)
        }
        binding.cardHourlyView.setOnClickListener {
            navigateWithStations(R.id.action_divisionHomeFragment_to_divisionHourlyViewFragment)
        }
        binding.cardDailyView.setOnClickListener {
            navigateWithStations(R.id.action_divisionHomeFragment_to_divisionDailyViewFragment)
        }
    }

    private fun navigate(actionId: Int, bundle: Bundle? = null) {
        try {
            findNavController().navigate(
                actionId, bundle,
                navOptions { popUpTo(R.id.divisionHomeFragment) { inclusive = false } }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Navigation error", e)
            showError("Unable to open screen")
        }
    }

    private fun navigateWithStations(actionId: Int) {
        val bundle = Bundle().apply {
            putStringArrayList(KEY_STATIONS, ArrayList(divisionStations))
        }
        navigate(actionId, bundle)
    }

    // ── LOGOUT ───────────────────────────────────────────────────

    private fun setupLogoutButton() {
        binding.logoutButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setPositiveButton("Yes") { _, _ -> performLogout() }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .setCancelable(true).show()
        }
    }

    private fun performLogout() {
        SessionManager.logout(requireContext())
        findNavController().navigate(R.id.loginFragment, null,
            navOptions { popUpTo(R.id.nav_graph) { inclusive = true }; launchSingleTop = true })
    }

    private fun showError(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}