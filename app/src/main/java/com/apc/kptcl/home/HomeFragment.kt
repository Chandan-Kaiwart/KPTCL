package com.apc.kptcl.home

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentHomeBinding
import com.apc.kptcl.home.users.ticket.confirmation.TicketApprovalAdapter
import com.apc.kptcl.home.users.ticket.confirmation.DCCApprovalFragment
import com.apc.kptcl.utils.JWTUtils
import com.apc.kptcl.utils.SessionManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
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
import com.apc.kptcl.MainActivity

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var ticketAdapter: TicketApprovalAdapter
    private var isDCCUser = false

    // ✅ CHANGE 3: Filter variables
    private var allTickets = mutableListOf<DCCApprovalFragment.PendingTicket>()
    private var selectedDate: String? = null
    private var selectedStation: String? = null

    companion object {
        private const val TAG = "HomeFragment"
        private const val VIEW_TICKETS_API = "http://62.72.59.119:7000/api/feeder/ticket/view/all"
        // ✅ Approve API - Updates both master and ticket status (PORT 5018)
        private const val APPROVE_API = "http://62.72.59.119:7000/api/dcc/ticket/approve"
        // ✅ Reject API - Only updates ticket status (PORT 5018)
        private const val REJECT_API = "http://62.72.59.119:7000/api/dcc/ticket/reject"
        private const val TIMEOUT = 15000
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateDateTime()
        displayTokenInfo()
        setupDCCFeatures()
        setupLogoutButton()
    }

    /**
     * ✅ Setup logout button
     */
    private fun setupLogoutButton() {
        binding.logoutButton.setOnClickListener {
            showLogoutConfirmation()
        }
    }

    /**
     * ✅ Show logout confirmation dialog
     */
    private fun showLogoutConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    /**
     * ✅ Perform logout action
     */
    private fun performLogout() {
        // Get username before logout for logging
        val username = SessionManager.getUsername(requireContext())

        // Clear session
        SessionManager.logout(requireContext())

        Log.d(TAG, "User logged out: $username")

        // Show logout message
        Snackbar.make(binding.root, "Logged out successfully", Snackbar.LENGTH_SHORT).show()

        // Navigate to login screen and clear back stack
        findNavController().navigate(R.id.loginFragment, null,
            navOptions {
                popUpTo(R.id.nav_graph) {
                    inclusive = true
                }
                launchSingleTop = true
            })
    }

    private fun setupDCCFeatures() {
        try {
            val token = SessionManager.getToken(requireContext())

            if (token.isEmpty()) {
                Log.w(TAG, "No token found")
                hideDCCSection()
                showStationUserFeatures()
                enableDrawerForStationUsers()  // ✅ Enable drawer for station users
                return
            }

            val payload = JWTUtils.decodeToken(token)

            if (payload == null) {
                Log.e(TAG, "Failed to decode token")
                hideDCCSection()
                showStationUserFeatures()
                enableDrawerForStationUsers()  // ✅ Enable drawer for station users
                return
            }

            isDCCUser = payload.role.lowercase() == "dcc"

            if (isDCCUser) {
                Log.d(TAG, "✅ DCC user detected - Showing tickets")
                showDCCSection()
                hideStationUserFeatures()
                setupTicketRecyclerView()
                setupFilters()  // ✅ CHANGE 3: Setup filters
                setupDCCReportsButton()  // ✅ NEW: Setup Reports button for DCC
                loadDCCTickets()

                // ✅ Disable drawer for DCC users
                disableDrawerForDCC()
            } else {
                Log.d(TAG, "ℹ️ Non-DCC user - Hiding DCC section")
                hideDCCSection()
                showStationUserFeatures()

                // ✅ Enable drawer for station users
                enableDrawerForStationUsers()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up DCC features", e)
            hideDCCSection()
            showStationUserFeatures()
            enableDrawerForStationUsers()  // ✅ Enable drawer for station users
        }
    }


    /**
     * ✅ NEW: Setup Reports button for DCC users
     */
    private fun setupDCCReportsButton() {
        binding.dccReportsBtn.visibility = View.VISIBLE
        binding.dccReportsBtn.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_homeFragment_to_reportFragment)
                Log.d(TAG, "Navigating to Reports Fragment")
            } catch (e: Exception) {
                Log.e(TAG, "Error navigating to Reports", e)
                Toast.makeText(requireContext(), "Unable to open Reports", Toast.LENGTH_SHORT).show()
            }
        }
    }
    /**
     * ✅ CHANGE 3: Setup filter functionality
     */
    private fun setupFilters() {
        // Date filter button
        binding.btnFilterDate.setOnClickListener {
            showDatePickerDialog()
        }

        // Station filter button
        binding.btnFilterStation.setOnClickListener {
            showStationFilterDialog()
        }

        // Clear filters button
        binding.btnClearFilters.setOnClickListener {
            clearFilters()
        }
    }

    /**
     * ✅ CHANGE 3: Show date picker dialog
     */
    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        DatePickerDialog(requireContext(), { _, selectedYear, selectedMonth, selectedDay ->
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            calendar.set(selectedYear, selectedMonth, selectedDay)
            selectedDate = dateFormat.format(calendar.time)

            val filterText = if (selectedStation != null) {
                "Filters: Date=$selectedDate, Station=$selectedStation"
            } else {
                "Filter: Date=$selectedDate"
            }
            binding.tvFilterStatus.text = filterText
            binding.tvFilterStatus.visibility = View.VISIBLE

            applyFilters()
        }, year, month, day).show()
    }

    /**
     * ✅ CHANGE 3: Show station filter dialog - FIXED
     */
    private fun showStationFilterDialog() {
        // Get unique stations from all tickets
        val stations = allTickets.map { it.username }.distinct().sorted().toTypedArray()

        Log.d(TAG, "🔍 Available stations: ${stations.joinToString()}")

        if (stations.isEmpty()) {
            Toast.makeText(requireContext(), "No stations available", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Filter by Station")
            .setItems(stations) { _, which ->
                selectedStation = stations[which]

                Log.d(TAG, "✅ Station filter selected: $selectedStation")

                val filterText = if (selectedDate != null) {
                    "Filters: Date=$selectedDate, Station=$selectedStation"
                } else {
                    "Filter: Station=$selectedStation"
                }
                binding.tvFilterStatus.text = filterText
                binding.tvFilterStatus.visibility = View.VISIBLE

                applyFilters()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * ✅ CHANGE 3: Apply filters to tickets - FIXED
     */
    private fun applyFilters() {
        val filteredList = if (selectedDate == null && selectedStation == null) {
            // No filters applied, show all tickets
            allTickets.toList()
        } else {
            // Apply filters
            allTickets.filter { ticket ->
                val matchesDate = if (selectedDate != null) {
                    ticket.startDateTime.startsWith(selectedDate!!)
                } else {
                    true
                }

                val matchesStation = if (selectedStation != null) {
                    ticket.username.equals(selectedStation, ignoreCase = true)
                } else {
                    true
                }

                matchesDate && matchesStation
            }
        }

        Log.d(TAG, "🔍 Filters applied - Date: $selectedDate, Station: $selectedStation")
        Log.d(TAG, "🔍 Showing ${filteredList.size} of ${allTickets.size} tickets")

        // ✅ FIX: Create a new list instance for proper adapter update
        ticketAdapter.submitList(ArrayList(filteredList))
        updateTicketCount(filteredList.size, allTickets.size)
    }

    /**
     * ✅ CHANGE 3: Clear all filters
     */
    private fun clearFilters() {
        selectedDate = null
        selectedStation = null
        binding.tvFilterStatus.visibility = View.GONE

        applyFilters()  // This will show all tickets

        Toast.makeText(requireContext(), "Filters cleared", Toast.LENGTH_SHORT).show()
    }

    /**
     * ✅ CHANGE 3: Update ticket count display
     */
    private fun updateTicketCount(filteredCount: Int, totalCount: Int) {
        if (filteredCount == totalCount) {
            binding.tvTicketCount.text = "📋 $totalCount Pending Ticket(s)"
        } else {
            binding.tvTicketCount.text = "📋 Showing $filteredCount of $totalCount Ticket(s)"
        }
    }

    /**
     * ✅ Disable navigation drawer for DCC users
     */
    private fun disableDrawerForDCC() {
        try {
            val mainActivity = activity as? MainActivity
            mainActivity?.lockDrawer()
            Log.d(TAG, "🔒 Drawer locked for DCC user")
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling drawer", e)
        }
    }

    /**
     * ✅ FIX: Enable navigation drawer for station users
     */
    private fun enableDrawerForStationUsers() {
        try {
            val mainActivity = activity as? MainActivity
            mainActivity?.unlockDrawer()
            Log.d(TAG, "🔓 Drawer unlocked for station user")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling drawer", e)
        }
    }

    /**
     * ✅ Show features for station users
     */
    private fun showStationUserFeatures() {
        binding.confirmationBtn.visibility = View.VISIBLE
        binding.confirmationBtn.setOnClickListener {
            findNavController().navigate(
                R.id.action_homeFragment_to_feederConfirmation
            )
        }
    }

    /**
     * ✅ Hide features for DCC users
     */
    private fun hideStationUserFeatures() {
        binding.confirmationBtn.visibility = View.GONE
    }

    private fun showDCCSection() {
        binding.apply {
            cardDccSection.visibility = View.VISIBLE
            rvDccTickets.visibility = View.VISIBLE
            tvDccTitle.visibility = View.VISIBLE
            tvTicketCount.visibility = View.VISIBLE
            // ✅ CHANGE 3: Show filter controls
            filterControlsLayout.visibility = View.VISIBLE
        }
    }

    private fun hideDCCSection() {
        binding.apply {
            cardDccSection.visibility = View.GONE
            rvDccTickets.visibility = View.GONE
            tvDccTitle.visibility = View.GONE
            tvTicketCount.visibility = View.GONE
            // ✅ CHANGE 3: Hide filter controls
            filterControlsLayout.visibility = View.GONE
            dccReportsBtn.visibility = View.GONE  // ✅ NEW: Hide Reports button
        }
    }

    private fun setupTicketRecyclerView() {
        ticketAdapter = TicketApprovalAdapter(
            onApproveClick = { ticket, newFeederCode ->
                approveTicket(ticket, newFeederCode)
            },
            onRejectClick = { ticket ->
                rejectTicket(ticket)
            }
        )

        binding.rvDccTickets.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ticketAdapter
        }
    }

    private fun loadDCCTickets() {
        binding.tvTicketCount.text = "Loading tickets..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                if (token.isEmpty()) {
                    showError("Session expired")
                    return@launch
                }

                Log.d(TAG, "🔍 Fetching DCC tickets...")
                val response = viewTicketsAPI(token)

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        Log.d(TAG, "✅ Received ${response.tickets.size} tickets")

                        // ✅ CHANGE 3: Store all tickets
                        allTickets.clear()
                        allTickets.addAll(response.tickets)

                        // Apply filters (will show all if no filters applied)
                        applyFilters()

                        if (response.tickets.isEmpty()) {
                            binding.tvTicketCount.text = "✅ No pending tickets"
                        }
                    } else {
                        showError("Failed to load tickets: ${response.message}")
                        binding.tvTicketCount.text = "❌ Failed to load tickets"
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading tickets", e)
                withContext(Dispatchers.Main) {
                    showError("Network error: ${e.message}")
                    binding.tvTicketCount.text = "❌ Error loading tickets"
                }
            }
        }
    }

    /* ===============================
       APPROVE TICKET
    ================================ */

    private fun approveTicket(ticket: DCCApprovalFragment.PendingTicket, newFeederCode: String?) {
        Log.d(TAG, "🔥 Approve clicked for ticket: ${ticket.ticketId}")

        val requestJson = JSONObject().apply {
            put("ticketId", ticket.ticketId)

            // Add feeder details from classification
            val details = ticket.detailsMap

            if (details.containsKey("feederName")) {
                put("feederName", details["feederName"])
            }
            if (details.containsKey("feederCode")) {
                put("feederCode", details["feederCode"])
            }
            if (details.containsKey("feederCategory")) {
                put("feederCategory", details["feederCategory"])
            }

            // Handle different classification types
            when (ticket.ticketClassification.uppercase()) {
                "FEEDER CODE" -> {
                    if (newFeederCode.isNullOrBlank()) {
                        Toast.makeText(
                            requireContext(),
                            "⚠️ Please enter new feeder code",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }
                    put("newFeederCode", newFeederCode)
                }
                "FEEDER NAME" -> {
                    if (details.containsKey("newFeederName")) {
                        put("newFeederName", details["newFeederName"])
                    }
                }
                "FEEDER CATEGORY" -> {
                    if (details.containsKey("newFeederCategory")) {
                        put("newFeederCategory", details["newFeederCategory"])
                    }
                }
                "FEEDER STATUS" -> {
                    if (details.containsKey("newStatus")) {
                        put("newStatus", details["newStatus"])
                    }
                }
                "NEW FEEDER ADDITION" -> {
                    if (newFeederCode.isNullOrBlank()) {
                        Toast.makeText(
                            requireContext(),
                            "⚠️ Please enter feeder code for new feeder",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }
                    put("newFeederCode", newFeederCode)
                    if (details.containsKey("newFeederName")) {
                        put("newFeederName", details["newFeederName"])
                    }
                    if (details.containsKey("newFeederCategory")) {
                        put("newFeederCategory", details["newFeederCategory"])
                    }
                }
            }
        }

        Log.d(TAG, "📦 Approve Request: $requestJson")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                if (token.isEmpty()) {
                    showError("Session expired")
                    return@launch
                }

                val response = approveTicketAPI(token, requestJson)

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        Snackbar.make(
                            binding.root,
                            "✅ ${response.message}",
                            Snackbar.LENGTH_LONG
                        ).show()
                        loadDCCTickets()
                    } else {
                        showError("❌ Approval failed: ${response.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error approving ticket", e)
                withContext(Dispatchers.Main) {
                    showError("Network error: ${e.message}")
                }
            }
        }
    }

    /* ===============================
       REJECT TICKET
    ================================ */

    private fun rejectTicket(ticket: DCCApprovalFragment.PendingTicket) {
        Log.d(TAG, "❌ Reject clicked for ticket: ${ticket.ticketId}")

        AlertDialog.Builder(requireContext())
            .setTitle("Reject Ticket")
            .setMessage("Are you sure you want to reject ticket ${ticket.ticketId}?")
            .setPositiveButton("Yes, Reject") { _, _ ->
                performReject(ticket)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performReject(ticket: DCCApprovalFragment.PendingTicket) {
        val requestJson = JSONObject().apply {
            put("ticketId", ticket.ticketId)
        }

        Log.d(TAG, "📦 Reject Request: $requestJson")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                if (token.isEmpty()) {
                    showError("Session expired")
                    return@launch
                }

                val response = rejectTicketAPI(token, requestJson)

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        Snackbar.make(
                            binding.root,
                            "✅ ${response.message}",
                            Snackbar.LENGTH_LONG
                        ).show()
                        loadDCCTickets()
                    } else {
                        showError("❌ Rejection failed: ${response.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error rejecting ticket", e)
                withContext(Dispatchers.Main) {
                    showError("Network error: ${e.message}")
                }
            }
        }
    }

    /* ===============================
       API CALLS
    ================================ */

    private suspend fun viewTicketsAPI(token: String): DCCApprovalFragment.TicketListResponse = withContext(Dispatchers.IO) {
        val url = URL(VIEW_TICKETS_API)
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
            Log.d(TAG, "🔥 View Tickets Response Code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }
                Log.d(TAG, "🔥 View Tickets Response: $response")
                parseTicketsResponse(response)
            } else {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                } catch (e: Exception) {
                    "No error body"
                }
                Log.e(TAG, "❌ View Tickets Error ($responseCode): $errorBody")
                DCCApprovalFragment.TicketListResponse(false, "Error: $responseCode", emptyList())
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun approveTicketAPI(token: String, requestJson: JSONObject): DCCApprovalFragment.ActionResponse = withContext(Dispatchers.IO) {
        val url = URL(APPROVE_API)
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
            }

            OutputStreamWriter(connection.outputStream).use {
                it.write(requestJson.toString())
                it.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "🔥 Approve Response Code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }
                Log.d(TAG, "🔥 Approve Response: $response")
                parseActionResponse(response)
            } else {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                } catch (e: Exception) {
                    "No error body"
                }
                Log.e(TAG, "❌ Approve Error ($responseCode): $errorBody")
                DCCApprovalFragment.ActionResponse(false, "HTTP $responseCode: $errorBody")
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun rejectTicketAPI(token: String, requestJson: JSONObject): DCCApprovalFragment.ActionResponse = withContext(Dispatchers.IO) {
        val url = URL(REJECT_API)
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
            }

            OutputStreamWriter(connection.outputStream).use {
                it.write(requestJson.toString())
                it.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "🔥 Reject Response Code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }
                Log.d(TAG, "🔥 Reject Response: $response")
                parseActionResponse(response)
            } else {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                } catch (e: Exception) {
                    "No error body"
                }
                Log.e(TAG, "❌ Reject Error ($responseCode): $errorBody")
                DCCApprovalFragment.ActionResponse(false, "HTTP $responseCode: $errorBody")
            }
        } finally {
            connection.disconnect()
        }
    }

    /* ===============================
       RESPONSE PARSING
    ================================ */

    private fun parseTicketsResponse(jsonString: String): DCCApprovalFragment.TicketListResponse {
        val jsonObject = JSONObject(jsonString)
        val success = jsonObject.optBoolean("success", false)
        val message = jsonObject.optString("message", "")

        val tickets = mutableListOf<DCCApprovalFragment.PendingTicket>()
        val dataArray = jsonObject.optJSONArray("data")

        if (dataArray != null) {
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)

                val classificationDetails = item.optString("CLASSIFICATION_DETAILS", "")
                val detailsMap = parseClassificationDetails(classificationDetails)

                tickets.add(
                    DCCApprovalFragment.PendingTicket(
                        ticketId = item.optString("TICKET_ID", ""),
                        username = item.optString("USERNAME", ""),
                        ticketClassification = item.optString("TICKET_CLASSIFICATION", ""),
                        problemStatement = item.optString("PROBLEM_STATEMENT", ""),
                        startDateTime = item.optString("START_DATETIME", ""),
                        ticketStatus = item.optString("TICKET_STATUS", ""),
                        classificationDetails = classificationDetails,
                        detailsMap = detailsMap
                    )
                )
            }
        }

        return DCCApprovalFragment.TicketListResponse(success, message, tickets)
    }

    private fun parseClassificationDetails(details: String): Map<String, String> {
        val map = mutableMapOf<String, String>()

        if (details.isBlank()) return map

        details.split(";").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.contains(":")) {
                val (key, value) = trimmed.split(":", limit = 2)
                map[key.trim()] = value.trim()
            }
        }

        return map
    }

    private fun parseActionResponse(jsonString: String): DCCApprovalFragment.ActionResponse {
        val jsonObject = JSONObject(jsonString)
        return DCCApprovalFragment.ActionResponse(
            success = jsonObject.optBoolean("success", false),
            message = jsonObject.optString("message", "Unknown error")
        )
    }

    private fun displayTokenInfo() {
        try {
            val token = SessionManager.getToken(requireContext())

            if (token.isEmpty()) {
                Log.w(TAG, "No token found")
                return
            }

            val payload = JWTUtils.decodeToken(token)

            if (payload == null) {
                Log.e(TAG, "Failed to decode token")
                return
            }

            binding.apply {
                tvUsername.text = "STATION: ${payload.username}"

                val roleFormatted = payload.role.replace("_", " ")
                    .split(" ")
                    .joinToString(" ") { it.capitalize() }

                if (payload.role.lowercase() == "dcc") {
                    tvRole.text = "ROLE: $roleFormatted 👑"
                    tvRole.setTextColor(resources.getColor(R.color.primary_light, null))
                } else {
                    tvRole.text = "ROLE: $roleFormatted"
                }

                tvEscom.text = "ESCOM: ${payload.escom}"

                val expiryTime = JWTUtils.getExpiryTime(token)
                tvTokenExpiry.text = "Session Expires: $expiryTime"

                val isExpired = JWTUtils.isTokenExpired(token)
                if (isExpired) {
                    tvTokenStatus.text = "⚠️ Session Expired"
                    tvTokenStatus.setTextColor(resources.getColor(R.color.red_500, null))
                } else {
                    tvTokenStatus.text = "✅ Session Active"
                    tvTokenStatus.setTextColor(resources.getColor(R.color.green_500, null))
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error displaying token info", e)
        }
    }


    private fun updateDateTime() {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val now = Date()

        binding.tvDate.text = "Date: ${dateFormat.format(now)}"
        binding.tvTime.text = "Time: ${timeFormat.format(now)}"
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}