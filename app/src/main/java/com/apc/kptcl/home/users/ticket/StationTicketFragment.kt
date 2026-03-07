package com.apc.kptcl.home.users.ticket

import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentCreateTicket2Binding
import com.apc.kptcl.home.users.ticket.dataclass.FeederData
import com.apc.kptcl.home.users.ticket.dataclass.FeederItem
import com.apc.kptcl.home.users.ticket.dataclass.FeederListResponse
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

/**
 * ✅ StationTicketFragment - Feeder-Specific Ticket Creation
 *
 * - Feeder selection via dialog
 * - Category auto-populates
 * - All classifications available (FEEDER NAME, CATEGORY, STATUS, NEW FEEDER ADDITION)
 * - Feeder fields required
 */
class StationTicketFragment : Fragment() {

    private var _binding: FragmentCreateTicket2Binding? = null
    private val binding get() = _binding!!

    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // ✅ Feeder selection variables
    private var selectedFeederData: FeederData? = null
    private var allFeeders = listOf<FeederData>()
    private var feederCategories = listOf<String>()

    // ✅ Double-submit guard
    private var isSubmitting = false

    companion object {
        private const val TAG = "StationTicket"
        private const val TICKET_API_URL = "http://62.72.59.119:9009/api/ticket/create"
        private const val FEEDER_LIST_URL = "http://62.72.59.119:9009/api/feeder/list"
        private const val CATEGORY_API_URL = "http://62.72.59.119:9009/api/feeder/categories"
        private const val TIMEOUT = 15000
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateTicket2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupStationTicketMode()
        setupCommonFields()
        setupButtons()
    }

    /**
     * ✅ Setup for Station Ticket - Feeder selection with dialog
     */
    private fun setupStationTicketMode() {
        Log.d(TAG, "🏢 Setting up STATION TICKET mode")

        // ✅ Show ALL classifications for station tickets
        val classifications = arrayOf(
            "FEEDER NAME",
            "FEEDER CATEGORY",
            "FEEDER STATUS",
            "NEW FEEDER ADDITION"
        )

        binding.actvClassification.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, classifications)
        )

        // ✅ Load feeders and setup click dialog
        loadFeederList()

        Toast.makeText(
            requireContext(),
            "Click feeder field to select",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * ✅ Load feeders from API and setup AutoCompleteTextView dropdown
     */
    private fun loadFeederList() {
        val token = SessionManager.getToken(requireContext())

        if (token.isEmpty()) {
            showError("Token missing. Please login again.")
            return
        }

        Log.d(TAG, "📄 Fetching feeders from API...")
        binding.actvFeederName.hint = "Loading feeders..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    fetchFeedersFromAPI(token)
                }

                if (response.success) {
                    allFeeders = response.data.map { item ->
                        FeederData(
                            name = item.FEEDER_NAME,
                            code = item.FEEDER_CODE,
                            category = item.FEEDER_CATEGORY,
                            confirmed = false
                        )
                    }

                    Log.d(TAG, "✅ Loaded ${allFeeders.size} feeders")

                    // ✅ Setup AutoCompleteTextView with feeder names
                    setupFeederDropdown()

                    binding.actvFeederName.hint = "Select feeder"
                    Toast.makeText(requireContext(), "Loaded ${allFeeders.size} feeders", Toast.LENGTH_SHORT).show()
                } else {
                    showError("Failed to load feeders")
                    binding.actvFeederName.hint = "Error loading feeders"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading feeders", e)
                showError("Failed to load feeders: ${e.message}")
                binding.actvFeederName.hint = "Error loading feeders"
            }
        }
    }

    /**
     * ✅ Setup AutoCompleteTextView dropdown for feeder selection
     */
    private fun setupFeederDropdown() {
        // ✅ Create adapter with feeder names
        val feederNames = allFeeders.map { it.name }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            feederNames
        )

        binding.actvFeederName.setAdapter(adapter)

        // ✅ Listen for feeder selection
        binding.actvFeederName.setOnItemClickListener { _, _, position, _ ->
            selectedFeederData = allFeeders[position]

            // ✅ Auto-populate category
            binding.actvFeederCategory.setText(selectedFeederData?.category ?: "", false)

            Log.d(TAG, "✅ Feeder selected: ${selectedFeederData?.name}")
            Log.d(TAG, "✅ Category auto-populated: ${selectedFeederData?.category}")

            Toast.makeText(
                requireContext(),
                "Selected: ${selectedFeederData?.name}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * ✅ Setup common fields - FIXED FUNCTION
     */
    private fun setupCommonFields() {
        // ✅ Load feeder categories from API
        loadFeederCategories()

        // ✅ Setup status dropdown
        val statusOptions = arrayOf("INACTIVE")
        binding.actvNewStatus.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, statusOptions)
        )

        // ✅ Auto-fill username
        binding.etUsername.setText(SessionManager.getUsername(requireContext()))

        // ✅ Set current date-time
        binding.etStartDateTime.setText(dateTimeFormat.format(Date()))

        // ✅ Set default ticket status
        binding.etTicketStatus.setText("OPEN")

        // ✅ Setup classification change listener
        binding.actvClassification.setOnItemClickListener { _, _, _, _ ->
            updateFieldsBasedOnClassification()
        }
    }

    /**
     * ✅ Load feeder categories from API - FIXED FUNCTION
     */
    private fun loadFeederCategories() {
        val token = SessionManager.getToken(requireContext())

        if (token.isEmpty()) {
            Log.e(TAG, "Token missing for category API")
            return
        }

        Log.d(TAG, "📄 Fetching categories from API...")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    fetchCategoriesFromAPI(token)
                }

                if (response.success) {
                    feederCategories = response.categories
                    Log.d(TAG, "✅ Loaded ${feederCategories.size} categories")

                    // ✅ Setup category dropdown
                    binding.actvNewFeederCategory.setAdapter(
                        ArrayAdapter(
                            requireContext(),
                            android.R.layout.simple_dropdown_item_1line,
                            feederCategories
                        )
                    )
                } else {
                    Log.e(TAG, "Failed to load categories")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading categories", e)
            }
        }
    }

    /**
     * ✅ Fetch categories from API - NEW FUNCTION
     */
    private suspend fun fetchCategoriesFromAPI(token: String): CategoryResponse =
        withContext(Dispatchers.IO) {
            val url = URL(CATEGORY_API_URL)
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

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(InputStreamReader(connection.inputStream))
                        .use { it.readText() }
                    parseCategoriesResponse(response)
                } else {
                    val errorMsg = try {
                        BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                    } catch (e: Exception) {
                        "HTTP Error: $responseCode"
                    }
                    throw Exception(errorMsg)
                }
            } finally {
                connection.disconnect()
            }
        }

    /**
     * ✅ Parse categories response - NEW FUNCTION
     */
    private fun parseCategoriesResponse(jsonString: String): CategoryResponse {
        val jsonObject = JSONObject(jsonString)

        val success = jsonObject.optBoolean("success", false)
        val count = jsonObject.optInt("count", 0)

        val categories = mutableListOf<String>()
        val categoriesArray = jsonObject.optJSONArray("categories")

        if (categoriesArray != null) {
            for (i in 0 until categoriesArray.length()) {
                categories.add(categoriesArray.getString(i))
            }
        }

        return CategoryResponse(success, count, categories)
    }

    /**
     * ✅ Update fields based on selected classification
     */
    private fun updateFieldsBasedOnClassification() {
        val classification = binding.actvClassification.text.toString()

        when (classification) {
            "FEEDER NAME" -> {
                binding.tilNewFeederName.visibility = View.VISIBLE
                binding.tilNewFeederCategory.visibility = View.GONE
                binding.tilNewStatus.visibility = View.GONE
            }

            "FEEDER CATEGORY" -> {
                binding.tilNewFeederName.visibility = View.GONE
                binding.tilNewFeederCategory.visibility = View.VISIBLE
                binding.tilNewStatus.visibility = View.GONE
            }

            "FEEDER STATUS" -> {
                binding.tilNewFeederName.visibility = View.GONE
                binding.tilNewFeederCategory.visibility = View.GONE
                binding.tilNewStatus.visibility = View.VISIBLE
            }

            "NEW FEEDER ADDITION" -> {
                binding.tilNewFeederName.visibility = View.VISIBLE
                binding.tilNewFeederCategory.visibility = View.VISIBLE
                binding.tilNewStatus.visibility = View.GONE
            }

            else -> {
                binding.tilNewFeederName.visibility = View.GONE
                binding.tilNewFeederCategory.visibility = View.GONE
                binding.tilNewStatus.visibility = View.GONE
            }
        }
    }

    /**
     * ✅ Setup buttons
     */
    private fun setupButtons() {
        binding.btnSubmit.setOnClickListener {
            if (validateInputs()) {
                submitTicket()
            }
        }
    }

    /**
     * ✅ Validate inputs before submission
     */
    private fun validateInputs(): Boolean {
        // Basic validation
        if (binding.etUsername.text.isNullOrBlank()) {
            showError("Username is required")
            return false
        }

        if (binding.etDepartment.text.isNullOrBlank()) {
            showError("Department is required")
            return false
        }

        val email = binding.etEmail.text.toString()
        if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Valid email is required")
            return false
        }

        val mobile = binding.etContact.text.toString()
        // ✅ Fix: Backend regex ^[6-9]\d{9}$ — Indian mobile 6/7/8/9 se shuru hona chahiye
        if (mobile.isBlank() || mobile.length != 10 || !mobile.matches(Regex("^[6-9]\\d{9}$"))) {
            showError("Valid 10-digit mobile number is required (must start with 6, 7, 8 or 9)")
            return false
        }

        if (binding.etProblemStatement.text.isNullOrBlank()) {
            showError("Problem statement is required")
            return false
        }

        // Classification validation
        if (binding.actvClassification.text.isNullOrBlank()) {
            showError("Classification is required")
            return false
        }

        // ✅ Feeder validation (required for station tickets)
        if (binding.actvFeederName.text.isNullOrBlank()) {
            showError("Feeder name is required")
            return false
        }

        // Classification-specific validation
        val classification = binding.actvClassification.text.toString()

        when (classification) {
            "FEEDER NAME" -> {
                if (binding.etNewFeederName.text.isNullOrBlank()) {
                    showError("New feeder name is required")
                    return false
                }
            }

            "FEEDER CATEGORY" -> {
                if (binding.actvNewFeederCategory.text.isNullOrBlank()) {
                    showError("New category is required")
                    return false
                }
            }

            "FEEDER STATUS" -> {
                if (binding.actvNewStatus.text.isNullOrBlank()) {
                    showError("New status is required")
                    return false
                }
            }

            "NEW FEEDER ADDITION" -> {
                if (binding.etNewFeederName.text.isNullOrBlank()) {
                    showError("New feeder name is required")
                    return false
                }
                if (binding.actvNewFeederCategory.text.isNullOrBlank()) {
                    showError("New category is required")
                    return false
                }
            }
        }

        return true
    }

    /**
     * ✅ Submit ticket to API
     */
    private fun submitTicket() {
        // ✅ Double-submit guard
        if (isSubmitting) {
            Log.w(TAG, "⚠️ Submit already in progress, ignoring duplicate tap")
            return
        }

        val token = SessionManager.getToken(requireContext())

        if (token.isEmpty()) {
            showError("Token missing. Please login again.")
            return
        }

        isSubmitting = true
        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.text = "Submitting..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val requestJson = withContext(Dispatchers.Main) { buildTicketRequest() }

                Log.d(TAG, "📤 Submitting ticket: $requestJson")

                val response = createTicketAPI(token, requestJson)

                Log.d(TAG, "📥 Response: success=${response.success}, ticketId=${response.ticketId}, message=${response.message}")

                withContext(Dispatchers.Main) {
                    isSubmitting = false
                    binding.btnSubmit.isEnabled = true
                    binding.btnSubmit.text = "SUBMIT"

                    if (response.success) {
                        showSuccessDialog(
                            response.message ?: "Ticket created successfully",
                            response.ticketId
                        )
                    } else {
                        showError(response.message ?: "Failed to create ticket")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error submitting ticket", e)
                withContext(Dispatchers.Main) {
                    isSubmitting = false
                    binding.btnSubmit.isEnabled = true
                    binding.btnSubmit.text = "SUBMIT"
                    showError("Network error: ${e.message}")
                }
            }
        }
    }

    /**
     * ✅ Build ticket request JSON
     */
    private fun buildTicketRequest(): JSONObject {
        val json = JSONObject()

        // Basic fields
        json.put("username", binding.etUsername.text.toString().trim())
        json.put("user_department", binding.etDepartment.text.toString().trim())
        json.put("email_id", binding.etEmail.text.toString().trim())
        json.put("mobile_number", binding.etContact.text.toString().trim())
        json.put("problem_statement", binding.etProblemStatement.text.toString().trim())
        json.put("start_datetime", binding.etStartDateTime.text.toString().trim())
        json.put("ticket_status", "OPEN")
        json.put("ticket_classification", binding.actvClassification.text.toString())

        // ✅ Feeder fields from AutoCompleteTextView selection
        val feederName = selectedFeederData?.name ?: binding.actvFeederName.text.toString().trim()
        val feederCategory = binding.actvFeederCategory.text.toString().trim()

        json.put("feeder_name", feederName)
        json.put("feeder_category", feederCategory)

        // ✅ Add feeder code if available
        if (selectedFeederData != null && !selectedFeederData!!.code.isNullOrBlank()) {
            json.put("feeder_code", selectedFeederData!!.code)
            Log.d(TAG, "📦 Feeder Code: ${selectedFeederData!!.code}")
        }

        // ✅ Add classification-specific fields
        val classification = binding.actvClassification.text.toString().uppercase()

        when (classification) {
            "FEEDER NAME" -> {
                json.put("old_feeder_name", feederName)
                json.put("new_feeder_name", binding.etNewFeederName.text.toString().trim())
                Log.d(TAG, "📦 FEEDER NAME: $feederName → ${binding.etNewFeederName.text}")
            }

            "FEEDER CATEGORY" -> {
                json.put("old_feeder_category", feederCategory)
                json.put("new_feeder_category", binding.actvNewFeederCategory.text.toString())
                Log.d(TAG, "📦 FEEDER CATEGORY: $feederCategory → ${binding.actvNewFeederCategory.text}")
            }

            "FEEDER STATUS" -> {
                json.put("old_status", "ACTIVE")
                json.put("new_status", binding.actvNewStatus.text.toString())
                Log.d(TAG, "📦 FEEDER STATUS: ACTIVE → ${binding.actvNewStatus.text}")
            }

            "NEW FEEDER ADDITION" -> {
                json.put("new_feeder_name", binding.etNewFeederName.text.toString().trim())
                json.put("new_feeder_category", binding.actvNewFeederCategory.text.toString())
                Log.d(TAG, "📦 NEW FEEDER ADDITION")
            }
        }

        return json
    }

    /**
     * ✅ Fetch feeders from API
     */
    private suspend fun fetchFeedersFromAPI(token: String): FeederListResponse =
        withContext(Dispatchers.IO) {
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

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                    parseFeedersResponse(response)
                } else {
                    val errorMsg = try {
                        BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                    } catch (e: Exception) {
                        "HTTP Error: $responseCode"
                    }
                    throw Exception(errorMsg)
                }
            } finally {
                connection.disconnect()
            }
        }

    /**
     * ✅ Parse feeder list response
     */
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

                val feederCode = if (item.isNull("FEEDER_CODE")) {
                    null
                } else {
                    val code = item.optString("FEEDER_CODE", "")
                    if (code.isEmpty()) null else code
                }

                feeders.add(
                    FeederItem(
                        FEEDER_NAME = item.optString("FEEDER_NAME", ""),
                        FEEDER_CODE = feederCode,
                        FEEDER_CATEGORY = item.optString("FEEDER_CATEGORY", ""),
                        STATION_NAME = item.optString("STATION_NAME", "")
                    )
                )
            }
        }

        return FeederListResponse(success, username, escom, count, feeders)
    }

    /**
     * ✅ Create ticket API call
     */
    private suspend fun createTicketAPI(token: String, requestJson: JSONObject): TicketResponse =
        withContext(Dispatchers.IO) {
            val url = URL(TICKET_API_URL)
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
                    val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                    parseTicketResponse(response)
                } else {
                    // ✅ Fix: Error stream se JSON parse karke clean message nikalo
                    val errorStream = connection.errorStream
                    val errorMsg = if (errorStream != null) {
                        try {
                            val errorJson = BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
                            val errorObj = JSONObject(errorJson)
                            // ✅ Backend se "message" field nikalo — raw JSON mat dikhao
                            val msg = errorObj.optString("message", "")
                            // ✅ Agar validation errors hain toh unhe bhi readable format mein dikhao
                            val errorsArray = errorObj.optJSONArray("errors")
                            if (errorsArray != null && errorsArray.length() > 0) {
                                val errorDetails = StringBuilder(msg).append("\n")
                                for (i in 0 until errorsArray.length()) {
                                    val errObj = errorsArray.getJSONObject(i)
                                    errorDetails.append("• ").append(errObj.optString("message", "")).append("\n")
                                }
                                errorDetails.toString().trim()
                            } else {
                                msg.ifEmpty { "Server error: $responseCode" }
                            }
                        } catch (e: Exception) {
                            "Server error: $responseCode"
                        }
                    } else {
                        "Server error: $responseCode"
                    }
                    TicketResponse(false, errorMsg, null)
                }
            } finally {
                connection.disconnect()
            }
        }

    /**
     * ✅ Parse ticket creation response
     * Backend returns: { success, message, ticket_id } (snake_case)
     */
    private fun parseTicketResponse(jsonString: String): TicketResponse {
        val jsonObject = JSONObject(jsonString)
        return TicketResponse(
            success = jsonObject.optBoolean("success", false),
            message = jsonObject.optString("message", "Unknown error"),
            // ✅ Fix: Backend sends "ticket_id" (snake_case), not "ticketId" (camelCase)
            ticketId = if (jsonObject.isNull("ticket_id")) null
            else jsonObject.optString("ticket_id", null)
        )
    }

    /**
     * ✅ Show success dialog
     */
    private fun showSuccessDialog(message: String, ticketId: String?) {
        // ✅ Fix: ticket_id properly dikhao — N/A nahi aayega ab
        val ticketDisplay = if (!ticketId.isNullOrBlank() && ticketId != "null") ticketId else null
        AlertDialog.Builder(requireContext())
            .setTitle("✅ Ticket Created Successfully")
            .setMessage(
                "$message\n\n" +
                        "🎫 Ticket ID: ${ticketDisplay ?: "Generated by server"}\n\n" +
                        "Ticket has been submitted for DCC approval."
            )
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                findNavController().popBackStack()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * ✅ Show error message
     */
    private fun showError(message: String) {
        Toast.makeText(requireContext(), "❌ $message", Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Data classes
     */
    data class TicketResponse(
        val success: Boolean,
        val message: String?,
        val ticketId: String?
    )

    data class CategoryResponse(
        val success: Boolean,
        val count: Int,
        val categories: List<String>
    )
}