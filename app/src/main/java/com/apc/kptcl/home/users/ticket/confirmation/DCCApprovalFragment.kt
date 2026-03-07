package com.apc.kptcl.home.users.ticket.confirmation

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.apc.kptcl.databinding.FragmentDccApprovalBinding
import com.apc.kptcl.utils.JWTUtils
import com.apc.kptcl.utils.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class DCCApprovalFragment : Fragment() {

    private var _binding: FragmentDccApprovalBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TicketApprovalAdapter

    companion object {
        private const val TAG = "DCCApproval"
        private const val VIEW_TICKETS_API = "http://62.72.59.119:9009/api/feeder/ticket/view"
        private const val APPROVE_API = "http://62.72.59.119:9009/api/dcc/ticket/approve"
        private const val REJECT_API = "http://62.72.59.119:9009/api/dcc/ticket/reject"
        private const val TIMEOUT = 15000
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDccApprovalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ✅ Check if user is DCC
        if (!checkDCCRole()) {
            showError("❌ Access Denied: DCC role required")
            requireActivity().onBackPressed()
            return
        }

        setupRecyclerView()
        fetchPendingTickets()

        binding.swipeRefresh.setOnRefreshListener {
            fetchPendingTickets()
        }
    }

    /**
     * ✅ Check if logged-in user is DCC
     */
    private fun checkDCCRole(): Boolean {
        try {
            val token = SessionManager.getToken(requireContext())
            if (token.isEmpty()) return false

            val payload = JWTUtils.decodeToken(token)
            val isDCC = payload?.role?.lowercase() == "dcc"

            Log.d(TAG, "🔐 Role check: ${payload?.role} - DCC: $isDCC")
            return isDCC

        } catch (e: Exception) {
            Log.e(TAG, "❌ Role check error", e)
            return false
        }
    }

    private fun setupRecyclerView() {
        adapter = TicketApprovalAdapter(
            onApproveClick = { ticket, newFeederCode ->
                approveTicket(ticket, newFeederCode)
            },
            onRejectClick = { ticket ->
                rejectTicket(ticket)
            }
        )

        binding.rvTickets.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@DCCApprovalFragment.adapter
        }
    }

    /**
     * ✅ Fetch ACTIVE tickets (filter in Kotlin)
     */
    private fun fetchPendingTickets() {
        showLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                if (token.isEmpty()) {
                    showError("Session expired")
                    return@launch
                }

                Log.d(TAG, "📥 Fetching tickets...")

                val response = fetchTicketsFromAPI(token)

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        // ✅ Filter ACTIVE tickets in Kotlin
                        val activeTickets = response.tickets.filter {
                            it.ticketStatus == "ACTIVE" &&
                                    it.ticketClassification.uppercase() != "GENERAL TICKET"
                        }

                        Log.d(TAG, "✅ Total tickets: ${response.tickets.size}")
                        Log.d(TAG, "✅ Active tickets: ${activeTickets.size}")

                        if (activeTickets.isEmpty()) {
                            showEmptyState()
                        } else {
                            adapter.submitList(activeTickets)
                            binding.tvTicketCount.text = "${activeTickets.size} pending ticket(s)"
                        }

                        showLoading(false)
                    } else {
                        showError(response.message)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error fetching tickets", e)
                withContext(Dispatchers.Main) {
                    showError("Network error: ${e.message}")
                }
            }
        }
    }

    /**
     * ✅ Approve ticket with optional new feeder code
     */
    private fun approveTicket(ticket: PendingTicket, newFeederCode: String?) {
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

                    // ✅ Add new feeder code if provided
                    if (newFeederCode != null && newFeederCode.isNotBlank()) {
                        put("new_feeder_code", newFeederCode)
                    }
                }

                val response = approveTicketAPI(token, requestJson)

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        Toast.makeText(
                            requireContext(),
                            "✅ Ticket ${ticket.ticketId} approved",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Refresh list
                        fetchPendingTickets()
                    } else {
                        showError(response.message)
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
     * ✅ Reject ticket
     */
    private fun rejectTicket(ticket: PendingTicket) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                Log.d(TAG, "❌ Rejecting ticket: ${ticket.ticketId}")

                val requestJson = JSONObject().apply {
                    put("ticket_id", ticket.ticketId)
                    put("dcc_remarks", "Rejected by DCC - Needs more information")
                }

                val response = rejectTicketAPI(token, requestJson)

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        Toast.makeText(
                            requireContext(),
                            "❌ Ticket ${ticket.ticketId} rejected",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Refresh list
                        fetchPendingTickets()
                    } else {
                        showError(response.message)
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

    private suspend fun fetchTicketsFromAPI(token: String): TicketListResponse = withContext(Dispatchers.IO) {
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
            Log.d(TAG, "Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }
                Log.d(TAG, "✅ Response: $response")
                parseTicketsResponse(response)
            } else {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                } catch (e: Exception) {
                    "No error body"
                }
                Log.e(TAG, "❌ Error: $errorBody")
                TicketListResponse(false, "Error: $responseCode", emptyList())
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun approveTicketAPI(token: String, requestJson: JSONObject): ActionResponse = withContext(Dispatchers.IO) {
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

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }
                parseActionResponse(response)
            } else {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                } catch (e: Exception) {
                    "No error body"
                }
                ActionResponse(false, "Error: $errorBody")
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun rejectTicketAPI(token: String, requestJson: JSONObject): ActionResponse = withContext(Dispatchers.IO) {
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

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }
                parseActionResponse(response)
            } else {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                } catch (e: Exception) {
                    "No error body"
                }
                ActionResponse(false, "Error: $errorBody")
            }
        } finally {
            connection.disconnect()
        }
    }

    /* ===============================
       RESPONSE PARSING
    ================================ */

    private fun parseTicketsResponse(jsonString: String): TicketListResponse {
        val jsonObject = JSONObject(jsonString)
        val success = jsonObject.optBoolean("success", false)
        val message = jsonObject.optString("message", "")

        val tickets = mutableListOf<PendingTicket>()
        val dataArray = jsonObject.optJSONArray("data")

        if (dataArray != null) {
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)

                // Parse CLASSIFICATION_DETAILS
                val classificationDetails = item.optString("CLASSIFICATION_DETAILS", "")
                val detailsMap = parseClassificationDetails(classificationDetails)

                tickets.add(
                    PendingTicket(
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

        return TicketListResponse(success, message, tickets)
    }

    /**
     * ✅ Parse CLASSIFICATION_DETAILS string into map
     */
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

    private fun parseActionResponse(jsonString: String): ActionResponse {
        val jsonObject = JSONObject(jsonString)
        return ActionResponse(
            success = jsonObject.optBoolean("success", false),
            message = jsonObject.optString("message", "Unknown error")
        )
    }

    /* ===============================
       UI HELPERS
    ================================ */

    private fun showLoading(show: Boolean) {
        binding.swipeRefresh.isRefreshing = show
        binding.rvTickets.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showEmptyState() {
        binding.rvTickets.visibility = View.GONE
        binding.tvTicketCount.text = "No pending tickets"
        Toast.makeText(requireContext(), "✅ No pending tickets", Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        binding.swipeRefresh.isRefreshing = false
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /* ===============================
       DATA CLASSES
    ================================ */

    data class PendingTicket(
        val ticketId: String,
        val username: String,
        val ticketClassification: String,
        val problemStatement: String,
        val startDateTime: String,
        val ticketStatus: String,
        val classificationDetails: String,
        val detailsMap: Map<String, String>
    )

    data class TicketListResponse(
        val success: Boolean,
        val message: String,
        val tickets: List<PendingTicket>
    )

    data class ActionResponse(
        val success: Boolean,
        val message: String
    )
}