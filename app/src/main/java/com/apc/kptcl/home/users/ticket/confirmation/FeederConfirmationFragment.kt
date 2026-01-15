package com.apc.kptcl.home.users.ticket.confirmation

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
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentFeederConfirmationBinding
import com.apc.kptcl.databinding.ItemFeederConfirmationBinding
import com.apc.kptcl.home.users.ticket.dataclass.FeederData
import com.apc.kptcl.home.users.ticket.dataclass.FeederItem
import com.apc.kptcl.home.users.ticket.dataclass.FeederListResponse
import com.apc.kptcl.utils.SessionManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class FeederConfirmationFragment : Fragment() {

    private var _binding: FragmentFeederConfirmationBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: FeederConfirmationAdapter

    companion object {
        private const val TAG = "FeederConfirmation"
        private const val FEEDER_LIST_URL = "http://62.72.59.119:5000/api/feeder/list"
        private const val TIMEOUT = 15000
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeederConfirmationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check if user is logged in
        if (!SessionManager.isLoggedIn(requireContext())) {
            Toast.makeText(requireContext(), "Please login first", Toast.LENGTH_LONG).show()
            return
        }

        setupRecyclerView()
        setupClickListeners()
        loadStationInfo()
        fetchFeederList()
    }

    private fun loadStationInfo() {
        // Load station information from SessionManager
        val username = SessionManager.getUsername(requireContext())
        val escom = SessionManager.getEscom(requireContext())

        binding.tvStation.text = username
        binding.tvEscom.text = escom

        // If you have these in SessionManager, load them too
        // binding.tvDistrict.text = SessionManager.getDistrict(requireContext())
        // binding.tvTaluka.text = SessionManager.getTaluka(requireContext())
        // binding.tvZone.text = SessionManager.getZone(requireContext())
        // binding.tvCircle.text = SessionManager.getCircle(requireContext())
        // binding.tvDivision.text = SessionManager.getDivision(requireContext())
        // binding.tvConstituency.text = SessionManager.getConstituency(requireContext())
    }

    private fun setupRecyclerView() {
        adapter = FeederConfirmationAdapter { feeder ->
            // Handle raise ticket click
            val bundle = Bundle().apply {
                putString("feederName", feeder.name)
                putString("feederCode", feeder.code)
                putString("feederCategory", feeder.category)
            }
            findNavController().navigate(R.id.CreateTicketFragment, bundle)
        }

        binding.rvFeeders.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@FeederConfirmationFragment.adapter
        }
    }

    private fun setupClickListeners() {
        binding.btnSubmit.setOnClickListener {
            submitConfirmations()
        }
    }

    private fun fetchFeederList() {
        // Show loading
        showLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                if (token.isEmpty()) {
                    showError("Session expired. Please login again.")
                    return@launch
                }

                Log.d(TAG, "Fetching feeder list...")
                Log.d(TAG, "Token: ${token.take(50)}...")
                Log.d(TAG, "API URL: $FEEDER_LIST_URL")

                val response = fetchFeedersFromAPI(token)

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        Log.d(TAG, "âœ… Received ${response.count} feeders")

                        // Convert API response to FeederData
                        val feeders = response.data.map { item ->
                            FeederData(
                                name = item.FEEDER_NAME,
                                code = item.FEEDER_CODE,
                                category = item.FEEDER_CATEGORY,
                                confirmed = false
                            )
                        }

                        adapter.submitList(feeders)
                        showLoading(false)

                        Toast.makeText(
                            requireContext(),
                            "Loaded ${feeders.size} feeders",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        showError("Failed to load feeders")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching feeders", e)
                withContext(Dispatchers.Main) {
                    showError("Network error: ${e.message}")
                }
            }
        }
    }

    private suspend fun fetchFeedersFromAPI(token: String): FeederListResponse = withContext(Dispatchers.IO) {
        val url = URL(FEEDER_LIST_URL)
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
                Log.d(TAG, "Response: $response")
                parseFeedersResponse(response)
            } else {
                throw Exception("HTTP Error: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseFeedersResponse(jsonString: String): FeederListResponse {
        val jsonObject = JSONObject(jsonString)

        val success = jsonObject.optBoolean("success", false)
        val username = jsonObject.optString("username", null)
        val escom = jsonObject.optString("escom", null)
        val count = jsonObject.optInt("count", 0)

        val feeders = mutableListOf<FeederItem>()
        val dataArray = jsonObject.optJSONArray("data")

        if (dataArray != null) {
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                feeders.add(
                    FeederItem(
                        FEEDER_NAME = item.optString("FEEDER_NAME", ""),
                        FEEDER_CODE = item.optString("FEEDER_CODE", ""),
                        FEEDER_CATEGORY = item.optString("FEEDER_CATEGORY", ""),
                        STATION_NAME = item.optString("STATION_NAME", "")
                    )
                )
            }
        }

        return FeederListResponse(success, username, escom, count, feeders)
    }

    private fun showLoading(show: Boolean) {
        if (show) {
            binding.rvFeeders.visibility = View.GONE
            binding.btnSubmit.isEnabled = false
            binding.btnSubmit.text = "Loading..."
        } else {
            binding.rvFeeders.visibility = View.VISIBLE
            binding.btnSubmit.isEnabled = true
            binding.btnSubmit.text = "SUBMIT"
        }
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        showLoading(false)
    }

    private fun submitConfirmations() {
        val confirmations = adapter.getConfirmations()

        // Check if all feeders are confirmed
        val allConfirmed = confirmations.all { it.value }

        if (!allConfirmed) {
            Snackbar.make(
                binding.root,
                "Please confirm all feeders or raise tickets for unconfirmed feeders",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        // TODO: Submit to API
        Snackbar.make(
            binding.root,
            "Feeder confirmations submitted successfully",
            Snackbar.LENGTH_SHORT
        ).show()

        // Navigate back or to home
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class FeederConfirmationAdapter(
    private val onRaiseTicketClick: (FeederData) -> Unit
) : RecyclerView.Adapter<FeederConfirmationAdapter.ViewHolder>() {

    private val feeders = mutableListOf<FeederData>()
    private val confirmations = mutableMapOf<String, Boolean>()

    fun submitList(list: List<FeederData>) {
        feeders.clear()
        feeders.addAll(list)
        // Initialize all as not confirmed (No selected by default)
        list.forEach { confirmations[it.code] = false }
        notifyDataSetChanged()
    }

    fun getConfirmations(): Map<String, Boolean> = confirmations

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFeederConfirmationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(feeders[position])
    }

    override fun getItemCount(): Int = feeders.size

    inner class ViewHolder(
        private val binding: ItemFeederConfirmationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(feeder: FeederData) {
            binding.apply {
                tvFeederName.text = feeder.name
                tvFeederCode.text = feeder.code
                tvFeederCategory.text = feeder.category

                // Set initial state
                val isConfirmed = confirmations[feeder.code] ?: false
                if (isConfirmed) {
                    rgConfirmation.check(R.id.rbYes)
                    btnRaiseTicket.visibility = View.GONE
                } else {
                    rgConfirmation.check(R.id.rbNo)
                    btnRaiseTicket.visibility = View.VISIBLE
                }

                // Handle radio button changes
                rgConfirmation.setOnCheckedChangeListener { _, checkedId ->
                    when (checkedId) {
                        R.id.rbYes -> {
                            confirmations[feeder.code] = true
                            feeder.confirmed = true
                            btnRaiseTicket.visibility = View.GONE
                        }
                        R.id.rbNo -> {
                            confirmations[feeder.code] = false
                            feeder.confirmed = false
                            btnRaiseTicket.visibility = View.VISIBLE
                        }
                    }
                }

                // Handle raise ticket button click
                btnRaiseTicket.setOnClickListener {
                    onRaiseTicketClick(feeder)
                }
            }
        }
    }
}