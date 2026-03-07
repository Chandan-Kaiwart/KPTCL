package com.apc.kptcl.home.users.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import com.apc.kptcl.utils.SessionManager
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ExcelUploadHandler(private val context: Context) {

    companion object {
        private const val TAG = "ExcelUpload"
        private const val HOURLY_SAVE_URL = "http://62.72.59.119:9009/api/feeder/hourly-entry/save"
        private const val CONSUMPTION_SAVE_URL = "http://62.72.59.119:9009/api/feeder/consumption/save"
        private const val TIMEOUT = 15000

        // ✅ Expected headers: User sees "1" to "24"
        private val HOURLY_HEADERS = listOf(
            "DATE", "STATION_NAME", "FEEDER_NAME", "FEEDER_CODE",
            "FEEDER_CATEGORY", "PARAMETER"
        ) + (1..24).map { it.toString() }  // "1", "2", "3" ... "24"

        private val CONSUMPTION_HEADERS = listOf(
            "DATE", "STATION_NAME", "FEEDER_NAME", "FEEDER_CODE",
            "FEEDER_CATEGORY", "REMARK", "TOTAL_CONSUMPTION",
            "SUPPLY_3PH", "SUPPLY_1PH"
        )

        private val PARAMETERS = listOf("IR", "IY", "IB", "MW", "MVAR")

        /**
         * ✅ Validates if a string contains only numeric values (including decimal points)
         * Returns true if valid number, false if contains alphabets
         */
        private fun isValidNumeric(value: String): Boolean {
            if (value.isBlank()) return true // Empty cells are allowed

            // Remove whitespace
            val trimmed = value.trim()

            // Check if it's a valid number (integer or decimal)
            return try {
                trimmed.toDouble()
                true
            } catch (e: NumberFormatException) {
                false
            }
        }
    }

    suspend fun validateAndUpload(uri: Uri, expectedDate: String): Result<UploadResult> {
        return try {
            Log.d(TAG, "📂 Starting Excel validation and upload...")

            val validationResult = validateExcelFile(uri, expectedDate)
            if (validationResult.isFailure) {
                return Result.failure(validationResult.exceptionOrNull()!!)
            }

            val excelData = validationResult.getOrNull()!!
            Log.d(TAG, "✅ Validation passed. Hourly: ${excelData.hourlyData.size}, Consumption: ${excelData.consumptionData.size}")

            // Upload hourly data
            val hourlyCount = uploadHourlyData(excelData.hourlyData)
            Log.d(TAG, "✅ Hourly upload complete: $hourlyCount records")

            // Upload consumption data
            val consumptionCount = uploadConsumptionData(excelData.consumptionData, expectedDate)
            Log.d(TAG, "✅ Consumption upload complete: $consumptionCount records")

            Result.success(UploadResult(hourlyCount, consumptionCount))

        } catch (e: Exception) {
            Log.e(TAG, "❌ Upload error", e)
            Result.failure(e)
        }
    }

    private suspend fun validateExcelFile(uri: Uri, expectedDate: String): Result<ExcelData> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(Exception("Cannot open file"))

            val workbook = WorkbookFactory.create(inputStream)

            // Find sheets by name pattern
            val hourlySheet = workbook.getSheetAt(0)  // FH_YYYY_MM_DD
            val consumptionSheet = workbook.getSheetAt(1)  // FC_YYYY_MM_DD

            // Validate hourly sheet headers
            val hourlyHeaderRow = hourlySheet.getRow(0)
            for (i in HOURLY_HEADERS.indices) {
                val cellValue = getCellValueAsString(hourlyHeaderRow.getCell(i))
                if (cellValue != HOURLY_HEADERS[i]) {
                    return Result.failure(Exception("Wrong format! Column ${i + 1} should be '${HOURLY_HEADERS[i]}', found '$cellValue'. Please re-download the template."))
                }
            }

            // Validate consumption sheet headers
            val consumptionHeaderRow = consumptionSheet.getRow(0)
            for (i in CONSUMPTION_HEADERS.indices) {
                val cellValue = getCellValueAsString(consumptionHeaderRow.getCell(i))
                if (cellValue != CONSUMPTION_HEADERS[i]) {
                    return Result.failure(Exception("Wrong format! Consumption sheet column ${i + 1} should be '${CONSUMPTION_HEADERS[i]}', found '$cellValue'. Please re-download the template."))
                }
            }

            // Extract hourly data
            val hourlyData = mutableListOf<HourlyEntryData>()
            for (rowNum in 1..hourlySheet.lastRowNum) {
                val row = hourlySheet.getRow(rowNum) ?: continue

                val date = getCellValueAsString(row.getCell(0))
                val stationName = getCellValueAsString(row.getCell(1))
                val feederName = getCellValueAsString(row.getCell(2))
                val feederCode = getCellValueAsString(row.getCell(3))
                val feederCategory = getCellValueAsString(row.getCell(4))
                val parameter = getCellValueAsString(row.getCell(5))

                if (date != expectedDate) {
                    return Result.failure(Exception(
                        "📅 Date Mismatch!\n\n" +
                                "You selected date '$expectedDate' in the app,\n" +
                                "but the Excel file contains data for '$date'.\n\n" +
                                "➡️ Please go back and select date '$date' in the app,\n" +
                                "then upload this file again."
                    ))
                }

                // Parse hours 1-24 (Excel) → convert to 00-23 (database)
// Blank cells = "0" (server ko saare 24 hours chahiye previous day ke liye)
                val hours = mutableMapOf<String, String>()
                for (excelHour in 1..24) {
                    val cellIndex = 6 + (excelHour - 1)
                    val cellValue = getCellValueAsString(row.getCell(cellIndex))
                    val dbHour = String.format("%02d", excelHour - 1)

                    if (cellValue.isNotEmpty()) {
                        // Validate: Only numeric values allowed
                        if (!isValidNumeric(cellValue)) {
                            return Result.failure(Exception("❌ Invalid data in Hourly sheet, Row ${rowNum + 1}, Hour $excelHour: '$cellValue' contains alphabets. Only numeric values are allowed."))
                        }
                        hours[dbHour] = cellValue
                        Log.d(TAG, "📊 Mapping Excel Hour $excelHour → DB Hour $dbHour (Value: $cellValue)")
                    } else {
                        // ✅ FIX: Blank cell = "0" instead of skipping
                        hours[dbHour] = "0"
                        Log.d(TAG, "📊 Mapping Excel Hour $excelHour → DB Hour $dbHour (Value: BLANK → 0)")
                    }
                }

// ✅ Ab hamesha add karo (hours kabhi empty nahi hoga)
                if (parameter.isNotEmpty() && feederName.isNotEmpty()) {
                    hourlyData.add(
                        HourlyEntryData(
                            date = date,
                            feederCode = if (feederCode.isEmpty()) null else feederCode,
                            feederName = feederName,
                            feederCategory = feederCategory,
                            parameter = parameter,
                            hours = hours
                        )
                    )
                }
            }

            // Extract consumption data
            val consumptionData = mutableListOf<ConsumptionEntryData>()
            for (rowNum in 1..consumptionSheet.lastRowNum) {
                val row = consumptionSheet.getRow(rowNum) ?: continue

                val date = getCellValueAsString(row.getCell(0))
                val stationName = getCellValueAsString(row.getCell(1))
                val feederName = getCellValueAsString(row.getCell(2))
                val feederCode = getCellValueAsString(row.getCell(3))
                val feederCategory = getCellValueAsString(row.getCell(4))
                val remark = getCellValueAsString(row.getCell(5))
                val totalConsumption = getCellValueAsString(row.getCell(6))
                val supply3phRaw = getCellValueAsString(row.getCell(7))
                val supply1phRaw = getCellValueAsString(row.getCell(8))

                // ✅ Validate: TOTAL_CONSUMPTION must be numeric
                if (totalConsumption.isNotEmpty() && !isValidNumeric(totalConsumption)) {
                    return Result.failure(Exception("❌ Invalid data in Consumption sheet, Row ${rowNum + 1}, TOTAL_CONSUMPTION: '$totalConsumption' contains alphabets. Only numeric values are allowed."))
                }

                // ✅ Convert Excel decimal times to HH:MM format
                val supply3ph = convertExcelTimeToHHMM(supply3phRaw)
                val supply1ph = convertExcelTimeToHHMM(supply1phRaw)

                val timeValidation = validateTotalSupplyTime(supply3ph, supply1ph)
                if (!timeValidation.first) {
                    return Result.failure(
                        Exception(
                            "❌ Invalid data in Consumption sheet, Row ${rowNum + 1}, Feeder: $feederName\n" +
                                    timeValidation.second
                        )
                    )
                }
                if (date != expectedDate) {
                    return Result.failure(Exception(
                        "📅 Date Mismatch!\n\n" +
                                "You selected date '$expectedDate' in the app,\n" +
                                "but the Excel file contains data for '$date'.\n\n" +
                                "➡️ Please go back and select date '$date' in the app,\n" +
                                "then upload this file again."
                    ))
                }

                if (remark.isNotEmpty() || totalConsumption.isNotEmpty()) {
                    consumptionData.add(
                        ConsumptionEntryData(
                            date = date,
                            stationName = stationName,      // ✅ Added
                            feederName = feederName,         // ✅ Added
                            feederCode = feederCode,
                            feederCategory = feederCategory,
                            remark = remark,
                            totalConsumption = totalConsumption.toDoubleOrNull(),
                            supply3ph = supply3ph,           // ✅ Converted to HH:MM
                            supply1ph = supply1ph            // ✅ Converted to HH:MM
                        )
                    )
                }
            }

            workbook.close()
            inputStream.close()

            Log.d(TAG, "✅ Validation complete. Hourly: ${hourlyData.size}, Consumption: ${consumptionData.size}")
            return Result.success(ExcelData(hourlyData, consumptionData))

        } catch (e: Exception) {
            Log.e(TAG, "❌ Validation error", e)
            return Result.failure(e)
        }
    }
    private fun isValidTimeFormat(time: String): Boolean {
        if (!time.matches(Regex("^\\d{2}:\\d{2}$"))) return false

        val parts = time.split(":")
        val hours = parts[0].toIntOrNull() ?: return false
        val minutes = parts[1].toIntOrNull() ?: return false

        return hours in 0..23 && minutes in 0..59
    }

    /**
     * ✅ Convert HH:MM to total minutes (same as ConsumptionEntryFragment)
     */
    private fun parseTimeToMinutes(time: String): Int {
        val parts = time.split(":")
        val hours = parts[0].toIntOrNull() ?: 0
        val minutes = parts[1].toIntOrNull() ?: 0
        return (hours * 60) + minutes
    }

    /**
     * ✅ Validate that total supply time (3PH + 1PH) does not exceed 24 hours
     */
    private fun validateTotalSupplyTime(supply3ph: String, supply1ph: String): Pair<Boolean, String?> {
        // If both empty, no validation needed
        if (supply3ph.isEmpty() && supply1ph.isEmpty()) {
            return Pair(true, null)
        }

        var total3phMinutes = 0
        var total1phMinutes = 0

        // Validate and parse 3PH
        if (supply3ph.isNotEmpty()) {
            if (!isValidTimeFormat(supply3ph)) {
                return Pair(false, "Invalid 3PH format: '$supply3ph'. Expected HH:MM (00:00 to 23:59)")
            }
            total3phMinutes = parseTimeToMinutes(supply3ph)
        }

        // Validate and parse 1PH
        if (supply1ph.isNotEmpty()) {
            if (!isValidTimeFormat(supply1ph)) {
                return Pair(false, "Invalid 1PH format: '$supply1ph'. Expected HH:MM (00:00 to 23:59)")
            }
            total1phMinutes = parseTimeToMinutes(supply1ph)
        }

        // Check total <= 24 hours (1440 minutes)
        val totalMinutes = total3phMinutes + total1phMinutes
        if (totalMinutes > 1440) {
            val total3phHours = total3phMinutes / 60
            val total3phMins = total3phMinutes % 60
            val total1phHours = total1phMinutes / 60
            val total1phMins = total1phMinutes % 60
            val totalHours = totalMinutes / 60
            val totalMins = totalMinutes % 60

            return Pair(
                false,
                "Total supply time exceeds 24 hours!\n" +
                        "3PH: ${String.format("%02d:%02d", total3phHours, total3phMins)} + " +
                        "1PH: ${String.format("%02d:%02d", total1phHours, total1phMins)} = " +
                        "${String.format("%02d:%02d", totalHours, totalMins)} > 24:00"
            )
        }

        return Pair(true, null)
    }

    private fun getCellValueAsString(cell: org.apache.poi.ss.usermodel.Cell?): String {
        if (cell == null) return ""

        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.trim()
            CellType.NUMERIC -> {
                val numValue = cell.numericCellValue
                if (numValue == numValue.toLong().toDouble()) {
                    numValue.toLong().toString()
                } else {
                    numValue.toString()
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    cell.stringCellValue.trim()
                } catch (e: Exception) {
                    cell.numericCellValue.toString()
                }
            }
            else -> ""
        }
    }

    /**
     * ✅ NEW: Convert Excel decimal time to HH:MM format
     * Excel stores time as fraction of day (0.5 = 12:00, 0.020833 = 00:30)
     */
    private fun convertExcelTimeToHHMM(excelTime: String): String {
        if (excelTime.isEmpty()) return ""

        try {
            // If it's already in HH:MM format (contains colon), return as-is
            if (excelTime.contains(":")) {
                return excelTime
            }

            // Try to parse as double (Excel decimal time)
            val decimalValue = excelTime.toDoubleOrNull() ?: return ""

            // Convert decimal to total minutes
            val totalMinutes = (decimalValue * 24 * 60).toInt()

            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60

            // Validate time range
            if (hours < 0 || hours > 24 || minutes < 0 || minutes >= 60) {
                Log.w(TAG, "⚠️ Invalid time value: $excelTime → ${hours}:${minutes}")
                return ""
            }

            // Format as HH:MM
            val formatted = String.format("%02d:%02d", hours, minutes)
            Log.d(TAG, "⏰ Time conversion: $excelTime → $formatted")
            return formatted

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to convert time: $excelTime", e)
            return ""
        }
    }

    private suspend fun uploadHourlyData(hourlyData: List<HourlyEntryData>): Int {
        if (hourlyData.isEmpty()) return 0

        val token = SessionManager.getToken(context)
        if (token.isEmpty()) {
            throw Exception("User not logged in")
        }

        // ✅ FIX: Send ALL feeders in ONE single API call
        // Server handles autofill for all feeders together
        val rowsArray = JSONArray()

        // Group by feeder to build rows per feeder × parameter
        val groupedData = hourlyData.groupBy { "${it.date}_${it.feederName}" }

        for ((_, entries) in groupedData) {
            val firstEntry = entries.first()

            for (parameter in PARAMETERS) {
                val parameterEntries = entries.filter { it.parameter == parameter }
                if (parameterEntries.isNotEmpty()) {
                    val hoursObject = JSONObject()
                    parameterEntries.forEach { entry ->
                        entry.hours.forEach { (hour, value) ->
                            hoursObject.put(hour, value)
                        }
                    }

                    rowsArray.put(JSONObject().apply {
                        put("date", firstEntry.date)
                        put("feeder_code", firstEntry.feederCode)
                        put("feeder_name", firstEntry.feederName)
                        put("feeder_category", firstEntry.feederCategory)
                        put("parameter", parameter)
                        put("hours", hoursObject)
                    })
                }
            }
        }

        Log.d(TAG, "📤 Sending ALL ${rowsArray.length()} rows in ONE API call")
        val requestBody = JSONObject().put("rows", rowsArray)

        val result = submitToHourlyAPI(token, requestBody)
        return if (result) hourlyData.size else throw Exception("Hourly upload failed. Please try again.")
    }

    private suspend fun submitToHourlyAPI(token: String, requestBody: JSONObject): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {

            // ===== ATTEMPT 1 =====
            val result1 = makeHourlyRequest(token, requestBody)
            if (result1.success) return@withContext true

            // ===== ATTEMPT 2: Autofill Retry =====
            // Server ne 400 + allow_autofill=true diya — 2 seconds wait karke retry karo
            // Server autofillPendingUsers map mein entry store karta hai 120s ke liye
            // Same request dobara bhejna = server autofill apply karega
            if (result1.allowAutofill) {
                Log.d(TAG, "🔄 Server requested autofill — waiting 2s then retrying...")
                kotlinx.coroutines.delay(2000)
                val result2 = makeHourlyRequest(token, requestBody)
                if (result2.success) return@withContext true
                Log.e(TAG, "❌ Autofill retry also failed")
                return@withContext false
            }

            Log.e(TAG, "❌ Hourly upload failed: ${result1.errorMessage}")
            throw Exception(result1.errorMessage ?: "Hourly upload failed")
        }
    }

    private data class HourlyRequestResult(
        val success: Boolean,
        val allowAutofill: Boolean = false,
        val errorMessage: String? = null
    )

    private fun makeHourlyRequest(token: String, requestBody: JSONObject): HourlyRequestResult {
        val url = URL(HOURLY_SAVE_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                doOutput = true
            }

            Log.d(TAG, "📤 Hourly Request: ${requestBody.toString(2)}")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }
                Log.d(TAG, "✅ Hourly Response: $response")
                val jsonResponse = JSONObject(response)
                return HourlyRequestResult(success = jsonResponse.optBoolean("success", false))
            } else {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                } catch (e: Exception) { "" }

                Log.e(TAG, "❌ HTTP Error: $responseCode | Body: $errorBody")

                val jsonError = try { JSONObject(errorBody) } catch (e: Exception) { JSONObject() }
                val allowAutofill = jsonError.optBoolean("allow_autofill", false)
                val errorMessage = parseErrorResponse(errorBody)

                if (allowAutofill) {
                    Log.d(TAG, "⚠️ Server requested autofill — will retry after delay")
                }

                return HourlyRequestResult(
                    success = false,
                    allowAutofill = allowAutofill,
                    errorMessage = errorMessage
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Hourly API error", e)
            return HourlyRequestResult(success = false, errorMessage = e.message)
        } finally {
            connection.disconnect()
        }
    }
    private fun fetchFeederList(token: String): List<JSONObject> {
        return try {
            val url = URL("http://62.72.59.119:9009/api/feeder/list")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("Authorization", "Bearer $token")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val json = JSONObject(response)
                val dataArray = json.optJSONArray("data") ?: return emptyList()
                (0 until dataArray.length()).map { dataArray.getJSONObject(it) }
            } else {
                Log.e(TAG, "❌ Failed to fetch feeder list: ${connection.responseCode}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchFeederList error", e)
            emptyList()
        }
    }
    private suspend fun uploadConsumptionData(consumptionData: List<ConsumptionEntryData>, date: String): Int {
        if (consumptionData.isEmpty()) return 0

        val token = SessionManager.getToken(context)
        if (token.isEmpty()) throw Exception("User not logged in")

        // ✅ Fetch master feeder list from API
        val allFeeders = fetchFeederList(token)

        val rowsArray = JSONArray()

        // ✅ Add submitted feeders
        val submittedCodes = consumptionData.map { it.feederCode.trim().uppercase() }.toSet()

        consumptionData.forEach { entry ->
            rowsArray.put(JSONObject().apply {
                put("date", entry.date)
                put("station_name", entry.stationName)
                put("feeder_name", entry.feederName)
                put("feeder_code", entry.feederCode)
                put("feeder_category", entry.feederCategory)
                put("remark", entry.remark.ifEmpty { "PROPER" })
                put("total_consumption", entry.totalConsumption ?: 0.0)
                put("supply_3ph", entry.supply3ph.ifEmpty { "00:00" })
                put("supply_1ph", entry.supply1ph.ifEmpty { "00:00" })
            })
        }

        // ✅ Add missing feeders with default values
        allFeeders.forEach { feeder ->
            val code = (feeder.optString("FEEDER_CODE") ?: "").trim().uppercase()
            if (code.isNotEmpty() && !submittedCodes.contains(code)) {
                Log.d(TAG, "➕ Auto-filling missing feeder: ${feeder.optString("FEEDER_NAME")}")
                rowsArray.put(JSONObject().apply {
                    put("date", date)
                    put("station_name", feeder.optString("STATION_NAME"))
                    put("feeder_name", feeder.optString("FEEDER_NAME"))
                    put("feeder_code", feeder.optString("FEEDER_CODE"))
                    put("feeder_category", feeder.optString("FEEDER_CATEGORY"))
                    put("remark", "PROPER")
                    put("total_consumption", 0.0)
                    put("supply_3ph", "00:00")
                    put("supply_1ph", "00:00")
                })
            }
        }

        val requestBody = JSONObject().apply { put("rows", rowsArray) }
        Log.d(TAG, "📤 Consumption Request (${rowsArray.length()} rows): ${requestBody.toString(2)}")

        val result = submitToConsumptionAPI(token, requestBody)
        return if (result) rowsArray.length() else 0
    }
    /**
     * ✅ Parse error response from backend API
     */
    private fun parseErrorResponse(errorBody: String?): String {
        return try {
            if (errorBody.isNullOrEmpty()) return "Unknown error occurred"

            val jsonObject = JSONObject(errorBody)

            // Try to get message field
            val message = jsonObject.optString("message", "")
            if (message.isNotEmpty()) {
                var fullMessage = message

                // Check for validation errors array
                val errorsArray = jsonObject.optJSONArray("errors")
                if (errorsArray != null && errorsArray.length() > 0) {
                    fullMessage += "\n\nValidation Errors:\n"
                    for (i in 0 until errorsArray.length()) {
                        val error = errorsArray.getJSONObject(i)
                        val feeder = error.optString("feeder", "Unknown")
                        val param = error.optString("parameter", "")
                        val hour = error.optString("hour", "")
                        val errorMsg = error.optString("error", "")
                        fullMessage += "• $feeder [$param] Hour $hour: $errorMsg\n"
                    }
                }

                // Check for details
                val details = jsonObject.optJSONObject("details")
                if (details != null) {
                    val rule = details.optString("rule", "")
                    if (rule.isNotEmpty()) {
                        fullMessage += "\nRule: $rule"
                    }
                }

                return fullMessage
            }

            // Fallback to full error body
            errorBody

        } catch (e: Exception) {
            errorBody ?: "Error: ${e.message}"
        }
    }
    private suspend fun submitToConsumptionAPI(token: String, requestBody: JSONObject): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = URL(CONSUMPTION_SAVE_URL)
            val connection = url.openConnection() as HttpURLConnection

            try {
                connection.apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT
                    readTimeout = TIMEOUT
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                    doOutput = true
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                        it.readText()
                    }
                    Log.d(TAG, "✅ Consumption Response: $response")

                    val jsonResponse = JSONObject(response)
                    jsonResponse.optBoolean("success", false)
                } else {
                    Log.e(TAG, "❌ HTTP Error: $responseCode")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Consumption API error", e)
                false
            } finally {
                connection.disconnect()
            }
        }
    }
}

data class ExcelData(
    val hourlyData: List<HourlyEntryData>,
    val consumptionData: List<ConsumptionEntryData>
)

data class HourlyEntryData(
    val date: String,
    val feederCode: String?,      // ✅ Nullable
    val feederName: String,       // ✅ ADDED - Required for fallback search
    val feederCategory: String,   // ✅ ADDED - For completeness
    val parameter: String,
    val hours: Map<String, String>  // Keys are "00", "01" ... "23" (database format)
)

data class ConsumptionEntryData(
    val date: String,
    val stationName: String,      // ✅ Added
    val feederName: String,       // ✅ Added
    val feederCode: String,
    val feederCategory: String,
    val remark: String,
    val totalConsumption: Double?,
    val supply3ph: String,         // ✅ In HH:MM format after conversion
    val supply1ph: String          // ✅ In HH:MM format after conversion
)

data class UploadResult(
    val hourlyCount: Int,
    val consumptionCount: Int
)