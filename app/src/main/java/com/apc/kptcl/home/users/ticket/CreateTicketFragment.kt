package com.apc.kptcl.home.users.ticket

import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentCreateTicketBinding
import com.apc.kptcl.home.users.ticket.dataclass.CreateTicketRequest
import com.apc.kptcl.home.users.ticket.dataclass.TicketApiService
import com.apc.kptcl.utils.SessionManager
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*

class CreateTicketFragment : Fragment() {

    private var _binding: FragmentCreateTicketBinding? = null
    private val binding get() = _binding!!

    // ✅ FIXED: Changed to match portal format (YYYY-MM-DD HH:MM) without timezone
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // Variables to store passed feeder data
    private var passedFeederName: String? = null
    private var passedFeederCode: String? = null
    private var passedFeederCategory: String? = null

    companion object {
        private const val TAG = "CreateTicketFragment"
        // Create ticket API is on port 5016
        private const val CREATE_TICKET_API_BASE_URL = "http://62.72.59.119:5016/"
    }

    // API setup
    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(CREATE_TICKET_API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val apiService by lazy { retrofit.create(TicketApiService::class.java) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateTicketBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get arguments passed from FeederConfirmationFragment
        passedFeederName = arguments?.getString("feederName")
        passedFeederCode = arguments?.getString("feederCode")
        passedFeederCategory = arguments?.getString("feederCategory")

        Log.d(TAG, "Received arguments:")
        Log.d(TAG, "  Feeder Name: $passedFeederName")
        Log.d(TAG, "  Feeder Code: $passedFeederCode")
        Log.d(TAG, "  Feeder Category: $passedFeederCategory")

        setupToolbar()
        setupDropdowns()
        setupInitialValues()
        setupButtons()
    }

    private fun setupToolbar() {
//        binding.toolbar.setNavigationOnClickListener {
//            activity?.onBackPressed()
//        }
    }

    private fun setupDropdowns() {
        // Classification dropdown - matches portal classifications
        val classifications = arrayOf(
            "FEEDER CODE",
            "FEEDER NAME",
            "FEEDER CATEGORY",
            "FEEDER STATUS",
            "NEW FEEDER ADDITION",
            "GENERAL TICKET",
            "EQUIPMENT ISSUE",
            "POWER OUTAGE",
            "MAINTENANCE REQUEST"
        )
        val classificationAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            classifications
        )
        binding.actvClassification.setAdapter(classificationAdapter)

        // Feeder Name dropdown - You can load these from API or database
        val feederNames = arrayOf(
            "F1- Sirasagi Maddi",
            "F2- High Court",
            "F3- City Station",
            "F4- Kionics"
        )
        val feederNameAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            feederNames
        )
        binding.actvFeederName.setAdapter(feederNameAdapter)

        // Feeder Category dropdown
        val feederCategories = arrayOf(
            "11KV",
            "33KV",
            "66KV",
            "110KV",
            "220KV",
            "URBAN",
            "RURAL",
            "INDUSTRIAL"
        )
        val feederCategoryAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            feederCategories
        )
        binding.actvFeederCategory.setAdapter(feederCategoryAdapter)

        // ✅ NEW: Add listener to handle classification-specific UI changes
        binding.actvClassification.setOnItemClickListener { _, _, position, _ ->
            handleClassificationChange(classifications[position])
        }
    }

    /**
     * ✅ NEW: Handle UI changes based on selected classification
     */
    private fun handleClassificationChange(classification: String) {
        when (classification.uppercase()) {
            "FEEDER NAME" -> {
                // Show old/new feeder name fields if you have them
                Toast.makeText(requireContext(), "Please provide old and new feeder names", Toast.LENGTH_SHORT).show()
            }
            "FEEDER CATEGORY" -> {
                // Emphasize new category field
                Toast.makeText(requireContext(), "Please select new feeder category", Toast.LENGTH_SHORT).show()
            }
            "FEEDER STATUS" -> {
                // Show status options
                Toast.makeText(requireContext(), "Please specify new feeder status", Toast.LENGTH_SHORT).show()
            }
            "NEW FEEDER ADDITION" -> {
                // Clear existing feeder selection
                Toast.makeText(requireContext(), "Adding new feeder - provide name and category", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupInitialValues() {
        // Set read-only values from SessionManager
        val username = SessionManager.getUsername(requireContext())
        binding.etUsername.setText(username)

        // ✅ FIXED: Set default classification to match portal default
        binding.actvClassification.setText("FEEDER CODE", false)

        // ✅ FIXED: Set current datetime in IST format matching portal
        binding.etStartDateTime.setText(getCurrentDateTime())

        // ✅ FIXED: Changed from "OPEN" to "ACTIVE" to match portal
        binding.etTicketStatus.setText("ACTIVE")

        // ✅ PRE-FILL FEEDER DATA IF PASSED FROM CONFIRMATION PAGE
        if (!passedFeederName.isNullOrEmpty()) {
            binding.actvFeederName.setText(passedFeederName, false)
            Log.d(TAG, "✅ Pre-filled Feeder Name: $passedFeederName")
        }

        if (!passedFeederCategory.isNullOrEmpty()) {
            binding.actvFeederCategory.setText(passedFeederCategory, false)
            Log.d(TAG, "✅ Pre-filled Feeder Category: $passedFeederCategory")
        }

        // Show a toast to inform user that feeder is pre-selected
        if (!passedFeederName.isNullOrEmpty()) {
            Toast.makeText(
                requireContext(),
                "Feeder pre-selected: $passedFeederName",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * ✅ FIXED: Get current datetime in IST timezone matching portal format
     * Portal uses: datetime.now(ist).strftime("%Y-%m-%d %H:%M")
     */
    private fun getCurrentDateTime(): String {
        // Get current time in IST (India Standard Time - UTC+5:30)
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        return dateTimeFormat.format(calendar.time)
    }

    private fun setupButtons() {
        binding.btnSubmit.setOnClickListener {
            if (validateForm()) {
                submitTicket()
            }
        }
    }

    private fun validateForm(): Boolean {
        var isValid = true

        if (binding.etDepartment.text.isNullOrBlank()) {
            binding.etDepartment.error = "Department is required"
            isValid = false
        }

        if (binding.etEmail.text.isNullOrBlank()) {
            binding.etEmail.error = "Email is required"
            isValid = false
        } else if (!Patterns.EMAIL_ADDRESS.matcher(binding.etEmail.text.toString()).matches()) {
            binding.etEmail.error = "Invalid email format"
            isValid = false
        }

        if (binding.etContact.text.isNullOrBlank()) {
            binding.etContact.error = "Contact number is required"
            isValid = false
        } else if (binding.etContact.text.toString().length < 10) {
            binding.etContact.error = "Contact must be at least 10 digits"
            isValid = false
        }

        if (binding.actvClassification.text.isNullOrBlank()) {
            binding.actvClassification.error = "Classification is required"
            isValid = false
        }

        // ✅ Classification-specific validation
        val classification = binding.actvClassification.text.toString().uppercase()

        when (classification) {
            "FEEDER CODE", "FEEDER CATEGORY", "FEEDER STATUS" -> {
                if (binding.actvFeederName.text.isNullOrBlank()) {
                    binding.actvFeederName.error = "Feeder name is required for $classification"
                    isValid = false
                }
                if (binding.actvFeederCategory.text.isNullOrBlank()) {
                    binding.actvFeederCategory.error = "Feeder category is required for $classification"
                    isValid = false
                }
            }
            "NEW FEEDER ADDITION" -> {
                // For new feeder, we need name and category but not existing feeder selection
                // This would require additional UI fields for new feeder name/category
            }
        }

        if (binding.etProblemStatement.text.isNullOrBlank()) {
            binding.etProblemStatement.error = "Problem statement is required"
            isValid = false
        }

        return isValid
    }

    private fun submitTicket() {
        // Show loading
        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.text = "Submitting..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get token from SessionManager
                val token = SessionManager.getToken(requireContext())

                if (token.isEmpty()) {
                    showError("Session expired. Please login again.")
                    return@launch
                }

                // ✅ FIXED: Prepare request body with proper classification details
                val classification = binding.actvClassification.text.toString()
                val feederName = binding.actvFeederName.text.toString()
                val feederCategory = binding.actvFeederCategory.text.toString()

                val request = CreateTicketRequest(
                    ticketClassification = classification,
                    problemStatement = binding.etProblemStatement.text.toString(),
                    feederName = feederName,
                    feederCategory = feederCategory,
                    emailId = binding.etEmail.text.toString(),
                    mobileNumber = binding.etContact.text.toString(),
                    userDepartment = binding.etDepartment.text.toString(),
                    attachmentName = null,
                    attachment = null,
                    resolutionProvided = null,
                    // ✅ FIXED: Include feeder code if available (for FEEDER CODE classification)
                    feederCode = passedFeederCode,
                    // ✅ REMOVED: classificationDetails - API will build this server-side
                    // ✅ REMOVED: detailsDict - not needed
                    // ✅ REMOVED: status - API sets this to "ACTIVE"
                )

                Log.d(TAG, "Creating ticket...")
                Log.d(TAG, "Token: ${token.take(50)}...")
                Log.d(TAG, "API URL: $CREATE_TICKET_API_BASE_URL")
                Log.d(TAG, "Classification: $classification")
                Log.d(TAG, "Feeder Name: $feederName")
                Log.d(TAG, "Feeder Category: $feederCategory")
                Log.d(TAG, "Feeder Code: $passedFeederCode")

                val response = apiService.createTicket("Bearer $token", request)

                Log.d(TAG, "Response code: ${response.code()}")

                if (response.isSuccessful && response.body() != null) {
                    val ticketResponse = response.body()!!

                    Log.d(TAG, "Response: $ticketResponse")

                    if (ticketResponse.success) {
                        Toast.makeText(
                            requireContext(),
                            "✅ Ticket created successfully!\nTicket ID: ${ticketResponse.ticketId ?: ""}\nStatus: ACTIVE",
                            Toast.LENGTH_LONG
                        ).show()

                        // Navigate back to view tickets
                        findNavController().popBackStack()
                    } else {
                        showError(ticketResponse.message ?: "Failed to create ticket")
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "API Error - Code: ${response.code()}, Message: ${response.message()}")
                    Log.e(TAG, "Error body: $errorBody")

                    when (response.code()) {
                        401 -> showError("Session expired. Please login again.")
                        400 -> showError("Invalid ticket data. Please check all fields.\n$errorBody")
                        else -> showError("Error: ${response.code()} - ${response.message()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                showError("Network error: ${e.message}")
                e.printStackTrace()
            } finally {
                // Reset button
                binding.btnSubmit.isEnabled = true
                binding.btnSubmit.text = "SUBMIT"
            }
        }
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}