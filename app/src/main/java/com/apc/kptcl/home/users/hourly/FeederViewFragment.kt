package com.apc.kptcl.home.users.hourly

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentFeederViewBinding
import com.apc.kptcl.home.adapter.*
import com.apc.kptcl.utils.SessionManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class FeederViewFragment : Fragment() {

    private var _binding: FragmentFeederViewBinding? = null
    private val binding get() = _binding!!

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // Repository instance
    private val repository = FeederHourlyRepository()

    private lateinit var adapter: FeederHourlyViewAdapter
    private var allHourlyData = listOf<FeederHourlyData>()
    private var feederList = listOf<FeederInfo>()
    private var selectedFeederId: String? = null  // ✅ Now nullable
    private var selectedFeederName: String? = null

    companion object {
        private const val TAG = "FeederViewFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeederViewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check authentication
        if (!SessionManager.isLoggedIn(requireContext())) {
            Snackbar.make(binding.root, "Please login first", Snackbar.LENGTH_LONG).show()
            Log.e(TAG, "User not logged in")
            return
        }

        Log.d(TAG, "Fragment created, initializing views")
        setupViews()
        loadAllFeeders()
    }

    private fun setupViews() {
        // Setup date picker
        binding.etDate.setText(dateFormat.format(calendar.time))
        binding.etDate.setOnClickListener {
            showDatePicker()
        }

        // Setup RecyclerView with adapter
        adapter = FeederHourlyViewAdapter()

        binding.rvViewData.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                context,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                false
            )
            this.adapter = this@FeederViewFragment.adapter
        }

        // Setup buttons
        binding.btnSearch.setOnClickListener {
            searchFeederData()
        }

        Log.d(TAG, "Views setup complete")
    }

    private fun showDatePicker() {
        val datePickerDialog = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                binding.etDate.setText(dateFormat.format(calendar.time))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        // ✅ FUTURE DATES DISABLE
        datePickerDialog.datePicker.maxDate = System.currentTimeMillis()

        datePickerDialog.show()
    }

    private fun loadAllFeeders() {
        val token = SessionManager.getToken(requireContext())
        if (token.isEmpty()) {
            Log.e(TAG, "No authentication token found")
            Snackbar.make(binding.root, "No authentication token", Snackbar.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "Token retrieved: ${token.take(20)}...")

        binding.btnSearch.isEnabled = false

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Starting to load all feeders from API...")
                val result = repository.fetchAllFeeders(token)

                if (result.isSuccess) {
                    feederList = result.getOrNull() ?: emptyList()
                    Log.d(TAG, "Feeder list retrieved: ${feederList.size} feeders")

                    if (feederList.isNotEmpty()) {
                        setupFeederDropdown()
                        Log.d(TAG, "✅ Successfully loaded ${feederList.size} feeders")
                        Snackbar.make(
                            binding.root,
                            "Loaded ${feederList.size} feeders",
                            Snackbar.LENGTH_SHORT
                        ).show()
                    } else {
                        Log.w(TAG, "⚠️ No feeders found in API response!")
                        Snackbar.make(
                            binding.root,
                            "No feeders found",
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                    binding.btnSearch.isEnabled = true
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "❌ Error loading feeders: $error")
                    binding.btnSearch.isEnabled = false
                    Snackbar.make(binding.root, "Error loading feeders: $error", Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception loading feeders", e)
                binding.btnSearch.isEnabled = false
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setupFeederDropdown() {
        if (feederList.isEmpty()) {
            Log.w(TAG, "Feeder list is empty, cannot setup dropdown")
            return
        }

        // ✅ FIXED: Show code if available, else show "NO CODE"
        val feederDisplayList = feederList.map {
            "${it.feederName} (${it.feederId ?: "NO CODE"})"
        }

        Log.d(TAG, "Setting up dropdown with ${feederDisplayList.size} items")

        val feederAdapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item_black,
            feederDisplayList
        )

        feederAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_black)

        binding.actvStation.adapter = feederAdapter

        binding.actvStation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < feederList.size) {
                    selectedFeederId = feederList[position].feederId  // ✅ Can be null
                    selectedFeederName = feederList[position].feederName
                    Log.d(TAG, "✅ Selected: $selectedFeederName (ID: ${selectedFeederId ?: "NO CODE"})")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                Log.d(TAG, "No feeder selected")
            }
        }

        Log.d(TAG, "Dropdown setup complete")
    }

    private fun searchFeederData() {
        // ✅ FIXED: Check for feederName instead of feederId
        if (selectedFeederName == null) {
            Log.w(TAG, "No feeder selected")
            Snackbar.make(binding.root, "Please select a feeder first", Snackbar.LENGTH_SHORT).show()
            return
        }

        val token = SessionManager.getToken(requireContext())
        if (token.isEmpty()) {
            Log.e(TAG, "No authentication token")
            Snackbar.make(binding.root, "Authentication token missing", Snackbar.LENGTH_LONG).show()
            return
        }

        binding.btnSearch.isEnabled = false
        binding.btnSearch.text = "Loading..."

        Log.d(TAG, "Starting search for feeder: $selectedFeederName (ID: ${selectedFeederId ?: "NONE"})")

        lifecycleScope.launch {
            try {
                val selectedDate = binding.etDate.text.toString()
                Log.d(TAG, "📅 Searching for date: $selectedDate")

                // ✅ FIXED: Pass both feederId and feederName
                val result = repository.fetchFeederHourlyData(
                    feederId = selectedFeederId,      // ✅ Can be null
                    feederName = selectedFeederName!!, // ✅ Required
                    token = token,
                    date = selectedDate,
                    limit = 100
                )

                if (result.isSuccess) {
                    val response = result.getOrNull()

                    if (response != null && response.data.isNotEmpty()) {
                        allHourlyData = response.data

                        Log.d(TAG, "✅ Found ${allHourlyData.size} records for date: $selectedDate")

                        // Group by parameter
                        val groupedData = allHourlyData.groupBy { it.parameter }
                        Log.d(TAG, "Parameters found: ${groupedData.keys.joinToString()}")

                        // Display data
                        adapter.submitList(allHourlyData)

                        // Scroll to show all data
                        binding.rvViewData.post {
                            if (adapter.itemCount > 3) {
                                binding.rvViewData.scrollToPosition(adapter.itemCount - 1)
                                binding.rvViewData.postDelayed({
                                    binding.rvViewData.smoothScrollToPosition(0)
                                }, 200)
                            }
                        }

                        Snackbar.make(
                            binding.root,
                            "✅ Loaded ${groupedData.size} parameters for $selectedDate",
                            Snackbar.LENGTH_SHORT
                        ).show()
                    } else {
                        Log.w(TAG, "⚠️ No data found for date: $selectedDate")
                        adapter.clearData()
                        Snackbar.make(
                            binding.root,
                            "No data found for $selectedDate",
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "❌ Error fetching data: $error")
                    Snackbar.make(binding.root, "Error: $error", Snackbar.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception fetching data", e)
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.btnSearch.isEnabled = true
                binding.btnSearch.text = "SEARCH"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        Log.d(TAG, "Fragment destroyed")
    }
}