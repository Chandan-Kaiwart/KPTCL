package com.apc.kptcl.home.users.ticket

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentViewTicketsBinding
import com.apc.kptcl.home.users.ticket.dataclass.Ticket
import com.apc.kptcl.utils.SessionManager
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.apc.kptcl.home.users.ticket.dataclass.TicketApiService
import com.apc.kptcl.home.users.ticket.dataclass.TicketsAdapter
import com.apc.kptcl.utils.ApiErrorHandler


class ViewTicketsFragment : Fragment() {

    private var _binding: FragmentViewTicketsBinding? = null
    private val binding get() = _binding!!

    private lateinit var ticketsAdapter: TicketsAdapter
    private var allTickets = listOf<Ticket>()
    private var currentFilter: String? = null

    companion object {
        private const val TAG = "ViewTicketsFragment"
        private const val TICKET_API_BASE_URL = "http://62.72.59.119:8008/"
    }

    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(TICKET_API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val apiService by lazy { retrofit.create(TicketApiService::class.java) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentViewTicketsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupStatusCards()
        setupRecyclerView()
        setupFab()
        loadTickets()
    }

    private fun setupToolbar() {
//        binding.toolbar.setNavigationOnClickListener {
//            activity?.onBackPressed()
//        }
    }

    private fun setupStatusCards() {
        binding.tvActiveCount.setOnClickListener {
            filterTickets("ACTIVE")
        }
        binding.tvPendingCount.setOnClickListener {
            filterTickets("PENDING")
        }
        binding.tvClosedCount.setOnClickListener {
            filterTickets("APPROVED & CLOSED")
        }
        binding.tvRejectedCount.setOnClickListener {
            filterTickets("REJECTED")
        }
    }

    private fun filterTickets(status: String) {
        currentFilter = if (currentFilter == status) {
            ticketsAdapter.submitList(allTickets)
            null
        } else {
            val filteredTickets = allTickets.filter {
                it.ticketStatus?.uppercase() == status.uppercase()
            }
            ticketsAdapter.submitList(filteredTickets)
            status
        }
    }

    private fun setupRecyclerView() {
        ticketsAdapter = TicketsAdapter { ticket ->
            // ✅ Show ticket details dialog with resolution/reason
            showTicketDetailsDialog(ticket)
        }

        binding.rvTickets.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ticketsAdapter
        }
    }

    /**
     * ✅ NEW: Show ticket details dialog with full resolution/reason
     */
    private fun showTicketDetailsDialog(ticket: Ticket) {
        val message = buildString {
            append("🎫 Ticket ID: ${ticket.ticketId}\n\n")
            append("👤 User: ${ticket.username}\n")
            append("📧 Email: ${ticket.emailId}\n")
            append("📱 Mobile: ${ticket.mobileNumber}\n\n")
            append("📋 Classification: ${ticket.ticketClassification}\n")
            append("❓ Problem: ${ticket.problemStatement}\n\n")
            append("📅 Start: ${ticket.startDatetime}\n")

            if (!ticket.endDatetime.isNullOrBlank()) {
                append("🏁 End: ${ticket.endDatetime}\n")
            }

            append("\n🎯 Status: ${ticket.ticketStatus}\n\n")

            // ✅ Show resolution/reason
            if (!ticket.resolutionProvided.isNullOrBlank()) {
                when (ticket.ticketStatus?.uppercase()) {
                    "REJECTED" -> {
                        append("❌ Rejection Reason:\n")
                        append("${ticket.resolutionProvided}")
                    }
                    "APPROVED & CLOSED" -> {
                        append("✅ Resolution:\n")
                        append("${ticket.resolutionProvided}")
                    }
                    else -> {
                        append("📝 Notes:\n")
                        append("${ticket.resolutionProvided}")
                    }
                }
            }
        }

        val title = when (ticket.ticketStatus?.uppercase()) {
            "REJECTED" -> "❌ Rejected Ticket"
            "APPROVED & CLOSED" -> "✅ Approved Ticket"
            "ACTIVE" -> "🟡 Active Ticket"
            "PENDING" -> "🟠 Pending Ticket"
            else -> "🎫 Ticket Details"
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun setupFab() {
        binding.fabCreateTicket.setOnClickListener {
            findNavController().navigate(R.id.action_viewTicketsFragment_to_CreateTicketFragment)
        }
    }

    private fun loadTickets() {
        binding.rvTickets.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                Log.d(TAG, "Loading tickets...")
                Log.d(TAG, "Token: ${token.take(50)}...")
                Log.d(TAG, "API URL: $TICKET_API_BASE_URL")

                if (token.isEmpty()) {
                    showError("Session expired. Please login again.")
                    Log.e(TAG, "Token is empty!")
                    return@launch
                }

                val response = apiService.getTickets("Bearer $token")

                Log.d(TAG, "Response code: ${response.code()}")

                if (response.isSuccessful && response.body() != null) {
                    val ticketResponse = response.body()!!

                    Log.d(TAG, "Response success: ${ticketResponse.success}")
                    Log.d(TAG, "Ticket count: ${ticketResponse.count}")

                    if (ticketResponse.success) {
                        allTickets = ticketResponse.data
                        ticketsAdapter.submitList(allTickets)

                        // ✅ Calculate counts - Updated to match actual statuses
                        val activeCount = allTickets.count { it.ticketStatus?.uppercase() == "ACTIVE" }
                        val pendingCount = allTickets.count { it.ticketStatus?.uppercase() == "PENDING" }
                        val closedCount = allTickets.count { it.ticketStatus?.uppercase() == "APPROVED & CLOSED" }
                        val rejectedCount = allTickets.count { it.ticketStatus?.uppercase() == "REJECTED" }

                        Log.d(TAG, "Status counts - Active: $activeCount, Pending: $pendingCount, Closed: $closedCount, Rejected: $rejectedCount")

                        updateStatusCounts(activeCount, pendingCount, closedCount, rejectedCount)

                        binding.rvTickets.visibility = View.VISIBLE
                        Toast.makeText(requireContext(), "Loaded ${allTickets.size} tickets", Toast.LENGTH_SHORT).show()
                    } else {
                        showError("Failed to load tickets")
                        Log.e(TAG, "API returned success=false")
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "API Error - Code: ${response.code()}, Message: ${response.message()}")
                    Log.e(TAG, "Error body: $errorBody")

                    when (response.code()) {
                        401 -> {
                            showError("Session expired. Please login again.")
                        }
                        else -> showError(ApiErrorHandler.parseServerError(response.errorBody()?.string(), response.code()))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                showError(ApiErrorHandler.handle(e))
            }
        }
    }

    private fun updateStatusCounts(active: Int, pending: Int, closed: Int, rejected: Int) {
        binding.tvActiveCount.text = active.toString()
        binding.tvPendingCount.text = pending.toString()
        binding.tvClosedCount.text = closed.toString()
        binding.tvRejectedCount.text = rejected.toString()
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        binding.rvTickets.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}