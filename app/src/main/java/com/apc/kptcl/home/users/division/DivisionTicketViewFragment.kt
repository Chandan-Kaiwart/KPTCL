package com.apc.kptcl.home.users.division

// FILE: DivisionTicketViewFragment.kt
// ✅ FIXES v6:
//   1. Date range filter — SERVER SIDE (API mein from_date/to_date params)
//   2. "APPROVED & CLOSED" exact match
//   3. GENERAL TICKET exclude
//   4. DateTime parse: handles "2026-03-17T13:25:00.000Z" AND "2026-03-17 13:25"
//   5. Apply button lagane par fresh API call — portal jaisi behaviour
//   6. ✅ FIX: No default date filter — load all tickets (April bug fix)

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
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentDivisionTicketViewBinding
import com.apc.kptcl.databinding.ItemDivisionTicketBinding
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
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class DivisionTicketViewFragment : Fragment() {

    private var _binding: FragmentDivisionTicketViewBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val TAG = "DivisionTicketView_v6"
        private const val TIMEOUT = 15000
        private const val STATUS_ACTIVE   = "ACTIVE"
        private const val STATUS_APPROVED = "APPROVED & CLOSED"
        private const val STATUS_REJECTED = "REJECTED"
    }

    data class TicketItem(
        val ticketId: String,
        val username: String,
        val classification: String,
        val problem: String,
        val status: String,
        val startDateTime: String,
        val startDateOnly: String,
        val endDateTime: String,
        val resolutionProvided: String,
        val classificationDetails: String,
        val feederName: String,
        val feederCategory: String,
        val userDepartment: String,
        val emailId: String,
        val mobileNumber: String
    )

    private val ticketList = mutableListOf<TicketItem>()
    private val fullList   = mutableListOf<TicketItem>()
    private lateinit var adapter: DivisionTicketAdapter

    private var filterFromDate: String? = null   // "yyyy-MM-dd"
    private var filterToDate:   String? = null   // "yyyy-MM-dd"
    private var activeStatusFilter: String? = null
    private var isDateFilterExpanded = false

    private val displayFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val storageFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDivisionTicketViewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "✅ v6 RUNNING — load all tickets, no default date filter")
        setupRecyclerView()
        setupBackButton()
        setupChipFilters()
        setupDateRangeFilter()

        // ✅ FIX v6: Koi default date filter NAHI — saare tickets load karo
        // v5 mein current month default tha, aaj April 1 hai but tickets March ke hain
        // Isliye 0 results aa rahe the. Ab bina filter ke saara data aayega.
        // User apni marzi se date range filter laga sakta hai.
        filterFromDate = null
        filterToDate   = null
        loadTickets(fromDate = null, toDate = null)
    }

    private fun setupRecyclerView() {
        adapter = DivisionTicketAdapter(ticketList)
        binding.rvTickets.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTickets.adapter = adapter
    }

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            findNavController().navigate(
                R.id.divisionHomeFragment, null,
                navOptions { popUpTo(R.id.divisionHomeFragment) { inclusive = true } }
            )
        }
    }

    // ── Status chip — client side (fast) ─────────────────────────

    private fun setupChipFilters() {
        binding.chipAll.setOnClickListener      { activeStatusFilter = null;           applyClientSideFilter() }
        binding.chipActive.setOnClickListener   { activeStatusFilter = STATUS_ACTIVE;   applyClientSideFilter() }
        binding.chipApproved.setOnClickListener { activeStatusFilter = STATUS_APPROVED; applyClientSideFilter() }
        binding.chipRejected.setOnClickListener { activeStatusFilter = STATUS_REJECTED; applyClientSideFilter() }
    }

    private fun applyClientSideFilter() {
        val filtered = if (activeStatusFilter == null) fullList.toList()
        else fullList.filter { it.status.trim() == activeStatusFilter }
        ticketList.clear()
        ticketList.addAll(filtered)
        updateCountBar(filtered)
        if (filtered.isEmpty()) {
            binding.tvEmpty.visibility   = View.VISIBLE
            binding.tvEmpty.text         = "No ${activeStatusFilter?.lowercase() ?: ""} tickets found for the selected date range"
            binding.rvTickets.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility   = View.GONE
            binding.rvTickets.visibility = View.VISIBLE
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateCountBar(list: List<TicketItem>) {
        // Always count from fullList so header totals never change when a chip filter is active
        val total    = fullList.size
        val active   = fullList.count { it.status.trim() == STATUS_ACTIVE }
        val approved = fullList.count { it.status.trim() == STATUS_APPROVED }
        val rejected = fullList.count { it.status.trim() == STATUS_REJECTED }
        binding.tvTicketCount.text =
            "Total: $total  |  Active: $active  |  Approved: $approved  |  Rejected: $rejected"
    }

    // ── Date range filter UI ──────────────────────────────────────

    private fun setupDateRangeFilter() {

        binding.llDateFilterHeader.setOnClickListener {
            isDateFilterExpanded = !isDateFilterExpanded
            binding.llDateFilterBody.visibility =
                if (isDateFilterExpanded) View.VISIBLE else View.GONE
            binding.ivDateFilterArrow.rotation =
                if (isDateFilterExpanded) 180f else 0f
        }

        binding.llFromDate.setOnClickListener { showDatePickerDialog(isFrom = true) }
        binding.llToDate.setOnClickListener   { showDatePickerDialog(isFrom = false) }

        // ✅ APPLY — server se fresh data with date range
        binding.btnApplyDateFilter.setOnClickListener {
            val from = filterFromDate
            val to   = filterToDate
            if (from == null && to == null) {
                Toast.makeText(requireContext(), "Please select at least one date", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (from != null && to != null) {
                val fromParsed = storageFmt.parse(from)
                val toParsed   = storageFmt.parse(to)
                if (fromParsed != null && toParsed != null && fromParsed.after(toParsed)) {
                    Toast.makeText(requireContext(), "'From' date cannot be after 'To' date", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            updateActiveDateRangeLabel()
            isDateFilterExpanded = false
            binding.llDateFilterBody.visibility = View.GONE
            binding.ivDateFilterArrow.rotation  = 0f

            // ✅ Server call with date range — portal jaisi
            loadTickets(fromDate = from, toDate = to)
        }

        // ✅ CLEAR — saare filters hata do, saare tickets wapas load karo
        binding.btnClearDateFilter.setOnClickListener {
            filterFromDate = null
            filterToDate   = null

            binding.tvFromDate.text = "Select date"
            binding.tvFromDate.setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
            binding.tvToDate.text   = "Select date"
            binding.tvToDate.setTextColor(android.graphics.Color.parseColor("#9E9E9E"))

            binding.tvActiveDateRange.text       = ""
            binding.tvActiveDateRange.visibility = View.GONE

            loadTickets(fromDate = null, toDate = null)
        }
    }

    private fun showDatePickerDialog(isFrom: Boolean) {
        val cal = Calendar.getInstance()
        val existing = if (isFrom) filterFromDate else filterToDate
        if (existing != null) storageFmt.parse(existing)?.let { cal.time = it }

        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val picked     = Calendar.getInstance().apply { set(year, month, day) }.time
                val storageStr = storageFmt.format(picked)
                val displayStr = displayFmt.format(picked)
                if (isFrom) {
                    filterFromDate = storageStr
                    binding.tvFromDate.text = displayStr
                    binding.tvFromDate.setTextColor(android.graphics.Color.parseColor("#212121"))
                } else {
                    filterToDate = storageStr
                    binding.tvToDate.text = displayStr
                    binding.tvToDate.setTextColor(android.graphics.Color.parseColor("#212121"))
                }
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateActiveDateRangeLabel() {
        val from = filterFromDate
        val to   = filterToDate
        val label = when {
            from != null && to != null ->
                "${displayFmt.format(storageFmt.parse(from)!!)} → ${displayFmt.format(storageFmt.parse(to)!!)}"
            from != null -> "From ${displayFmt.format(storageFmt.parse(from)!!)}"
            to   != null -> "Up to ${displayFmt.format(storageFmt.parse(to)!!)}"
            else -> null
        }
        if (label != null) {
            binding.tvActiveDateRange.text       = label
            binding.tvActiveDateRange.visibility = View.VISIBLE
        } else {
            binding.tvActiveDateRange.visibility = View.GONE
        }
    }

    // ── Load tickets (server-side date filter) ────────────────────

    private fun loadTickets(fromDate: String? = null, toDate: String? = null) {
        val token     = SessionManager.getToken(requireContext())
        val serverUrl = SessionManager.getServerUrl(requireContext())
        if (token.isEmpty() || serverUrl.isEmpty()) {
            showError("Session expired. Please login again."); return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility     = View.GONE
        binding.rvTickets.visibility   = View.GONE

        lifecycleScope.launch {
            try {
                val tickets = fetchAllTickets(serverUrl, token, fromDate, toDate)
                binding.progressBar.visibility = View.GONE

                fullList.clear()
                fullList.addAll(tickets)

                // ✅ FIX: Do NOT reset activeStatusFilter here — preserve whatever chip user selected
                // This allows date range + chip filter to work together correctly
                applyClientSideFilter()

                if (tickets.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.tvEmpty.text = if (fromDate != null || toDate != null)
                        "No tickets found for the selected date range"
                    else
                        "No tickets found"
                }

            } catch (e: Exception) {
                Log.e(TAG, "Load error: ${e.message}", e)
                binding.progressBar.visibility = View.GONE
                showError("Failed to load tickets: ${e.message}")
            }
        }
    }

    // ── API call — with optional from_date / to_date params ──────

    private suspend fun fetchAllTickets(
        serverUrl: String,
        token: String,
        fromDate: String?,
        toDate: String?
    ): List<TicketItem> = withContext(Dispatchers.IO) {

        // Build URL: GET /api/division/tickets?from_date=2026-01-01&to_date=2026-03-31
        val urlBuilder = StringBuilder("$serverUrl/api/division/tickets")
        val params = mutableListOf<String>()
        if (fromDate != null) params.add("from_date=$fromDate")
        if (toDate   != null) params.add("to_date=$toDate")
        if (params.isNotEmpty()) urlBuilder.append("?${params.joinToString("&")}")

        val urlStr = urlBuilder.toString()
        Log.d(TAG, "API call: $urlStr")

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.apply {
                requestMethod  = "GET"
                connectTimeout = TIMEOUT
                readTimeout    = TIMEOUT
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            Log.d(TAG, "HTTP $code")
            if (code == HttpURLConnection.HTTP_OK) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                Log.d(TAG, "Response: ${body.length} chars")
                parseTickets(body)
            } else {
                val err = runCatching {
                    BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                }.getOrDefault("no error body")
                Log.e(TAG, "Error: $err")
                emptyList()
            }
        } finally { conn.disconnect() }
    }

    // ── DateTime extractor ────────────────────────────────────────

    private fun extractDateOnly(raw: String): String {
        if (raw == "—" || raw.isBlank()) return ""
        return try {
            when {
                raw.contains("T") -> {
                    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                    val date = fmt.parse(raw) ?: return raw.take(10)
                    val istFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    istFmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                    istFmt.format(date)
                }
                raw.contains(" ") -> raw.take(10)
                else              -> raw.take(10)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Date parse failed: $raw — ${e.message}")
            raw.take(10)
        }
    }

    // ── JSON parser ───────────────────────────────────────────────

    private fun parseTickets(json: String): List<TicketItem> {
        return try {
            val root = JSONObject(json)
            val arr  = root.optJSONArray("data") ?: run {
                Log.e(TAG, "No 'data' key. Keys: ${root.keys().asSequence().toList()}")
                return emptyList()
            }
            Log.d(TAG, "Parsing ${arr.length()} items")
            val result = mutableListOf<TicketItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val classification = o.optString("TICKET_CLASSIFICATION", "").trim()
                if (classification.uppercase() == "GENERAL TICKET") continue
                val rawStart = o.optString("START_DATETIME", "—")
                val status   = o.optString("TICKET_STATUS", "—").trim()
                if (i < 3) Log.d(TAG, "[$i] status='$status' start='$rawStart'")
                result.add(TicketItem(
                    ticketId              = o.optString("TICKET_ID",             "—"),
                    username              = o.optString("USERNAME",               "—"),
                    classification        = classification.ifEmpty { "—" },
                    problem               = o.optString("PROBLEM_STATEMENT",      "—"),
                    status                = status,
                    startDateTime         = rawStart,
                    startDateOnly         = extractDateOnly(rawStart),
                    endDateTime           = o.optString("END_DATETIME",           "—"),
                    resolutionProvided    = o.optString("RESOLUTION_PROVIDED",    "—"),
                    classificationDetails = o.optString("CLASSIFICATION_DETAILS", "—"),
                    feederName            = o.optString("FEEDER_NAME",            "—"),
                    feederCategory        = o.optString("FEEDER_CATEGORY",        "—"),
                    userDepartment        = o.optString("USER_DEPARTMENT",        "—"),
                    emailId               = o.optString("EMAIL_ID",              "—"),
                    mobileNumber          = o.optString("MOBILE_NUMBER",         "—")
                ))
            }
            Log.d(TAG, "Parsed: ${result.size} | Active:${result.count{it.status==STATUS_ACTIVE}} Approved:${result.count{it.status==STATUS_APPROVED}} Rejected:${result.count{it.status==STATUS_REJECTED}}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}", e)
            emptyList()
        }
    }

    private fun showError(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    // ── Adapter ───────────────────────────────────────────────────

    inner class DivisionTicketAdapter(private val items: List<TicketItem>) :
        RecyclerView.Adapter<DivisionTicketAdapter.VH>() {

        inner class VH(val b: ItemDivisionTicketBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemDivisionTicketBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            with(holder.b) {
                tvTicketId.text       = "# ${t.ticketId}"
                tvStation.text        = t.username
                tvClassification.text = t.classification
                tvProblem.text        = t.problem
                val displayStart = t.startDateTime
                    .replace("T", " ").replace(".000Z", "").take(16)
                tvDateTime.text = "Start: $displayStart"
                tvStatus.text   = t.status
                val (bg, fg) = when (t.status.trim()) {
                    STATUS_APPROVED -> "#E8F5E9" to "#1B5E20"
                    STATUS_REJECTED -> "#FFEBEE" to "#B71C1C"
                    STATUS_ACTIVE   -> "#FFF8E1" to "#F57F17"
                    else            -> "#F3E5F5" to "#4A148C"
                }
                tvStatus.setBackgroundColor(android.graphics.Color.parseColor(bg))
                tvStatus.setTextColor(android.graphics.Color.parseColor(fg))
            }
        }
    }
}