package com.apc.kptcl.home.users.upload

import android.content.Context
import android.os.Environment
import android.util.Log
import com.apc.kptcl.home.adapter.FeederHourlyRepository
import com.apc.kptcl.utils.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.ss.usermodel.DataValidationConstraint
import org.apache.poi.ss.usermodel.DataValidationHelper
import org.apache.poi.ss.util.CellRangeAddressList
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class DynamicExcelGenerator(private val context: Context) {

    companion object {
        private const val TAG = "DynamicExcelGen"
        private const val FEEDER_LIST_URL = "http://62.72.59.119:9009/api/feeder/list"
        private const val CONSUMPTION_URL = "http://62.72.59.119:9009/api/feeder/consumption" // ✅ FIXED
        private const val TIMEOUT = 15000
        private val PARAMETERS = listOf("IR", "IY", "IB", "MW", "MVAR")
        private val REMARK_OPTIONS = listOf(
            "PROPER",
            "LINE CLEAR",
            "HAND TRIP",
            "MAIN SUPPLY FAILURE",
            "METER FAULTY",
            "FAULT TRIP(OCR OR EFR)",
            "BANK IN TRIPPED CONDITION",
            "TC IN TRIPPED CONDITION"
        )
    }

    // ✅ Use same repository as FeederViewFragment!
    private val repository = FeederHourlyRepository()

    suspend fun generateTemplate(selectedDate: String): Result<File> {
        return try {
            val token = SessionManager.getToken(context)
            if (token.isEmpty()) {
                return Result.failure(Exception("User not logged in"))
            }

            Log.d(TAG, "📄 Fetching feeders from API...")

            val feedersResponse = fetchFeedersFromAPI(token)

            val username = SessionManager.getUsername(context)
            val stationName = if (username.isNotBlank()) {
                username
            } else {
                feedersResponse.station.ifBlank { "UNKNOWN_STATION" }
            }

            val feeders = feedersResponse.feeders

            if (feeders.isEmpty()) {
                return Result.failure(Exception("No feeders found for station"))
            }

            Log.d(TAG, "✅ Station (Username): $stationName, Feeders: ${feeders.size}")

            // ✅ Fetch existing data for the selected date using SAME REPOSITORY
            Log.d(TAG, "📥 Fetching existing data for date: $selectedDate")
            val hourlyData = fetchAllHourlyData(token, selectedDate, feeders)
            val consumptionData = fetchConsumptionData(token, selectedDate, feeders) // ✅ FIXED: Pass feeders list

            val file = createExcelFile(stationName, selectedDate, feeders, hourlyData, consumptionData)

            Log.d(TAG, "✅ Excel generated: ${file.name}")
            Result.success(file)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error generating Excel", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchFeedersFromAPI(token: String): FeedersResponse {
        return withContext(Dispatchers.IO) {
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
                Log.d(TAG, "API Response Code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                        it.readText()
                    }
                    Log.d(TAG, "📥 API Response: $response")
                    parseFeedersResponse(response)
                } else {
                    throw Exception("API failed with code: $responseCode")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    // ✅ Fetch hourly data for ALL feeders using SAME REPOSITORY as FeederViewFragment
    private suspend fun fetchAllHourlyData(
        token: String,
        date: String,
        feeders: List<FeederInfo>
    ): Map<String, Map<String, String>> {
        val hourlyDataMap = mutableMapOf<String, MutableMap<String, String>>()

        try {
            Log.d(TAG, "📥 Fetching hourly data for ${feeders.size} feeders for date: $date")

            // Fetch data for each feeder using the SAME repository
            feeders.forEach { feeder ->
                try {
                    val result = repository.fetchFeederHourlyData(
                        feederId = feeder.feederId,  // ✅ Use feederId (nullable) not feederCode
                        date = date,
                        token = token,
                        limit = 100,
                        feederName = feeder.feederName
                    )

                    if (result.isSuccess) {
                        val response = result.getOrNull()
                        if (response != null && response.data.isNotEmpty()) {
                            // ✅ Convert repository response to our format
                            response.data.forEach { hourlyData ->
                                val key = "${feeder.feederName}_${hourlyData.parameter}"
                                val hourMap = mutableMapOf<String, String>()

                                // ✅ Extract from hourlyValues map (00-23)
                                hourlyData.hourlyValues.forEach { (hour, value) ->
                                    if (value != null) {
                                        val formattedValue = when {
                                            value == 0.0 -> "0"
                                            value == value.toInt().toDouble() -> value.toInt().toString()
                                            else -> String.format("%.2f", value)
                                        }
                                        hourMap[hour] = formattedValue
                                    }
                                }

                                if (hourMap.isNotEmpty()) {
                                    hourlyDataMap[key] = hourMap
                                }
                            }

                            Log.d(TAG, "✅ Fetched data for ${feeder.feederName}: ${response.data.size} parameters")
                        } else {
                            Log.d(TAG, "ℹ️ No data for ${feeder.feederName} on $date")
                        }
                    } else {
                        Log.w(TAG, "⚠️ Error fetching data for ${feeder.feederName}: ${result.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Exception fetching data for ${feeder.feederName}: ${e.message}")
                }
            }

            Log.d(TAG, "✅ Total hourly records fetched: ${hourlyDataMap.size}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in fetchAllHourlyData", e)
        }

        return hourlyDataMap
    }

    // ✅ FIXED: Fetch consumption data for ALL feeders individually
    private suspend fun fetchConsumptionData(
        token: String,
        date: String,
        feeders: List<FeederInfo>
    ): Map<String, ConsumptionInfo> {
        val dataMap = mutableMapOf<String, ConsumptionInfo>()

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "📥 Fetching consumption data for ${feeders.size} feeders for date: $date")

                // ✅ Fetch individually for each feeder (same as DailyParameterViewFragment)
                for (feeder in feeders) {
                    try {
                        val url = URL(CONSUMPTION_URL)
                        val connection = url.openConnection() as HttpURLConnection

                        connection.apply {
                            requestMethod = "POST"
                            connectTimeout = TIMEOUT
                            readTimeout = TIMEOUT
                            setRequestProperty("Content-Type", "application/json")
                            setRequestProperty("Accept", "application/json")
                            setRequestProperty("Authorization", "Bearer $token")
                            doOutput = true
                            doInput = true
                        }

                        // ✅ FIXED: Send both feeder_id and feeder_name for null handling
                        val jsonBody = JSONObject().apply {
                            feeder.feederId?.let { put("feeder_id", it) }  // ✅ Only add if not null
                            put("feeder_name", feeder.feederName)          // ✅ Always send name
                            put("date", date)
                        }

                        OutputStreamWriter(connection.outputStream).use { writer ->
                            writer.write(jsonBody.toString())
                            writer.flush()
                        }

                        val responseCode = connection.responseCode

                        if (responseCode == HttpURLConnection.HTTP_OK) {
                            val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                                it.readText()
                            }

                            // Parse response
                            val jsonObject = JSONObject(response)
                            val success = jsonObject.optBoolean("success", false)

                            if (success) {
                                val dataArray = jsonObject.optJSONArray("data")

                                if (dataArray != null && dataArray.length() > 0) {
                                    val item = dataArray.getJSONObject(0)

                                    val remark = item.optString("REMARK", "PROPER")
                                    val totalConsumption = item.optString("TOTAL_CONSUMPTION", "")
                                    val supply3ph = item.optString("SUPPLY_3PH", "")
                                    val supply1ph = item.optString("SUPPLY_1PH", "")

                                    dataMap[feeder.feederName] = ConsumptionInfo(
                                        remark = remark,
                                        totalConsumption = totalConsumption,
                                        supply3ph = supply3ph,
                                        supply1ph = supply1ph
                                    )

                                    Log.d(TAG, "  ✅ ${feeder.feederName}: TC=$totalConsumption, 3PH=$supply3ph, 1PH=$supply1ph")
                                } else {
                                    Log.d(TAG, "  ℹ️ No data for ${feeder.feederName}")
                                }
                            }
                        } else {
                            Log.d(TAG, "  ℹ️ No data for ${feeder.feederName} (Status: $responseCode)")
                        }

                        connection.disconnect()

                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Error fetching consumption for ${feeder.feederName}: ${e.message}")
                    }
                }

                Log.d(TAG, "✅ Parsed ${dataMap.size} consumption records")

            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error fetching consumption data: ${e.message}")
            }

            dataMap
        }
    }

    private fun parseFeedersResponse(jsonString: String): FeedersResponse {
        val feeders = mutableListOf<FeederInfo>()

        val jsonObject = JSONObject(jsonString)
        val success = jsonObject.optBoolean("success", false)

        if (!success) {
            throw Exception(jsonObject.optString("message", "API request failed"))
        }

        var station = ""
        val dataArray = jsonObject.optJSONArray("data")

        if (dataArray != null && dataArray.length() > 0) {
            val firstFeeder = dataArray.getJSONObject(0)
            station = firstFeeder.optString("STATION_NAME", "")

            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)

                // ✅ CRITICAL FIX: Handle null FEEDER_CODE properly
                val feederCode = if (item.isNull("FEEDER_CODE")) {
                    null
                } else {
                    val code = item.optString("FEEDER_CODE", "")
                    if (code.isEmpty()) null else code
                }

                val feederName = item.optString("FEEDER_NAME", "")

                if (feederName.isNotEmpty()) {
                    feeders.add(
                        FeederInfo(
                            feederId = feederCode,  // ✅ Use feederId (nullable)
                            feederName = feederName,
                            feederCategory = item.optString("FEEDER_CATEGORY", ""),
                            stationName = station
                        )
                    )
                }
            }
        }

        return FeedersResponse(station, feeders.sortedBy { it.feederName })
    }

    private fun createExcelFile(
        stationName: String,
        date: String,
        feeders: List<FeederInfo>,
        hourlyData: Map<String, Map<String, String>>,
        consumptionData: Map<String, ConsumptionInfo>
    ): File {
        val workbook = XSSFWorkbook()
        val safeDate = date.replace("-", "_")

        createFeederHourlySheet(workbook, stationName, date, safeDate, feeders, hourlyData)
        createFeederConsumptionSheet(workbook, stationName, date, safeDate, feeders, consumptionData)

        return saveWorkbook(workbook, stationName, safeDate)
    }

    private fun createFeederHourlySheet(
        workbook: XSSFWorkbook,
        stationName: String,
        date: String,
        safeDate: String,
        feeders: List<FeederInfo>,
        hourlyData: Map<String, Map<String, String>>
    ) {
        val sheet = workbook.createSheet("FH_$safeDate")

        val headerRow = sheet.createRow(0)
        val headers = mutableListOf(
            "DATE", "STATION_NAME", "FEEDER_NAME", "FEEDER_CODE",
            "FEEDER_CATEGORY", "PARAMETER"
        )

        // ✅ Display hours 1-24 for user
        for (hour in 1..24) {
            headers.add(hour.toString())
        }

        val headerStyle = createHeaderStyle(workbook) as XSSFCellStyle
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }

        var rowIndex = 1
        val textStyle = createTextStyle(workbook) as XSSFCellStyle

        feeders.forEach { feeder ->
            PARAMETERS.forEach { param ->
                val row = sheet.createRow(rowIndex++)

                row.createCell(0).setCellValue(date)
                row.createCell(1).setCellValue(stationName)
                row.createCell(2).setCellValue(feeder.feederName)

                val codeCell = row.createCell(3)
                codeCell.setCellValue(feeder.feederId ?: "")  // ✅ Write empty for null
                codeCell.cellStyle = textStyle

                row.createCell(4).setCellValue(feeder.feederCategory)
                row.createCell(5).setCellValue(param)

                // ✅ Pre-fill hourly data from repository
                val key = "${feeder.feederName}_${param}"
                val existingData = hourlyData[key]

                for (hour in 0 until 24) {
                    val hourKey = String.format("%02d", hour)
                    val cellValue = existingData?.get(hourKey) ?: ""
                    row.createCell(6 + hour).setCellValue(cellValue)
                }
            }
        }

        setColumnWidths(sheet)
        applySheetProtection(sheet, unlockColumnStart = 6)
    }

    private fun createFeederConsumptionSheet(
        workbook: XSSFWorkbook,
        stationName: String,
        date: String,
        safeDate: String,
        feeders: List<FeederInfo>,
        consumptionData: Map<String, ConsumptionInfo>
    ) {
        val sheet = workbook.createSheet("FC_$safeDate")

        val headerRow = sheet.createRow(0)
        val headers = listOf(
            "DATE", "STATION_NAME", "FEEDER_NAME", "FEEDER_CODE",
            "FEEDER_CATEGORY", "REMARK", "TOTAL_CONSUMPTION",
            "SUPPLY_3PH", "SUPPLY_1PH"
        )

        val headerStyle = createHeaderStyle(workbook) as XSSFCellStyle
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }

        val textStyle = createTextStyle(workbook) as XSSFCellStyle

        feeders.forEachIndexed { index, feeder ->
            val row = sheet.createRow(index + 1)

            row.createCell(0).setCellValue(date)
            row.createCell(1).setCellValue(stationName)
            row.createCell(2).setCellValue(feeder.feederName)

            val codeCell = row.createCell(3)
            codeCell.setCellValue(feeder.feederId ?: "")  // ✅ Write empty for null
            codeCell.cellStyle = textStyle

            row.createCell(4).setCellValue(feeder.feederCategory)

            // ✅ Pre-fill consumption data from API
            val existingData = consumptionData[feeder.feederName]

            row.createCell(5).setCellValue(existingData?.remark ?: "PROPER")
            row.createCell(6).setCellValue(existingData?.totalConsumption ?: "")
            row.createCell(7).setCellValue(existingData?.supply3ph ?: "")
            row.createCell(8).setCellValue(existingData?.supply1ph ?: "")
        }

        addRemarkDropdown(sheet, feeders.size)
        setConsumptionColumnWidths(sheet)
        applySheetProtection(sheet, unlockColumnStart = 5)
    }

    private fun setColumnWidths(sheet: Sheet) {
        sheet.setColumnWidth(0, 3000)   // DATE
        sheet.setColumnWidth(1, 9009)   // STATION_NAME
        sheet.setColumnWidth(2, 6000)   // FEEDER_NAME
        sheet.setColumnWidth(3, 4000)   // FEEDER_CODE
        sheet.setColumnWidth(4, 5000)   // FEEDER_CATEGORY
        sheet.setColumnWidth(5, 3500)   // PARAMETER

        // 24 hour columns
        for (i in 6 until 30) {
            sheet.setColumnWidth(i, 2500)
        }
    }

    private fun setConsumptionColumnWidths(sheet: Sheet) {
        sheet.setColumnWidth(0, 3000)   // DATE
        sheet.setColumnWidth(1, 9009)   // STATION_NAME
        sheet.setColumnWidth(2, 6000)   // FEEDER_NAME
        sheet.setColumnWidth(3, 4000)   // FEEDER_CODE
        sheet.setColumnWidth(4, 5000)   // FEEDER_CATEGORY
        sheet.setColumnWidth(5, 9009)   // REMARK
        sheet.setColumnWidth(6, 5000)   // TOTAL_CONSUMPTION
        sheet.setColumnWidth(7, 3500)   // SUPPLY_3PH
        sheet.setColumnWidth(8, 3500)   // SUPPLY_1PH
    }

    private fun addRemarkDropdown(sheet: Sheet, feederCount: Int) {
        val helper: DataValidationHelper = sheet.dataValidationHelper
        val addressList = CellRangeAddressList(1, feederCount, 5, 5)
        val constraint: DataValidationConstraint = helper.createExplicitListConstraint(
            REMARK_OPTIONS.toTypedArray()
        )

        val validation: DataValidation = helper.createValidation(constraint, addressList)
        validation.showErrorBox = true
        validation.createErrorBox("Invalid Value", "Please select from dropdown")
        validation.emptyCellAllowed = false

        sheet.addValidationData(validation)
    }

    private fun applySheetProtection(sheet: Sheet, unlockColumnStart: Int) {
        val workbook = sheet.workbook

        val lockedStyle = workbook.createCellStyle()
        lockedStyle.setLocked(true)

        val unlockedStyle = workbook.createCellStyle()
        unlockedStyle.setLocked(false)

        for (rowNum in 0..sheet.lastRowNum) {
            val row = sheet.getRow(rowNum) ?: continue

            for (cellNum in 0 until row.lastCellNum) {
                val cell = row.getCell(cellNum) ?: row.createCell(cellNum)

                // ✅ Lock header row (0) and non-input columns (0-5)
                cell.cellStyle = if (rowNum == 0 || cellNum < unlockColumnStart) {
                    lockedStyle
                } else {
                    unlockedStyle
                }
            }
        }

        sheet.protectSheet("")
    }

    private fun createHeaderStyle(workbook: Workbook): CellStyle {
        val style = workbook.createCellStyle()
        val font = workbook.createFont()
        font.bold = true
        font.fontHeightInPoints = 11
        style.setFont(font)
        style.alignment = HorizontalAlignment.CENTER
        style.verticalAlignment = VerticalAlignment.CENTER
        return style
    }

    private fun createTextStyle(workbook: Workbook): CellStyle {
        val style = workbook.createCellStyle()
        style.dataFormat = workbook.createDataFormat().getFormat("@")
        return style
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
    private fun saveWorkbook(workbook: XSSFWorkbook, stationName: String, safeDate: String): File {
        val fileName = "ELOG_TEMPLATE_${stationName}_$safeDate.xlsx"

        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )

        val file = File(downloadsDir, fileName)

        FileOutputStream(file).use { outputStream ->
            workbook.write(outputStream)
        }

        workbook.close()

        return file
    }
}

data class FeedersResponse(
    val station: String,
    val feeders: List<FeederInfo>
)

data class FeederInfo(
    val feederId: String?,        // Nullable - some feeders don't have codes
    val feederName: String,
    val feederCategory: String,
    val stationName: String = ""  // Optional, defaults to empty
) {
    // Helper property for backward compatibility
    val feederCode: String
        get() = feederId ?: ""

    // Helper property for repository compatibility
    val category: String
        get() = feederCategory
}

data class ConsumptionInfo(
    val remark: String,
    val totalConsumption: String,
    val supply3ph: String,
    val supply1ph: String
)