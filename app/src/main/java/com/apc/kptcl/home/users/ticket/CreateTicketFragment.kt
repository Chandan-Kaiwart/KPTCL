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
import java.text.SimpleDateFormat
import java.util.*

class CreateTicketFragment : Fragment() {

    private var _binding: FragmentCreateTicketBinding? = null
    private val binding get() = _binding!!

    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // Passed feeder data from FeederConfirmationFragment
    private var passedFeederName: String? = null
    private var passedFeederCode: String? = null
    private var passedFeederCategory: String? = null
    private var isFromFeederConfirmation = false

    companion object {
        private const val TAG = "CreateTicket"
        private const val API_URL = "http://62.72.59.119:5016/api/ticket/create"
        private const val TIMEOUT = 15000
    }

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

        // Get passed arguments
        passedFeederName = arguments?.getString("feederName")
        passedFeederCode = arguments?.getString("feederCode")
        passedFeederCategory = arguments?.getString("feederCategory")
        isFromFeederConfirmation = !passedFeederName.isNullOrEmpty()

        Log.d(TAG, "📍 Entry: ${if (isFromFeederConfirmation) "Feeder Confirmation" else "Direct"}")
        Log.d(TAG, "📋 Feeder: $passedFeederName | Code: $passedFeederCode | Category: $passedFeederCategory")

        setupDropdowns()
        setupInitialValues()
        setupButtons()
    }

    private fun setupDropdowns() {
        // Different classifications based on entry point
        val classifications = if (isFromFeederConfirmation) {
            arrayOf(
                "FEEDER CODE",
                "FEEDER NAME",
                "FEEDER CATEGORY",
                "FEEDER STATUS",
                "NEW FEEDER ADDITION",
                "GENERAL TICKET"
            )
        } else {
            arrayOf("GENERAL TICKET")
        }

        binding.actvClassification.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, classifications)
        )

        // Feeder Categories (for display only - pre-filled)
        val feederCategories = arrayOf(
            "AGRICULTURE",
            "INDUSTRIAL",
            "NJY",
            "RURAL",
            "URBAN",
            "WATERSUPPLY"
        )
        binding.actvFeederCategory.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, feederCategories)
        )

        // New Feeder Category dropdown (for changing category)
        binding.actvNewFeederCategory.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, feederCategories)
        )

        // ✅ FIXED: Status dropdown - Only INACTIVE (ACTIVE removed)
        binding.actvNewStatus.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line,
                arrayOf("INACTIVE"))  // Only INACTIVE option
        )

        // Handle classification change
        binding.actvClassification.setOnItemClickListener { _, _, position, _ ->
            handleClassificationChange(classifications[position])
        }

        // ✅ FIXED: Disable both feeder name AND category if from confirmation
        if (isFromFeederConfirmation) {
            binding.actvFeederName.isEnabled = false
            binding.actvFeederCategory.isEnabled = false  // ✅ Non-editable
        } else {
            binding.actvFeederName.isEnabled = false
            binding.actvFeederCategory.isEnabled = false
        }
    }

    private fun handleClassificationChange(classification: String) {
        // Hide all dynamic fields
        binding.tilNewFeederCode.visibility = View.GONE
        binding.tilNewFeederName.visibility = View.GONE
        binding.tilNewFeederCategory.visibility = View.GONE
        binding.tilNewStatus.visibility = View.GONE

        when (classification.uppercase()) {
            "FEEDER CODE" -> {
                // ✅ FIXED: Show field but make it non-editable with toast
                binding.tilNewFeederCode.visibility = View.VISIBLE
                binding.etNewFeederCode.isEnabled = false  // ✅ Non-editable
                binding.etNewFeederCode.hint = "Will be assigned by DCC"
                Toast.makeText(
                    requireContext(),
                    "⚠️ Only DCC can assign feeder code\nTicket will be raised for code request",
                    Toast.LENGTH_LONG
                ).show()
            }
            "FEEDER NAME" -> {
                binding.tilNewFeederName.visibility = View.VISIBLE
                Toast.makeText(requireContext(), "💡 Enter new feeder name", Toast.LENGTH_SHORT).show()
            }
            "FEEDER CATEGORY" -> {
                binding.tilNewFeederCategory.visibility = View.VISIBLE
                Toast.makeText(requireContext(), "💡 Select new category", Toast.LENGTH_SHORT).show()
            }
            "FEEDER STATUS" -> {
                binding.tilNewStatus.visibility = View.VISIBLE
                Toast.makeText(requireContext(), "💡 Select INACTIVE to disable feeder", Toast.LENGTH_SHORT).show()
            }
            "NEW FEEDER ADDITION" -> {
                binding.tilNewFeederName.visibility = View.VISIBLE
                binding.tilNewFeederCategory.visibility = View.VISIBLE
                // ✅ FIXED: New feeder code also non-editable
                binding.tilNewFeederCode.visibility = View.VISIBLE
                binding.etNewFeederCode.isEnabled = false
                binding.etNewFeederCode.hint = "Will be assigned by DCC"
                Toast.makeText(
                    requireContext(),
                    "⚠️ Only DCC can assign feeder code\nEnter name & category, code will be assigned later",
                    Toast.LENGTH_LONG
                ).show()
            }
            "GENERAL TICKET" -> {
                Toast.makeText(requireContext(), "💡 Describe your problem", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupInitialValues() {
        val username = SessionManager.getUsername(requireContext())
        binding.etUsername.setText(username)

        if (isFromFeederConfirmation) {
            binding.actvClassification.setText("FEEDER CODE", false)
            handleClassificationChange("FEEDER CODE")
        } else {
            binding.actvClassification.setText("GENERAL TICKET", false)
            handleClassificationChange("GENERAL TICKET")
        }

        binding.etStartDateTime.setText(getCurrentDateTime())
        binding.etTicketStatus.setText("ACTIVE")

        // ✅ FIXED: Pre-fill feeder data (both name AND category)
        if (isFromFeederConfirmation) {
            binding.actvFeederName.setText(passedFeederName, false)
            binding.actvFeederCategory.setText(passedFeederCategory, false)  // ✅ Pre-filled, non-editable
            Toast.makeText(
                requireContext(),
                "✅ Feeder: $passedFeederName\n📂 Category: $passedFeederCategory",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun getCurrentDateTime(): String {
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
            binding.etDepartment.error = "Required"
            isValid = false
        }

        if (binding.etEmail.text.isNullOrBlank()) {
            binding.etEmail.error = "Required"
            isValid = false
        } else if (!Patterns.EMAIL_ADDRESS.matcher(binding.etEmail.text.toString()).matches()) {
            binding.etEmail.error = "Invalid email"
            isValid = false
        }

        if (binding.etContact.text.isNullOrBlank()) {
            binding.etContact.error = "Required"
            isValid = false
        }

        if (binding.actvClassification.text.isNullOrBlank()) {
            binding.actvClassification.error = "Required"
            isValid = false
        }

        if (binding.etProblemStatement.text.isNullOrBlank()) {
            binding.etProblemStatement.error = "Required"
            isValid = false
        }

        // Validate based on classification
        val classification = binding.actvClassification.text.toString().uppercase()

        when (classification) {
            "FEEDER CODE" -> {
                // ✅ FIXED: No validation needed - DCC will assign code
                // Just check problem statement is filled
            }
            "FEEDER NAME" -> {
                if (binding.etNewFeederName.text.isNullOrBlank()) {
                    binding.etNewFeederName.error = "New name required"
                    isValid = false
                }
            }
            "FEEDER CATEGORY" -> {
                if (binding.actvNewFeederCategory.text.isNullOrBlank()) {
                    binding.actvNewFeederCategory.error = "New category required"
                    isValid = false
                }
            }
            "FEEDER STATUS" -> {
                if (binding.actvNewStatus.text.isNullOrBlank()) {
                    binding.actvNewStatus.error = "Select INACTIVE"
                    isValid = false
                }
            }
            "NEW FEEDER ADDITION" -> {
                if (binding.etNewFeederName.text.isNullOrBlank() ||
                    binding.actvNewFeederCategory.text.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "Name & category required\n(Code will be assigned by DCC)", Toast.LENGTH_SHORT).show()
                    isValid = false
                }
                // ✅ FIXED: No code validation - DCC assigns it
            }
        }

        return isValid
    }

    private fun submitTicket() {
        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.text = "Submitting..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SessionManager.getToken(requireContext())

                if (token.isEmpty()) {
                    showError("Session expired")
                    return@launch
                }

                val classification = binding.actvClassification.text.toString()
                val requestJson = buildRequestJson(classification)

                Log.d(TAG, "🚀 Submitting ticket")
                Log.d(TAG, "📦 Request: $requestJson")

                val response = createTicketAPI(token, requestJson)

                withContext(Dispatchers.Main) {
                    if (response.success) {
                        Toast.makeText(
                            requireContext(),
                            "✅ Ticket ${response.ticketId} created!\nStatus: ACTIVE\nWaiting for DCC approval",
                            Toast.LENGTH_LONG
                        ).show()
                        findNavController().popBackStack()
                    } else {
                        showError(response.message)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error", e)
                withContext(Dispatchers.Main) {
                    showError("Network error: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.btnSubmit.isEnabled = true
                    binding.btnSubmit.text = "SUBMIT"
                }
            }
        }
    }

    private fun buildRequestJson(classification: String): JSONObject {
        val json = JSONObject()

        // Common fields
        json.put("ticketClassification", classification)
        json.put("problemStatement", binding.etProblemStatement.text.toString())
        json.put("emailId", binding.etEmail.text.toString())
        json.put("mobileNumber", binding.etContact.text.toString())
        json.put("userDepartment", binding.etDepartment.text.toString())

        // Add feeder data if from confirmation
        if (isFromFeederConfirmation) {
            json.put("feederName", passedFeederName)
            json.put("feederCode", passedFeederCode)
            json.put("feederCategory", passedFeederCategory)

            // Add new values based on classification
            when (classification.uppercase()) {
                "FEEDER CODE" -> {
                    json.put("oldFeederCode", passedFeederCode)
                    // ✅ FIXED: No newFeederCode - DCC will assign
                    Log.d(TAG, "📦 FEEDER CODE change request (DCC will assign new code)")
                }

                "FEEDER NAME" -> {
                    json.put("oldFeederName", passedFeederName)
                    json.put("newFeederName", binding.etNewFeederName.text.toString())
                    Log.d(TAG, "📦 NEW_FEEDER_NAME: ${binding.etNewFeederName.text}")
                }

                "FEEDER CATEGORY" -> {
                    json.put("oldFeederCategory", passedFeederCategory)
                    json.put("newFeederCategory", binding.actvNewFeederCategory.text.toString())
                    Log.d(TAG, "📦 NEW_FEEDER_CATEGORY: ${binding.actvNewFeederCategory.text}")
                }

                "FEEDER STATUS" -> {
                    json.put("oldStatus", "ACTIVE")
                    json.put("newStatus", binding.actvNewStatus.text.toString())  // Will be "INACTIVE"
                    Log.d(TAG, "📦 NEW_STATUS: ${binding.actvNewStatus.text}")
                }

                "NEW FEEDER ADDITION" -> {
                    json.put("newFeederName", binding.etNewFeederName.text.toString())
                    json.put("newFeederCategory", binding.actvNewFeederCategory.text.toString())
                    // ✅ FIXED: No newFeederCode - DCC will assign
                    Log.d(TAG, "📦 NEW FEEDER (DCC will assign code)")
                }
            }
        }

        return json
    }

    private suspend fun createTicketAPI(token: String, requestJson: JSONObject): TicketResponse = withContext(Dispatchers.IO) {
        val url = URL(API_URL)
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
                Log.d(TAG, "✅ Response: $response")
                parseTicketResponse(response)
            } else {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                } catch (e: Exception) {
                    "No error body"
                }
                Log.e(TAG, "❌ Error $responseCode: $errorBody")
                TicketResponse(false, "Error: $responseCode", null)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTicketResponse(jsonString: String): TicketResponse {
        val jsonObject = JSONObject(jsonString)
        return TicketResponse(
            success = jsonObject.optBoolean("success", false),
            message = jsonObject.optString("message", "Unknown error"),
            ticketId = jsonObject.optString("ticketId", null)
        )
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class TicketResponse(
        val success: Boolean,
        val message: String,
        val ticketId: String?
    )
}