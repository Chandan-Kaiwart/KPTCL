package com.apc.kptcl.home.users.ticket

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


class ViewTicketsFragment : Fragment() {

    private var _binding: FragmentViewTicketsBinding? = null
    private val binding get() = _binding!!

    private lateinit var ticketsAdapter: TicketsAdapter
    private var allTickets = listOf<Ticket>()
    private var currentFilter: String? = null

    companion object {
        private const val TAG = "ViewTicketsFragment"
        // Ticket API is on port 5015, not 5010 (login port)
        private const val TICKET_API_BASE_URL = "http://62.72.59.119:7000/"
    }

    // API setup - using ticket API server
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
        // Status cards are clickable to filter tickets
        binding.tvActiveCount.setOnClickListener {
            filterTickets("OPEN")
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
            // Toggle off filter if clicking the same status
            ticketsAdapter.submitList(allTickets)
            null
        } else {
            // Apply filter
            val filteredTickets = allTickets.filter {
                it.ticketStatus?.uppercase() == status.uppercase()
            }
            ticketsAdapter.submitList(filteredTickets)
            status
        }
    }

    private fun setupRecyclerView() {
        ticketsAdapter = TicketsAdapter { ticket ->
            // Handle ticket click - show details
            Toast.makeText(
                requireContext(),
                "Ticket ID: ${ticket.ticketId}\nStatus: ${ticket.ticketStatus}",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.rvTickets.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ticketsAdapter
        }
    }

    private fun setupFab() {
        binding.fabCreateTicket.setOnClickListener {
            findNavController().navigate(R.id.action_viewTicketsFragment_to_CreateTicketFragment)
        }
    }

    private fun loadTickets() {
        // Show loading state
        binding.rvTickets.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get token from SessionManager
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

                        // Calculate counts
                        val openCount = allTickets.count { it.ticketStatus == "OPEN" }
                        val pendingCount = allTickets.count { it.ticketStatus == "PENDING" }
                        val closedCount = allTickets.count { it.ticketStatus == "APPROVED & CLOSED" }
                        val rejectedCount = allTickets.count { it.ticketStatus == "REJECTED" }

                        Log.d(TAG, "Status counts - Open: $openCount, Pending: $pendingCount, Closed: $closedCount, Rejected: $rejectedCount")

                        updateStatusCounts(openCount, pendingCount, closedCount, rejectedCount)

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
                            // Navigate to login
                            // findNavController().navigate(R.id.action_to_login)
                        }
                        else -> showError("Error: ${response.code()} - ${response.message()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                showError("Network error: ${e.message}")
                e.printStackTrace()
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