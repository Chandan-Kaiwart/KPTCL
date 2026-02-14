//package com.apc.kptcl.home.users.ticket
//
//import android.os.Bundle
//import android.util.Log
//import android.util.Patterns
//import android.view.LayoutInflater
//import android.view.View
//import android.view.ViewGroup
//import android.widget.ArrayAdapter
//import android.widget.Toast
//import androidx.appcompat.app.AlertDialog
//import androidx.fragment.app.Fragment
//import androidx.lifecycle.lifecycleScope
//import androidx.navigation.fragment.findNavController
//import com.apc.kptcl.R
//import com.apc.kptcl.databinding.FragmentCreateTicketBinding
//import com.apc.kptcl.home.users.ticket.dataclass.FeederData
//import com.apc.kptcl.home.users.ticket.dataclass.FeederItem
//import com.apc.kptcl.home.users.ticket.dataclass.FeederListResponse
//import com.apc.kptcl.utils.ApiErrorHandler
//import com.apc.kptcl.utils.SessionManager
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import org.json.JSONObject
//import java.io.BufferedReader
//import java.io.InputStreamReader
//import java.io.OutputStreamWriter
//import java.net.HttpURLConnection
//import java.net.URL
//import java.text.SimpleDateFormat
//import java.util.*
//
///**
// * ✅ CreateTicketFragment - Dual Flow Implementation with Feeder Selection
// *
// * Flow 1: Direct Entry (General Ticket)
// * - User can manually select feeder from loaded list
// * - Only "GENERAL TICKET" classification
// *
// * Flow 2: From Feeder Confirmation (Feeder-Specific Ticket)
// * - Feeder data pre-filled from bundle
// * - Feeder fields are NON-EDITABLE
// * - All classifications available
// */
//class CreateTicketFragment : Fragment() {
//
//    private var _binding: FragmentCreateTicketBinding? = null
//    private val binding get() = _binding!!
//
//    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
//
//    // ✅ Dual Flow Variables
//    private var isFromFeederConfirmation = false
//
//    // Prefilled data (from FeederConfirmation)
//    private var prefilledFeederName: String? = null
//    private var prefilledFeederCode: String? = null  // ✅ Can be null
//    private var prefilledFeederCategory: String? = null
//
//    // Selected data (from Direct Entry)
//    private var selectedFeederData: FeederData? = null
//    private var allFeeders = listOf<FeederData>()
//    // Line 63 - CORRECT (class level pe)
//    private var feederCategories = listOf<String>()  // ✅ ADD HERE
//    companion object {
//        private const val TAG = "CreateTicket"
//        private const val TICKET_API_URL = "http://62.72.59.119:8008/api/ticket/create"
//        private const val FEEDER_LIST_URL = "http://62.72.59.119:8008/api/feeder/list"
//
//        private const val CATEGORY_API_URL = "http://62.72.59.119:8008/api/feeder/categories"
//
//        private const val TIMEOUT = 15000
//    }
//
//    override fun onCreateView(
//        inflater: LayoutInflater,
//        container: ViewGroup?,
//        savedInstanceState: Bundle?
//    ): View {
//        _binding = FragmentCreateTicketBinding.inflate(inflater, container, false)
//        return binding.root
//    }
//
//    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//        super.onViewCreated(view, savedInstanceState)
//
//        // ✅ Detect entry point
//        checkEntryPoint()
//
//        // ✅ Setup based on entry mode
//        if (isFromFeederConfirmation) {
//            setupPrefilledMode()
//        } else {
//            setupNormalMode()
//        }
//
//        setupCommonFields()
//        setupButtons()
//    }
//
//    /**
//     * ✅ Detect if coming from FeederConfirmation or Direct Entry
//     */
//    private fun checkEntryPoint() {
//        arguments?.let { bundle ->
//            prefilledFeederName = bundle.getString("feederName")
//            prefilledFeederCode = bundle.getString("feederCode")
//            prefilledFeederCategory = bundle.getString("feederCategory")
//
//            // ✅ If feederName exists in bundle, it's from FeederConfirmation
//            isFromFeederConfirmation = !prefilledFeederName.isNullOrEmpty()
//
//            Log.d(TAG, "📍 Entry: ${if (isFromFeederConfirmation) "FEEDER CONFIRMATION" else "DIRECT"}")
//            if (isFromFeederConfirmation) {
//                Log.d(TAG, "📋 Feeder: $prefilledFeederName")
//                Log.d(TAG, "📋 Code: ${prefilledFeederCode ?: "NO CODE"}")
//                Log.d(TAG, "📋 Category: $prefilledFeederCategory")
//            }
//        }
//    }
//
//    /**
//     * ✅ PREFILLED MODE: From Feeder Confirmation
//     */
//    private fun setupPrefilledMode() {
//        Log.d(TAG, "🔒 Setting up PREFILLED mode")
//
//        // ✅ Pre-fill feeder fields
//        binding.etFeederName.setText(prefilledFeederName)
//        binding.etFeederCategory.setText(prefilledFeederCategory)
//
//        // ✅ Fields are already non-editable in XML (enabled="false")
//        // Just add visual indication
//        binding.etFeederName.alpha = 0.7f
//        binding.etFeederCategory.alpha = 0.7f
//
//        // ✅ Show ALL classifications for feeder-specific tickets
//        val classifications = arrayOf(
//            "FEEDER NAME",
//            "FEEDER CATEGORY",
//            "FEEDER STATUS",
//            "NEW FEEDER ADDITION"
//        )
//
//        binding.actvClassification.setAdapter(
//            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, classifications)
//        )
//
//        Toast.makeText(
//            requireContext(),
//            "✅ Feeder locked: $prefilledFeederName\nSelect ticket classification",
//            Toast.LENGTH_LONG
//        ).show()
//    }
//
//    /**
//     * ✅ NORMAL MODE: Direct Entry with Feeder Selection Dialog
//     */
//    private fun setupNormalMode() {
//        Log.d(TAG, "🔓 Setting up NORMAL mode with Feeder Selection")
//
//        // ✅ Enable feeder name field but make it clickable to show selection dialog
//        binding.etFeederName.isEnabled = true
//        binding.etFeederName.isFocusable = false
//        binding.etFeederName.isClickable = true
//
//        // ✅ Only GENERAL TICKET classification
//        val classifications = arrayOf("GENERAL TICKET")
//
//        binding.actvClassification.setAdapter(
//            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, classifications)
//        )
//
//        // ✅ Load feeders and setup selection dialog
//        loadFeederList()
//    }
//
//    /**
//     * ✅ Load feeders from API (SAME as FeederHourlyEntryFragment)
//     */
//    private fun loadFeederList() {
//        val token = SessionManager.getToken(requireContext())
//
//        if (token.isEmpty()) {
//            showError("Token missing. Please login again.")
//            return
//        }
//
//        Log.d(TAG, "📄 Fetching feeders from API...")
//        binding.etFeederName.hint = "Loading feeders..."
//
//        viewLifecycleOwner.lifecycleScope.launch {
//            try {
//                val response = withContext(Dispatchers.IO) {
//                    fetchFeedersFromAPI(token)
//                }
//
//                if (response.success) {
//                    allFeeders = response.data.map { item ->
//                        FeederData(
//                            name = item.FEEDER_NAME,
//                            code = item.FEEDER_CODE,  // ✅ Can be null
//                            category = item.FEEDER_CATEGORY,
//                            confirmed = false
//                        )
//                    }
//
//                    Log.d(TAG, "✅ Loaded ${allFeeders.size} feeders")
//
//                    // ✅ Setup click listener to show selection dialog
//                    setupFeederSelectionDialog()
//
//                    binding.etFeederName.hint = "Click to select feeder..."
//                    Toast.makeText(requireContext(), "Loaded ${allFeeders.size} feeders", Toast.LENGTH_SHORT).show()
//
//                } else {
//                    showError("Failed to load feeders")
//                }
//
//            } catch (e: Exception) {
//                Log.e(TAG, "❌ Error loading feeders", e)
//                showError(ApiErrorHandler.handle(e))
//            }
//        }
//    }
//
//    /**
//     * ✅ Setup feeder selection dialog (cleaner approach than AutoCompleteTextView)
//     */
//    private fun setupFeederSelectionDialog() {
//        binding.etFeederName.setOnClickListener {
//            if (allFeeders.isEmpty()) {
//                Toast.makeText(requireContext(), "No feeders available", Toast.LENGTH_SHORT).show()
//                return@setOnClickListener
//            }
//
//            // ✅ Create display strings with NAME and CODE (if available)
//            val feederDisplayList = allFeeders.map { feeder ->
//                if (feeder.code.isNullOrBlank()) {
//                    feeder.name
//                } else {
//                    "${feeder.name} (${feeder.code})"
//                }
//            }.toTypedArray()
//
//            // ✅ Show selection dialog
//            AlertDialog.Builder(requireContext())
//                .setTitle("Select Feeder")
//                .setItems(feederDisplayList) { dialog, which ->
//                    selectedFeederData = allFeeders[which]
//
//                    // ✅ Display selected feeder name (with code if available)
//                    val displayText = if (selectedFeederData?.code.isNullOrBlank()) {
//                        selectedFeederData?.name
//                    } else {
//                        "${selectedFeederData?.name} (${selectedFeederData?.code})"
//                    }
//                    binding.etFeederName.setText(displayText)
//
//                    // ✅ Auto-fill feeder category
//                    binding.etFeederCategory.setText(selectedFeederData?.category ?: "")
//
//                    Log.d(TAG, "✅ Selected: ${selectedFeederData?.name} | Code: ${selectedFeederData?.code ?: "NONE"} | Category: ${selectedFeederData?.category}")
//
//                    Toast.makeText(
//                        requireContext(),
//                        "Selected: ${selectedFeederData?.name}",
//                        Toast.LENGTH_SHORT
//                    ).show()
//
//                    dialog.dismiss()
//                }
//                .setNegativeButton("Cancel") { dialog, _ ->
//                    dialog.dismiss()
//                }
//                .show()
//        }
//    }
//    private fun loadFeederCategories() {
//        val token = SessionManager.getToken(requireContext())
//        if (token.isEmpty()) {
//            setupFallbackCategories()
//            return
//        }
//
//        viewLifecycleOwner.lifecycleScope.launch {
//            try {
//                val response = withContext(Dispatchers.IO) {
//                    fetchCategoriesFromAPI(token)
//                }
//
//                if (response.success && response.categories.isNotEmpty()) {
//                    feederCategories = response.categories
//                    binding.actvNewFeederCategory.setAdapter(
//                        ArrayAdapter(requireContext(),
//                            android.R.layout.simple_dropdown_item_1line,
//                            feederCategories)
//                    )
//                    Log.d(TAG, "✅ Loaded ${feederCategories.size} categories")
//                } else {
//                    setupFallbackCategories()
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "❌ Category API failed: ${e.message}")
//                setupFallbackCategories()
//            }
//        }
//    }
//
//// 5️⃣ Add this function for API call:
//    /**
//     * ✅ Fetch categories from API
//     */
//    private suspend fun fetchCategoriesFromAPI(token: String): CategoryResponse =
//        withContext(Dispatchers.IO) {
//            val url = URL(CATEGORY_API_URL)
//            val connection = url.openConnection() as HttpURLConnection
//
//            try {
//                connection.apply {
//                    requestMethod = "GET"
//                    connectTimeout = TIMEOUT
//                    readTimeout = TIMEOUT
//                    setRequestProperty("Accept", "application/json")
//                    setRequestProperty("Authorization", "Bearer $token")
//                }
//
//                val responseCode = connection.responseCode
//
//                if (responseCode == HttpURLConnection.HTTP_OK) {
//                    val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
//                    parseCategoryResponse(response)
//                } else {
//                    // ✅ FIXED: Was "throw Exception("HTTP Error: $responseCode")"
//                    // Category load failing → silently fall back to local list (handled in caller)
//                    throw Exception(ApiErrorHandler.fromErrorStream(connection.errorStream, responseCode))
//                }
//            } finally {
//                connection.disconnect()
//            }
//        }
//
//// 6️⃣ Add this function to parse response:
//    /**
//     * ✅ Parse category API response
//     */
//    private fun parseCategoryResponse(jsonString: String): CategoryResponse {
//        val jsonObject = JSONObject(jsonString)
//
//        val success = jsonObject.optBoolean("success", false)
//        val count = jsonObject.optInt("count", 0)
//        val categories = mutableListOf<String>()
//
//        val categoriesArray = jsonObject.optJSONArray("categories")
//        if (categoriesArray != null) {
//            for (i in 0 until categoriesArray.length()) {
//                categories.add(categoriesArray.getString(i))
//            }
//        }
//
//        return CategoryResponse(success, count, categories)
//    }
//
//// 7️⃣ Add this fallback function (in case API fails):
//    /**
//     * ✅ Fallback to hardcoded categories if API fails
//     */
//    private fun setupFallbackCategories() {
//        // Default categories (fallback)
//        feederCategories = listOf(
//            "URBAN",
//            "RURAL",
//            "INDUSTRIAL",
//            "COMMERCIAL",
//            "AGRICULTURAL",
//            "IP",
//            "NJY"
//        )
//
//        val adapter = ArrayAdapter(
//            requireContext(),
//            android.R.layout.simple_dropdown_item_1line,
//            feederCategories
//        )
//        binding.actvNewFeederCategory.setAdapter(adapter)
//
//        Log.d(TAG, "⚠️ Using fallback categories: $feederCategories")
//    }
//    /**
//     * ✅ Setup common fields (shared between both modes)
//     */
//    private fun setupCommonFields() {
//
//        loadFeederCategories()
//
//
//        // Status dropdown
//        val statusOptions = arrayOf("ACTIVE", "INACTIVE")
//        binding.actvNewStatus.setAdapter(
//            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, statusOptions)
//        )
//        // Auto-fill username
//        binding.etUsername.setText(SessionManager.getUsername(requireContext()))
//
//        // Set current date-time
//        binding.etStartDateTime.setText(dateTimeFormat.format(Date()))
//
//        // Set default ticket status
//        binding.etTicketStatus.setText("OPEN")
//
//        // ✅ Setup classification-dependent fields
//        binding.actvClassification.setOnItemClickListener { _, _, _, _ ->
//            handleClassificationChange()
//        }
//    }
//
//    /**
//     * ✅ Handle classification changes to show/hide fields
//     */
//    private fun handleClassificationChange() {
//        val classification = binding.actvClassification.text.toString()
//
//        // Reset visibility
//        binding.tilNewFeederName.visibility = View.GONE
//        binding.tilNewFeederCategory.visibility = View.GONE
//        binding.tilNewStatus.visibility = View.GONE
//
//        when (classification.uppercase()) {
//            "FEEDER NAME" -> {
//                binding.tilNewFeederName.visibility = View.VISIBLE
//            }
//
//            "FEEDER CODE" -> {
//                // No extra fields - DCC assigns code
//            }
//
//            "FEEDER CATEGORY" -> {
//                binding.tilNewFeederCategory.visibility = View.VISIBLE
//
//            }
//
//            "FEEDER STATUS" -> {
//                binding.tilNewStatus.visibility = View.VISIBLE
//                val statusOptions = arrayOf("INACTIVE")
//                binding.actvNewStatus.setAdapter(
//                    ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, statusOptions)
//                )
//            }
//
//            "NEW FEEDER ADDITION" -> {
//                binding.tilNewFeederName.visibility = View.VISIBLE
//                binding.tilNewFeederCategory.visibility = View.VISIBLE
//
//            }
//
//            "GENERAL TICKET" -> {
//                // No extra fields
//            }
//        }
//    }
//
//    /**
//     * ✅ Setup buttons
//     */
//    private fun setupButtons() {
//        binding.btnSubmit.setOnClickListener {
//            if (validateInputs()) {
//                submitTicket()
//            }
//        }
//    }
//
//    /**
//     * ✅ Validate user inputs
//     */
//    private fun validateInputs(): Boolean {
//        val department = binding.etDepartment.text.toString().trim()
//        val email = binding.etEmail.text.toString().trim()  // ✅ Fixed: etEmail not etEmailId
//        val phone = binding.etContact.text.toString().trim()  // ✅ Fixed: etContact not etPhone
//        val feederName = binding.etFeederName.text.toString().trim()
//        val feederCategory = binding.etFeederCategory.text.toString().trim()
//        val classification = binding.actvClassification.text.toString()
//        val problem = binding.etProblemStatement.text.toString().trim()
//
//        if (department.isEmpty()) {
//            showError("Please enter department")
//            return false
//        }
//
//        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
//            showError("Please enter valid email")
//            return false
//        }
//
//        if (phone.isEmpty() || phone.length != 10) {
//            showError("Please enter valid 10-digit phone number")
//            return false
//        }
//
//        if (feederName.isEmpty()) {
//            showError("Please select feeder name")
//            return false
//        }
//
//        if (feederCategory.isEmpty()) {
//            showError("Please enter feeder category")
//            return false
//        }
//
//        if (classification.isEmpty()) {
//            showError("Please select classification")
//            return false
//        }
//
//        // ✅ Classification-specific validation
//        when (classification.uppercase()) {
//            "FEEDER NAME" -> {
//                if (binding.etNewFeederName.text.toString().trim().isEmpty()) {
//                    showError("Please enter new feeder name")
//                    return false
//                }
//            }
//
//            "FEEDER CATEGORY" -> {
//                if (binding.actvNewFeederCategory.text.toString().isEmpty()) {
//                    showError("Please select new feeder category")
//                    return false
//                }
//            }
//
//            "FEEDER STATUS" -> {
//                if (binding.actvNewStatus.text.toString().isEmpty()) {
//                    showError("Please select new status")
//                    return false
//                }
//            }
//
//            "NEW FEEDER ADDITION" -> {
//                if (binding.etNewFeederName.text.toString().trim().isEmpty()) {
//                    showError("Please enter new feeder name")
//                    return false
//                }
//                if (binding.actvNewFeederCategory.text.toString().isEmpty()) {
//                    showError("Please select feeder category")
//                    return false
//                }
//            }
//        }
//
//        if (problem.isEmpty()) {
//            showError("Please describe the problem")
//            return false
//        }
//
//        return true
//    }
//
//    /**
//     * ✅ Submit ticket
//     */
//    private fun submitTicket() {
//        val token = SessionManager.getToken(requireContext())
//        if (token.isEmpty()) {
//            showError("Session expired. Please login again.")
//            return
//        }
//
//        binding.btnSubmit.isEnabled = false
//        binding.btnSubmit.text = "Submitting..."
//
//        lifecycleScope.launch {
//            try {
//                val requestJson = buildTicketRequest()
//                Log.d(TAG, "📤 Request: $requestJson")
//
//                val response = createTicketAPI(token, requestJson)
//
//                withContext(Dispatchers.Main) {
//                    if (response.success) {
//                        showSuccessDialog(response.message ?: "Ticket created", response.ticketId)
//                    } else {
//                        showError(response.message ?: "Failed to create ticket")
//                        binding.btnSubmit.isEnabled = true
//                        binding.btnSubmit.text = "SUBMIT"
//                    }
//                }
//
//            } catch (e: Exception) {
//                Log.e(TAG, "❌ Error submitting ticket", e)
//                withContext(Dispatchers.Main) {
//                    showError(ApiErrorHandler.handle(e))
//                    binding.btnSubmit.isEnabled = true
//                    binding.btnSubmit.text = "SUBMIT"
//                }
//            }
//        }
//    }
//
//    /**
//     * ✅ Build ticket request JSON with feeder code
//     */
//    private fun buildTicketRequest(): JSONObject {
//        val json = JSONObject()
//
//        // Basic fields - using snake_case as expected by API
//        json.put("username", binding.etUsername.text.toString().trim())
//        json.put("user_department", binding.etDepartment.text.toString().trim())
//        json.put("email_id", binding.etEmail.text.toString().trim())  // ✅ Fixed: snake_case
//        json.put("mobile_number", binding.etContact.text.toString().trim())  // ✅ Fixed: snake_case
//        json.put("problem_statement", binding.etProblemStatement.text.toString().trim())  // ✅ Fixed: snake_case
//        json.put("start_datetime", binding.etStartDateTime.text.toString().trim())  // ✅ Fixed: snake_case
//        json.put("ticket_status", "OPEN")  // ✅ Fixed: snake_case
//        json.put("ticket_classification", binding.actvClassification.text.toString())  // ✅ Fixed: CRITICAL field name
//
//        // ✅ Feeder fields with code - using snake_case
//        if (isFromFeederConfirmation) {
//            // From FeederConfirmation - use prefilled data
//            json.put("feeder_name", prefilledFeederName)
//            if (!prefilledFeederCode.isNullOrBlank()) {
//                json.put("feeder_code", prefilledFeederCode)
//                Log.d(TAG, "📦 Prefilled Feeder Code: $prefilledFeederCode")
//            } else {
//                Log.d(TAG, "📦 No feeder code available (prefilled)")
//            }
//            json.put("feeder_category", prefilledFeederCategory)
//
//        } else {
//            // Normal mode - use selected feeder
//            val enteredFeederCategory = binding.etFeederCategory.text.toString().trim()
//
//            // ✅ Use only the feeder name (remove code from display text)
//            val feederName = if (selectedFeederData != null) {
//                selectedFeederData!!.name
//            } else {
//                binding.etFeederName.text.toString().trim()
//            }
//
//            json.put("feeder_name", feederName)
//            json.put("feeder_category", enteredFeederCategory)
//
//            // ✅ Add feeder code if available from selection
//            if (selectedFeederData != null && !selectedFeederData!!.code.isNullOrBlank()) {
//                json.put("feeder_code", selectedFeederData!!.code)
//                Log.d(TAG, "📦 Selected Feeder Code: ${selectedFeederData!!.code}")
//            } else {
//                Log.d(TAG, "📦 No feeder code available (selected)")
//            }
//
//            Log.d(TAG, "📦 Feeder: $feederName | Category: $enteredFeederCategory")
//        }
//
//        // ✅ Add classification-specific fields - using snake_case
//        val classification = binding.actvClassification.text.toString().uppercase()
//
//        when (classification) {
//            "FEEDER CODE" -> {
//                // ✅ For FEEDER CODE, include old code if available
//                if (isFromFeederConfirmation && !prefilledFeederCode.isNullOrBlank()) {
//                    json.put("old_feeder_code", prefilledFeederCode)
//                }
//                // No new_feeder_code - DCC assigns it
//                Log.d(TAG, "📦 FEEDER CODE request - DCC will assign new code")
//            }
//
//            "FEEDER NAME" -> {
//                val oldName = if (isFromFeederConfirmation) {
//                    prefilledFeederName
//                } else {
//                    selectedFeederData?.name ?: binding.etFeederName.text.toString().trim()
//                }
//                json.put("old_feeder_name", oldName)
//                json.put("new_feeder_name", binding.etNewFeederName.text.toString().trim())
//                Log.d(TAG, "📦 OLD NAME: $oldName → NEW NAME: ${binding.etNewFeederName.text}")
//            }
//
//            "FEEDER CATEGORY" -> {
//                val oldCategory = if (isFromFeederConfirmation) {
//                    prefilledFeederCategory
//                } else {
//                    binding.etFeederCategory.text.toString().trim()
//                }
//                json.put("old_feeder_category", oldCategory)
//                json.put("new_feeder_category", binding.actvNewFeederCategory.text.toString())
//                Log.d(TAG, "📦 OLD CATEGORY: $oldCategory → NEW CATEGORY: ${binding.actvNewFeederCategory.text}")
//            }
//
//            "FEEDER STATUS" -> {
//                json.put("old_status", "ACTIVE")
//                json.put("new_status", binding.actvNewStatus.text.toString())  // "INACTIVE"
//                Log.d(TAG, "📦 STATUS: ACTIVE → INACTIVE")
//            }
//
//            "NEW FEEDER ADDITION" -> {
//                json.put("new_feeder_name", binding.etNewFeederName.text.toString().trim())
//                json.put("new_feeder_category", binding.actvNewFeederCategory.text.toString())
//                // No new_feeder_code - DCC assigns it
//                Log.d(TAG, "📦 NEW FEEDER: ${binding.etNewFeederName.text} | Category: ${binding.actvNewFeederCategory.text}")
//                Log.d(TAG, "📦 Code will be assigned by DCC")
//            }
//
//            "GENERAL TICKET" -> {
//                Log.d(TAG, "📦 GENERAL TICKET - no classification-specific fields")
//            }
//        }
//
//        return json
//    }
//
//    /**
//     * ✅ Fetch feeders from API (SAME as FeederHourlyEntryFragment)
//     */
//    private suspend fun fetchFeedersFromAPI(token: String): FeederListResponse =
//        withContext(Dispatchers.IO) {
//            val url = URL(FEEDER_LIST_URL)
//            val connection = url.openConnection() as HttpURLConnection
//
//            try {
//                connection.apply {
//                    requestMethod = "GET"
//                    connectTimeout = TIMEOUT
//                    readTimeout = TIMEOUT
//                    setRequestProperty("Accept", "application/json")
//                    setRequestProperty("Authorization", "Bearer $token")
//                }
//
//                val responseCode = connection.responseCode
//
//                if (responseCode == HttpURLConnection.HTTP_OK) {
//                    val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
//                    parseFeedersResponse(response)
//                } else {
//                    // ✅ FIXED: Was "throw Exception("HTTP Error: $responseCode")"
//                    val errorMsg = ApiErrorHandler.fromErrorStream(connection.errorStream, responseCode)
//                    throw Exception(errorMsg)
//                }
//            } finally {
//                connection.disconnect()
//            }
//        }
//
//    /**
//     * ✅ Parse feeder list response (SAME as FeederHourlyEntryFragment)
//     */
//    private fun parseFeedersResponse(jsonString: String): FeederListResponse {
//        val jsonObject = JSONObject(jsonString)
//
//        val success = jsonObject.optBoolean("success", false)
//        val username = jsonObject.optString("username", null)
//        val escom = jsonObject.optString("escom", null)
//        val count = jsonObject.optInt("count", 0)
//
//        val feeders = mutableListOf<FeederItem>()
//        val dataArray = jsonObject.optJSONArray("data")
//
//        if (dataArray != null) {
//            for (i in 0 until dataArray.length()) {
//                val item = dataArray.getJSONObject(i)
//
//                // ✅ Handle null FEEDER_CODE properly (SAME as HourlyEntry)
//                val feederCode = if (item.isNull("FEEDER_CODE")) {
//                    null
//                } else {
//                    val code = item.optString("FEEDER_CODE", "")
//                    if (code.isEmpty()) null else code
//                }
//
//                feeders.add(
//                    FeederItem(
//                        FEEDER_NAME = item.optString("FEEDER_NAME", ""),
//                        FEEDER_CODE = feederCode,  // ✅ Nullable
//                        FEEDER_CATEGORY = item.optString("FEEDER_CATEGORY", ""),
//                        STATION_NAME = item.optString("STATION_NAME", "")
//                    )
//                )
//            }
//        }
//
//        return FeederListResponse(success, username, escom, count, feeders)
//    }
//
//    /**
//     * ✅ Create ticket API call
//     */
//    private suspend fun createTicketAPI(token: String, requestJson: JSONObject): TicketResponse =
//        withContext(Dispatchers.IO) {
//            val url = URL(TICKET_API_URL)
//            val connection = url.openConnection() as HttpURLConnection
//
//            try {
//                connection.apply {
//                    requestMethod = "POST"
//                    connectTimeout = TIMEOUT
//                    readTimeout = TIMEOUT
//                    setRequestProperty("Content-Type", "application/json")
//                    setRequestProperty("Accept", "application/json")
//                    setRequestProperty("Authorization", "Bearer $token")
//                    doOutput = true
//                }
//
//                OutputStreamWriter(connection.outputStream).use {
//                    it.write(requestJson.toString())
//                    it.flush()
//                }
//
//                val responseCode = connection.responseCode
//
//                if (responseCode == HttpURLConnection.HTTP_OK) {
//                    val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
//                    parseTicketResponse(response)
//                } else {
//                    // ✅ FIXED: Was TicketResponse(false, "Server error: $responseCode", null)
//                    val errorMsg = ApiErrorHandler.fromErrorStream(connection.errorStream, responseCode)
//                    TicketResponse(false, errorMsg, null)
//                }
//            } finally {
//                connection.disconnect()
//            }
//        }
//    /**
//     * ✅ Parse ticket creation response
//     */
//    private fun parseTicketResponse(jsonString: String): TicketResponse {
//        val jsonObject = JSONObject(jsonString)
//        return TicketResponse(
//            success = jsonObject.optBoolean("success", false),
//            message = jsonObject.optString("message", "Unknown error"),
//            ticketId = jsonObject.optString("ticketId", null)
//        )
//    }
//
//    /**
//     * ✅ Show success dialog
//     */
//    private fun showSuccessDialog(message: String, ticketId: String?) {
//        AlertDialog.Builder(requireContext())
//            .setTitle("✅ Success")
//            .setMessage("$message\n\nTicket ID: ${ticketId ?: "N/A"}")
//            .setPositiveButton("OK") { dialog, _ ->
//                dialog.dismiss()
//                findNavController().popBackStack()
//            }
//            .setCancelable(false)
//            .show()
//    }
//
//    /**
//     * ✅ Show error message
//     */
//    private fun showError(message: String) {
//        Toast.makeText(requireContext(), "❌ $message", Toast.LENGTH_LONG).show()
//    }
//
//    override fun onDestroyView() {
//        super.onDestroyView()
//        _binding = null
//    }
//
//    /**
//     * ✅ Ticket response data class
//     */
//    data class TicketResponse(
//        val success: Boolean,
//        val message: String?,
//        val ticketId: String?
//    )
//    data class CategoryResponse(
//        val success: Boolean,
//        val count: Int,
//        val categories: List<String>
//    )
//}