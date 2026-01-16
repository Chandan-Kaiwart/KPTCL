package com.apc.kptcl.home

import android.app.AlertDialog
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

    companion object {
        private const val TAG = "HomeFragment"
        private const val VIEW_TICKETS_API = "http://62.72.59.119:5015/api/feeder/ticket/view/all"
        // ✅ Approve API - Updates both master and ticket status (PORT 5018)
        private const val APPROVE_API = "http://62.72.59.119:5018/api/dcc/ticket/approve"
        // ✅ Reject API - Only updates ticket status (PORT 5018)
        private const val REJECT_API = "http://62.72.59.119:5018/api/dcc/ticket/reject"
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
                return
            }

            val payload = JWTUtils.decodeToken(token)

            if (payload == null) {
                Log.e(TAG, "Failed to decode token")
                hideDCCSection()
                showStationUserFeatures()
                return
            }

            isDCCUser = payload.role.lowercase() == "dcc"

            if (isDCCUser) {
                Log.d(TAG, "✅ DCC user detected - Showing tickets")
                showDCCSection()
                hideStationUserFeatures()
                setupTicketRecyclerView()
                loadDCCTickets()

                // ✅ Disable drawer for DCC users
                disableDrawerForDCC()
            } else {
                Log.d(TAG, "ℹ️ Non-DCC user - Hiding DCC section")
                hideDCCSection()
                showStationUserFeatures()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up DCC features", e)
            hideDCCSection()
            showStationUserFeatures()
        }
    }

    /**
     * ✅ Disable navigation drawer for DCC users
     */
    private fun disableDrawerForDCC() {
        try {
            val mainActivity = activity as? MainActivity
            mainActivity?.lockDrawer()
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling drawer", e)
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
        }
    }

    private fun hideDCCSection() {
        binding.apply {
            cardDccSection.visibility = View.GONE
            rvDccTickets.visibility = View.GONE
            tvDccTitle.visibility = View.GONE
            tvTicketCount.visibility = View.GONE
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
            isNestedScrollingEnabled = false
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

                Log.d(TAG, "🔥 Loading DCC tickets...")

                val response = fetchTicketsFromAPI(token)

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        val activeTickets = response.tickets.filter {
                            it.ticketStatus == "ACTIVE"
                        }

                        Log.d(TAG, "✅ Active tickets: ${activeTickets.size}")

                        if (activeTickets.isEmpty()) {
                            binding.tvTicketCount.text = "No pending tickets"
                            binding.rvDccTickets.visibility = View.GONE
                        } else {
                            ticketAdapter.submitList(activeTickets)
                            binding.tvTicketCount.text = "${activeTickets.size} pending ticket(s)"
                            binding.rvDccTickets.visibility = View.VISIBLE
                        }
                    } else {
                        showError(response.message)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading tickets", e)
                withContext(Dispatchers.Main) {
                    showError("Network error: ${e.message}")
                }
            }
        }
    }

    /**
     * ✅ Approve Ticket
     * - Updates master table (feeder_name, feeder_category, feeder_status, etc.)
     * - Updates ticket status to APPROVED
     * - For FEEDER CODE and NEW FEEDER ADDITION, new_feeder_code is required
     */
    private fun approveTicket(ticket: DCCApprovalFragment.PendingTicket, newFeederCode: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                Log.d(TAG, "✅ Approving ticket: ${ticket.ticketId}")
                Log.d(TAG, "   Classification: ${ticket.ticketClassification}")
                if (newFeederCode != null) {
                    Log.d(TAG, "   New Feeder Code: $newFeederCode")
                }

                val requestJson = JSONObject().apply {
                    put("ticket_id", ticket.ticketId)
                    put("dcc_remarks", "Approved by DCC")

                    // ✅ Add new feeder code if provided (required for FEEDER CODE and NEW FEEDER ADDITION)
                    if (newFeederCode != null && newFeederCode.isNotBlank()) {
                        put("new_feeder_code", newFeederCode)
                    }
                }

                Log.d(TAG, "📤 Approve Request: ${requestJson.toString(2)}")

                val response = approveTicketAPI(token, requestJson)

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        Toast.makeText(
                            requireContext(),
                            "✅ Ticket approved - Master updated",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Reload tickets
                        loadDCCTickets()
                    } else {
                        showError("Approve failed: ${response.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Approve error", e)
                withContext(Dispatchers.Main) {
                    showError("Error: ${e.message}")
                }
            }
        }
    }

    /**
     * ✅ Reject Ticket
     * - Only updates ticket status to REJECTED
     * - Does NOT update master table
     */
    private fun rejectTicket(ticket: DCCApprovalFragment.PendingTicket) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                Log.d(TAG, "❌ Rejecting ticket: ${ticket.ticketId}")

                val requestJson = JSONObject().apply {
                    put("ticket_id", ticket.ticketId)
                    put("dcc_remarks", "Rejected by DCC - Needs more information")
                }

                Log.d(TAG, "📤 Reject Request: ${requestJson.toString(2)}")

                val response = rejectTicketAPI(token, requestJson)

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        Toast.makeText(
                            requireContext(),
                            "❌ Ticket rejected",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Reload tickets
                        loadDCCTickets()
                    } else {
                        showError("Reject failed: ${response.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Reject error", e)
                withContext(Dispatchers.Main) {
                    showError("Error: ${e.message}")
                }
            }
        }
    }

    /* ===============================
       API CALLS
    ================================ */

    private suspend fun fetchTicketsFromAPI(token: String): DCCApprovalFragment.TicketListResponse = withContext(Dispatchers.IO) {
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