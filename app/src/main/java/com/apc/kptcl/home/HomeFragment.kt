package com.apc.kptcl.home

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentHomeBinding
import com.apc.kptcl.utils.JWTUtils
import com.apc.kptcl.utils.SessionManager
import com.google.android.material.datepicker.MaterialDatePicker
import java.text.SimpleDateFormat
import java.util.*
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val TAG = "HomeFragment"
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

        setupClickListeners()
        updateDateTime()
        displayTokenInfo() // ✅ Display JWT token information
        setupDCCFeatures() // ✅ Setup DCC-specific features

        binding.confirmationBtn.setOnClickListener {
            Toast.makeText(
                requireContext(),
                "This feature is coming soon",
                Toast.LENGTH_SHORT
            ).show()

//            findNavController().navigate(
//                R.id.action_homeFragment_to_feederConfirmation
//            )
        }

//        binding.hourlyViewBtn.setOnClickListener {
//            findNavController().navigate(R.id.action_homeFragment_to_feederViewFragment)
//        }
//        binding.dailyViewBtn.setOnClickListener {
//            findNavController().navigate(R.id.action_homeFragment_to_dailyParameterEntryFragment)
//        }
    }

    /**
     * ✅ Setup DCC-specific features based on user role
     */
    private fun setupDCCFeatures() {
        try {
            val token = SessionManager.getToken(requireContext())

            if (token.isEmpty()) {
                Log.w(TAG, "No token found")
                binding.dccBtn.visibility = View.GONE
                return
            }

            // Decode token to get role
            val payload = JWTUtils.decodeToken(token)

            if (payload == null) {
                Log.e(TAG, "Failed to decode token")
                binding.dccBtn.visibility = View.GONE
                return
            }

            // ✅ Check if user is DCC
            val isDCC = payload.role.lowercase() == "dcc"

            if (isDCC) {
                // ✅ Show DCC button
                binding.dccBtn.visibility = View.VISIBLE

                // ✅ Set up click listener
                binding.dccBtn.setOnClickListener {
                    navigateToDataValidator()
                }

                Log.d(TAG, "✅ DCC user detected - Reports button enabled")
            } else {
                // ✅ Hide button for non-DCC users
                binding.dccBtn.visibility = View.GONE
                Log.d(TAG, "ℹ️ Non-DCC user - Reports button hidden")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up DCC features", e)
            binding.dccBtn.visibility = View.GONE
        }
    }

    /**
     * ✅ Navigate to Data Validator screen (DCC only)
     */
    private fun navigateToDataValidator() {
        try {
            // Double-check user is DCC before navigating
            val token = SessionManager.getToken(requireContext())
            val payload = JWTUtils.decodeToken(token)

            if (payload?.role?.lowercase() == "dcc") {
                // ✅ Navigate to Data Validator Fragment
                findNavController().navigate(R.id.action_homeFragment_to_reportFragment)
                Log.d(TAG, "✅ Navigating to Data Validator")
            } else {
                // ✅ Show error if somehow non-DCC user clicked
                Toast.makeText(
                    requireContext(),
                    "❌ This feature is only available for DCC users",
                    Toast.LENGTH_LONG
                ).show()
                Log.w(TAG, "⚠️ Non-DCC user attempted to access Data Validator")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to Data Validator", e)
            Toast.makeText(
                requireContext(),
                "Error: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Display JWT token decoded information (simplified - no database details)
     */
    private fun displayTokenInfo() {
        try {
            val token = SessionManager.getToken(requireContext())

            if (token.isEmpty()) {
                Log.w(TAG, "No token found")
                return
            }

            Log.d(TAG, "📋 Decoding JWT token...")

            // Decode token
            val payload = JWTUtils.decodeToken(token)

            if (payload == null) {
                Log.e(TAG, "Failed to decode token")
                return
            }

            // Display ONLY username, role, escom, and token expiry/status
            binding.apply {
                // Username
                tvUsername.text = "STATION: ${payload.username}"

                // Role (capitalize first letter and add DCC badge)
                val roleFormatted = payload.role.replace("_", " ")
                    .split(" ")
                    .joinToString(" ") { it.capitalize() }

                // ✅ Add special formatting for DCC role
                if (payload.role.lowercase() == "dcc") {
                    tvRole.text = "ROLE: $roleFormatted 👑" // Crown emoji for DCC
                    tvRole.setTextColor(resources.getColor(R.color.primary_light, null))
                } else {
                    tvRole.text = "ROLE: $roleFormatted"
                }

                // ESCOM - update the existing tvEscom
                tvEscom.text = "ESCOM: ${payload.escom}"

                // Token expiry
                val expiryTime = JWTUtils.getExpiryTime(token)
                tvTokenExpiry.text = "Session Expires: $expiryTime"

                // Check if expired and update status
                val isExpired = JWTUtils.isTokenExpired(token)
                if (isExpired) {
                    tvTokenStatus.text = "⚠️ Session Expired"
                    tvTokenStatus.setTextColor(resources.getColor(R.color.red_500, null))
                } else {
                    tvTokenStatus.text = "✓ Session Active"
                    tvTokenStatus.setTextColor(resources.getColor(R.color.green_500, null))
                }
            }

            Log.d(TAG, "✅ Token info displayed successfully")
            Log.d(TAG, "   Username: ${payload.username}")
            Log.d(TAG, "   Role: ${payload.role}")
            Log.d(TAG, "   ESCOM: ${payload.escom}")
            Log.d(TAG, "   Expires: ${JWTUtils.getExpiryTime(token)}")

        } catch (e: Exception) {
            Log.e(TAG, "Error displaying token info", e)
        }
    }

    private fun setupClickListeners() {
//        binding.btnDateFilter.setOnClickListener {
//            showDateRangePicker()
//        }
    }

    private fun showDateRangePicker() {
        val constraintsBuilder = CalendarConstraints.Builder()
            .setValidator(DateValidatorPointForward.now())

        val dateRangePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Select Date Range")
            .setTheme(R.style.CustomDatePickerTheme)
            .setCalendarConstraints(constraintsBuilder.build())
            .build()

        dateRangePicker.addOnPositiveButtonClickListener { selection ->
            val startDate = selection.first
            val endDate = selection.second
            filterByDateRange(startDate, endDate)
        }

        dateRangePicker.show(parentFragmentManager, "DATE_RANGE_PICKER")
    }

    private fun filterByDateRange(startDate: Long, endDate: Long) {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val startStr = dateFormat.format(Date(startDate))
        val endStr = dateFormat.format(Date(endDate))
        // Apply date filter to data
        Log.d(TAG, "Date filter: $startStr to $endStr")
    }

    private fun updateDateTime() {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val now = Date()

        binding.tvDate.text = "Date: ${dateFormat.format(now)}"
        binding.tvTime.text = "Time: ${timeFormat.format(now)}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}