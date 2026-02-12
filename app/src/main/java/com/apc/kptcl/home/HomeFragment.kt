package com.apc.kptcl.home

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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

    // Filter variables
    private var allTickets = mutableListOf<DCCApprovalFragment.PendingTicket>()
    private var selectedDate: String? = null
    private var selectedStation: String? = null

    companion object {
        private const val TAG = "HomeFragment"
        private const val VIEW_TICKETS_API = "http://62.72.59.119:8000/api/feeder/ticket/view/all"
        private const val APPROVE_API = "http://62.72.59.119:8000/api/dcc/ticket/approve"
        private const val REJECT_API = "http://62.72.59.119:8000/api/dcc/ticket/reject"
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

    private fun setupLogoutButton() {
        binding.logoutButton.setOnClickListener {
            showLogoutConfirmation()
        }
    }

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

    private fun performLogout() {
        val username = SessionManager.getUsername(requireContext())
        SessionManager.logout(requireContext())
        Log.d(TAG, "User logged out: $username")
        Snackbar.make(binding.root, "Logged out successfully", Snackbar.LENGTH_SHORT).show()

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
                enableDrawerForStationUsers()
                return
            }

            val payload = JWTUtils.decodeToken(token)

            if (payload == null) {
                Log.e(TAG, "Failed to decode token")
                hideDCCSection()
                showStationUserFeatures()
                enableDrawerForStationUsers()
                return
            }

            isDCCUser = payload.role.lowercase() == "dcc"

            if (isDCCUser) {
                Log.d(TAG, "✅ DCC user detected - Showing tickets")
                showDCCSection()
                hideStationUserFeatures()
                setupTicketRecyclerView()
                setupFilters()
                setupDCCReportsButton()
                loadDCCTickets()
                disableDrawerForDCC()
            } else {
                Log.d(TAG, "ℹ️ Non-DCC user - Hiding DCC section")
                hideDCCSection()
                showStationUserFeatures()
                enableDrawerForStationUsers()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up DCC features", e)
            hideDCCSection()
            showStationUserFeatures()
            enableDrawerForStationUsers()
        }
    }

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

    private fun setupFilters() {
        binding.btnFilterDate.setOnClickListener {
            showDatePickerDialog()
        }

        binding.btnFilterStation.setOnClickListener {
            showStationFilterDialog()
        }

        binding.btnClearFilters.setOnClickListener {
            clearFilters()
        }
    }

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        DatePickerDialog(requireContext(), { _, selectedYear, selectedMonth, selectedDay ->
            selectedDate = String.format("%04d-%02d-%02d", selectedYear, selectedMonth + 1, selectedDay)
            applyFilters()
        }, year, month, day).show()
    }

    private fun showStationFilterDialog() {
        val stations = allTickets.map { it.username }.distinct().sorted().toTypedArray()

        if (stations.isEmpty()) {
            Toast.makeText(requireContext(), "No stations available to filter", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Select Station")
            .setItems(stations) { _, which ->
                selectedStation = stations[which]
                applyFilters()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyFilters() {
        var filteredTickets = allTickets.toList()

        if (selectedDate != null) {
            filteredTickets = filteredTickets.filter { ticket ->
                ticket.startDateTime.startsWith(selectedDate!!)
            }
        }

        if (selectedStation != null) {
            filteredTickets = filteredTickets.filter { ticket ->
                ticket.username == selectedStation
            }
        }

        ticketAdapter.submitList(filteredTickets)
        updateFilterStatusText()
        Log.d(TAG, "✅ Filters applied - Showing ${filteredTickets.size} tickets")
    }

    private fun updateFilterStatusText() {
        val statusText = buildString {
            if (selectedDate != null || selectedStation != null) {
                append("Filtering by: ")
                val filters = mutableListOf<String>()
                if (selectedDate != null) filters.add("Date: $selectedDate")
                if (selectedStation != null) filters.add("Station: $selectedStation")
                append(filters.joinToString(", "))
            }
        }

        if (statusText.isNotEmpty()) {
            binding.tvFilterStatus.text = statusText
            binding.tvFilterStatus.visibility = View.VISIBLE
        } else {
            binding.tvFilterStatus.visibility = View.GONE
        }
    }

    private fun clearFilters() {
        selectedDate = null
        selectedStation = null
        ticketAdapter.submitList(allTickets)
        binding.tvFilterStatus.visibility = View.GONE
        Toast.makeText(requireContext(), "Filters cleared", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "✅ Filters cleared - Showing all ${allTickets.size} tickets")
    }

    private fun disableDrawerForDCC() {
        (requireActivity() as? MainActivity)?.apply {
            try {
                val method = MainActivity::class.java.getMethod("setDrawerEnabled", Boolean::class.java)
                method.invoke(this, false)
                Log.d(TAG, "🔒 Drawer disabled for DCC user")
            } catch (e: Exception) {
                Log.w(TAG, "MainActivity doesn't have setDrawerEnabled method")
            }
        }
    }

    private fun enableDrawerForStationUsers() {
        (requireActivity() as? MainActivity)?.apply {
            try {
                val method = MainActivity::class.java.getMethod("setDrawerEnabled", Boolean::class.java)
                method.invoke(this, true)
                Log.d(TAG, "🔓 Drawer enabled for station user")
            } catch (e: Exception) {
                Log.w(TAG, "MainActivity doesn't have setDrawerEnabled method")
            }
        }
    }

    private fun showDCCSection() {
        binding.cardDccSection.visibility = View.VISIBLE
        binding.rvDccTickets.visibility = View.VISIBLE
        binding.filterControlsLayout.visibility = View.VISIBLE
    }

    private fun hideDCCSection() {
        binding.cardDccSection.visibility = View.GONE
        binding.rvDccTickets.visibility = View.GONE
        binding.filterControlsLayout.visibility = View.GONE
    }

    private fun showStationUserFeatures() {
        binding.confirmationBtn.visibility = View.GONE


    }

    private fun hideStationUserFeatures() {
        binding.confirmationBtn.visibility = View.GONE
    }

    private fun setupTicketRecyclerView() {
        // ✅ FIXED: Adapter expects (ticket, feederCode) for approve and (ticket) for reject
        ticketAdapter = TicketApprovalAdapter(
            onApproveClick = { ticket, feederCode -> performApprove(ticket, feederCode) },
            onRejectClick = { ticket -> rejectTicket(ticket) }
        )

        binding.rvDccTickets.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ticketAdapter
            setHasFixedSize(false)
            setItemViewCacheSize(20)
        }
    }

    private fun loadDCCTickets() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                if (token.isEmpty()) {
                    showError("Session expired")
                    return@launch
                }

                val response = viewTicketsAPI(token)

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        allTickets = response.tickets.toMutableList()
                        ticketAdapter.submitList(allTickets)

                        val pendingCount = allTickets.count { it.ticketStatus == "ACTIVE" }
                        binding.tvTicketCount.text = "Pending Tickets: $pendingCount | Total: ${allTickets.size}"

                        Log.d(TAG, "✅ Loaded ${allTickets.size} tickets")
                    } else {
                        showError("Failed to load tickets: ${response.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading tickets", e)
                withContext(Dispatchers.Main) {
                    showError("Network error: ${e.message}")
                }
            }
        }
    }

    /* ===============================
       APPROVE TICKET
       ✅ Now called directly from adapter with feederCode
    ================================ */

    private fun performApprove(ticket: DCCApprovalFragment.PendingTicket, newFeederCode: String?) {
        val details = ticket.detailsMap

        val requestJson = JSONObject().apply {
            put("ticket_id", ticket.ticketId)

            val classification = ticket.ticketClassification.trim()
            when (classification.uppercase()) {
                "FEEDER CODE" -> {
                    if (newFeederCode != null) {
                        put("newFeederCode", newFeederCode)
                    } else if (details.containsKey("NEW_FEEDER_CODE")) {
                        put("newFeederCode", details["NEW_FEEDER_CODE"])
                    }
                }
                "NEW FEEDER ADDITION" -> {
                    if (!newFeederCode.isNullOrBlank()) {
                        put("new_feeder_code", newFeederCode)
                    }
                    if (details.containsKey("NEW_FEEDER_NAME")) {
                        put("newFeederName", details["NEW_FEEDER_NAME"])
                    }
                    if (details.containsKey("NEW_FEEDER_CATEGORY")) {
                        put("newFeederCategory", details["NEW_FEEDER_CATEGORY"])
                    }
                }
                "FEEDER NAME" -> {
                    if (details.containsKey("NEW_FEEDER_NAME")) {
                        put("newFeederName", details["NEW_FEEDER_NAME"])
                    }
                }
                "FEEDER CATEGORY" -> {
                    if (details.containsKey("NEW_FEEDER_CATEGORY")) {
                        put("newFeederCategory", details["NEW_FEEDER_CATEGORY"])
                    }
                }
                "FEEDER STATUS" -> {
                    if (details.containsKey("NEW_STATUS")) {
                        put("newStatus", details["NEW_STATUS"])
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
       REJECT TICKET - WITH REASON DIALOG
    ================================ */

    private fun rejectTicket(ticket: DCCApprovalFragment.PendingTicket) {
        Log.d(TAG, "❌ Reject clicked for ticket: ${ticket.ticketId}")

        val editText = EditText(requireContext()).apply {
            hint = "Enter rejection reason"
            setPadding(50, 30, 50, 30)
            minLines = 3
            maxLines = 5
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Reject Ticket")
            .setMessage("Please provide a reason for rejecting ticket:\n${ticket.ticketId}")
            .setView(editText)
            .setPositiveButton("Reject") { _, _ ->
                val reason = editText.text.toString().trim()

                if (reason.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "⚠️ Please provide a rejection reason",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    performReject(ticket, reason)
                }
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .show()
    }

    private fun performReject(ticket: DCCApprovalFragment.PendingTicket, rejectionReason: String) {
        val requestJson = JSONObject().apply {
            put("ticket_id", ticket.ticketId)
            put("dcc_remarks", rejectionReason)
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
                            "✅ Ticket rejected: $rejectionReason",
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
                DCCApprovalFragment.TicketListResponse(false, "HTTP $responseCode: $errorBody", emptyList())
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