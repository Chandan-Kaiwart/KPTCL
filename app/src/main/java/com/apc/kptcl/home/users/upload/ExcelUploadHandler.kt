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
        private const val FEEDER_LIST_URL = "http://62.72.59.119:9009/api/feeder/list"
        private const val TIMEOUT = 15000

        private val HOURLY_HEADERS = listOf(
            "DATE", "STATION_NAME", "FEEDER_NAME", "FEEDER_CODE",
            "FEEDER_CATEGORY", "PARAMETER"
        ) + (1..24).map { it.toString() }

        private val CONSUMPTION_HEADERS = listOf(
            "DATE", "STATION_NAME", "FEEDER_NAME", "FEEDER_CODE",
            "FEEDER_CATEGORY", "REMARK", "TOTAL_CONSUMPTION",
            "SUPPLY_3PH", "SUPPLY_1PH"
        )

        private val VALID_PARAMETERS = listOf("IR", "IY", "IB", "MW", "MVAR")

        // ✅ MUST match REMARK_OPTIONS in DynamicExcelGenerator exactly
        private val VALID_REMARKS = listOf("PROPER", "SHUTDOWN", "BREAKDOWN", "HOLIDAY", "UNDER_MAINTENANCE")

        private fun isValidNumeric(value: String): Boolean {
            if (value.isBlank()) return true
            return try { value.trim().toDouble(); true } catch (e: NumberFormatException) { false }
        }

        // ✅ FIX: Normalize any cell string — strip whitespace, normalize unicode spaces
        private fun normalize(value: String): String = value.trim().replace("\u00A0", " ").trim()
    }

    private data class MasterFeeder(
        val feederName: String,
        val feederCode: String,
        val feederCategory: String
    )

    // ===============================
    // ENTRY POINT
    // ===============================
    /**
     * @param autofillApproved  Pass true when the user already confirmed "Yes, Autofill with 0"
     *                          in the Fragment dialog (second call).
     */
    suspend fun validateAndUpload(
        uri: Uri,
        expectedDate: String,
        autofillApproved: Boolean = false
    ): Result<UploadResult> {
        return try {
            Log.d(TAG, "📂 Starting Excel validation and upload... autofillApproved=$autofillApproved")

            val token = SessionManager.getToken(context)
            if (token.isEmpty()) return Result.failure(Exception("User not logged in. Please login again."))

            // ── IST date/hour detection ──
            val istOffsetMs = 5L * 3_600_000L + 30L * 60_000L
            val nowUtcMs    = System.currentTimeMillis()
            val sdf         = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val todayISTStr = sdf.format(java.util.Date(nowUtcMs + istOffsetMs))
            val isToday     = (expectedDate == todayISTStr)
            val istCal      = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            istCal.timeInMillis = nowUtcMs + istOffsetMs
            val currentISTHour = istCal.get(java.util.Calendar.HOUR_OF_DAY) // 0-23

            Log.d(TAG, "📅 expectedDate=$expectedDate  isToday=$isToday  currentISTHour=$currentISTHour")

            // Fetch master feeder list FIRST
            val masterFeeders = fetchMasterFeeders(token)
            if (masterFeeders.isEmpty()) {
                return Result.failure(Exception(
                    "❌ Could not fetch feeder list from server.\n\n" +
                            "Please check your internet connection and try again."
                ))
            }
            Log.d(TAG, "✅ Master feeders fetched: ${masterFeeders.size}")

            val validationResult = validateExcelFile(
                uri, expectedDate, masterFeeders,
                isToday = isToday,
                currentISTHour = currentISTHour,
                autofillApproved = autofillApproved
            )
            if (validationResult.isFailure) return Result.failure(validationResult.exceptionOrNull()!!)

            val excelData = validationResult.getOrNull()!!

            // ── needsAutofill bubble-up: return to Fragment to show dialog ──
            if (excelData.needsAutofill) {
                return Result.success(
                    UploadResult(
                        hourlyCount   = 0,
                        consumptionCount = 0,
                        needsAutofill = true,
                        emptyCellCount = excelData.emptyCellCount,
                        isTodayUpload = isToday,
                        upToHour      = if (isToday) currentISTHour - 1 else 23
                    )
                )
            }

            Log.d(TAG, "✅ Validation passed. Hourly: ${excelData.hourlyData.size}, Consumption: ${excelData.consumptionData.size}")

            val hourlyCount = uploadHourlyData(excelData.hourlyData, token)
            Log.d(TAG, "✅ Hourly upload complete: $hourlyCount records")

            // For today: skip consumption entirely
            val consumptionCount = if (!isToday) {
                val c = uploadConsumptionData(excelData.consumptionData, expectedDate, token)
                Log.d(TAG, "✅ Consumption upload complete: $c records")
                c
            } else {
                Log.d(TAG, "📅 Today's date — consumption upload skipped.")
                0
            }

            Result.success(
                UploadResult(
                    hourlyCount      = hourlyCount,
                    consumptionCount = consumptionCount,
                    isTodayUpload    = isToday,
                    upToHour         = if (isToday) currentISTHour - 1 else 23
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Upload error", e)
            Result.failure(e)
        }
    }

    // ===============================
    // FETCH MASTER FEEDER LIST
    // ===============================
    private fun fetchMasterFeeders(token: String): List<MasterFeeder> {
        return try {
            val url = URL(FEEDER_LIST_URL)
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
                (0 until dataArray.length()).map { i ->
                    val obj = dataArray.getJSONObject(i)
                    MasterFeeder(
                        feederName     = obj.optString("FEEDER_NAME", "").trim(),
                        feederCode     = obj.optString("FEEDER_CODE", "").trim(),
                        feederCategory = obj.optString("FEEDER_CATEGORY", "").trim()
                    )
                }
            } else {
                Log.e(TAG, "❌ Failed to fetch feeder list: ${connection.responseCode}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchMasterFeeders error", e)
            emptyList()
        }
    }

    // ===============================
    // MAIN VALIDATION
    // ===============================
    private fun validateExcelFile(
        uri: Uri,
        expectedDate: String,
        masterFeeders: List<MasterFeeder>,
        isToday: Boolean = false,
        currentISTHour: Int = 23,
        autofillApproved: Boolean = false
    ): Result<ExcelData> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(Exception("Cannot open file. Please try again."))

            val workbook = WorkbookFactory.create(inputStream)
            val loggedInUsername = SessionManager.getUsername(context)

            // --- 1. Exactly 2 sheets ---
            if (workbook.numberOfSheets != 2) {
                workbook.close()
                return Result.failure(Exception(
                    "❌ Invalid File!\n\n" +
                            "This file has ${workbook.numberOfSheets} sheet(s).\n" +
                            "Original template has exactly 2 sheets (Hourly + Consumption).\n\n" +
                            "Do not add or remove sheets.\n" +
                            "➡️ Re-download the template and fill it again."
                ))
            }

            val hourlySheet      = workbook.getSheetAt(0)
            val consumptionSheet = workbook.getSheetAt(1)

            // --- 2. Sheet names must match date ---
            // ✅ FIX: normalize sheet names before comparing (trim whitespace/unicode)
            val expectedHourlyName      = "FH_$expectedDate"
            val expectedConsumptionName = "FC_$expectedDate"

            if (normalize(hourlySheet.sheetName) != expectedHourlyName) {
                workbook.close()
                return Result.failure(Exception(
                    "❌ Wrong Template!\n\n" +
                            "Hourly sheet name: '${hourlySheet.sheetName}'\n" +
                            "Expected: '$expectedHourlyName'\n\n" +
                            "Do not rename the sheets, or you may be uploading a template for a different date.\n" +
                            "➡️ Re-download the template for '$expectedDate' and upload again."
                ))
            }
            if (normalize(consumptionSheet.sheetName) != expectedConsumptionName) {
                workbook.close()
                return Result.failure(Exception(
                    "❌ Wrong Template!\n\n" +
                            "Consumption sheet name: '${consumptionSheet.sheetName}'\n" +
                            "Expected: '$expectedConsumptionName'\n\n" +
                            "Do not rename the sheets, or you may be uploading a template for a different date.\n" +
                            "➡️ Re-download the template for '$expectedDate' and upload again."
                ))
            }

            // --- 3. Header validation ---
            val hourlyHeaderRow = hourlySheet.getRow(0)
                ?: run { workbook.close(); return Result.failure(Exception("❌ Hourly sheet is empty. Re-download the template.")) }
            for (i in HOURLY_HEADERS.indices) {
                val cell = normalize(getCellValueAsString(hourlyHeaderRow.getCell(i)))
                if (cell != HOURLY_HEADERS[i]) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Column Header Modified!\n\n" +
                                "Hourly sheet, Column ${i + 1}:\n" +
                                "Expected header: '${HOURLY_HEADERS[i]}'\n" +
                                "Found: '$cell'\n\n" +
                                "Do not edit column headers.\n" +
                                "➡️ Re-download the template."
                    ))
                }
            }

            val consumptionHeaderRow = consumptionSheet.getRow(0)
                ?: run { workbook.close(); return Result.failure(Exception("❌ Consumption sheet is empty. Re-download the template.")) }
            for (i in CONSUMPTION_HEADERS.indices) {
                val cell = normalize(getCellValueAsString(consumptionHeaderRow.getCell(i)))
                if (cell != CONSUMPTION_HEADERS[i]) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Column Header Modified!\n\n" +
                                "Consumption sheet, Column ${i + 1}:\n" +
                                "Expected header: '${CONSUMPTION_HEADERS[i]}'\n" +
                                "Found: '$cell'\n\n" +
                                "Do not edit column headers.\n" +
                                "➡️ Re-download the template."
                    ))
                }
            }

            // =============================================
            // 4. HOURLY SHEET — row by row
            // =============================================
            val hourlyData        = mutableListOf<HourlyEntryData>()
            val hourlyRowsSeen    = mutableSetOf<String>()

            for (rowNum in 1..hourlySheet.lastRowNum) {
                val row = hourlySheet.getRow(rowNum) ?: continue

                // ✅ FIX: normalize() all locked-column values before comparing
                val date           = normalize(getCellValueAsString(row.getCell(0)))
                val stationName    = normalize(getCellValueAsString(row.getCell(1)))
                val feederName     = normalize(getCellValueAsString(row.getCell(2)))
                val feederCode     = normalize(getCellValueAsString(row.getCell(3)))
                val feederCategory = normalize(getCellValueAsString(row.getCell(4)))
                val parameter      = normalize(getCellValueAsString(row.getCell(5)))

                // Skip fully blank rows
                if (date.isEmpty() && stationName.isEmpty() && feederName.isEmpty() && parameter.isEmpty()) continue

                // ✅ FIX: DATE — trim + exact match (handles accidental edit + re-correct)
                if (date != expectedDate) {
                    workbook.close()
                    return Result.failure(Exception(
                        "📅 Date Modified in Hourly Sheet!\n\n" +
                                "Row ${rowNum + 1}: DATE = '$date'\n" +
                                "Expected: '$expectedDate'\n\n" +
                                "The DATE column must not be edited.\n" +
                                "➡️ Re-download the template for '$expectedDate'."
                    ))
                }

                // ✅ FIX: STATION_NAME — trim + case-insensitive (already was, now normalize() ensures no hidden spaces)
                if (stationName.uppercase() != loggedInUsername.trim().uppercase()) {
                    workbook.close()
                    return Result.failure(Exception(
                        "🏭 Station Name Modified in Hourly Sheet!\n\n" +
                                "Row ${rowNum + 1}: STATION_NAME = '$stationName'\n" +
                                "Your station: '$loggedInUsername'\n\n" +
                                "The STATION_NAME column must not be edited.\n" +
                                "You can only upload data for your own station."
                    ))
                }

                // FEEDER_NAME not empty
                if (feederName.isBlank()) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ FEEDER_NAME Missing!\n\n" +
                                "Hourly sheet, Row ${rowNum + 1}: FEEDER_NAME is empty.\n\n" +
                                "Do not delete feeder rows.\n" +
                                "➡️ Re-download the template."
                    ))
                }

                // ✅ STRICT: FEEDER_NAME must exist in master list
                val matchedFeeder = masterFeeders.find {
                    it.feederName.uppercase() == feederName.uppercase()
                }
                if (matchedFeeder == null) {
                    workbook.close()
                    val validNames = masterFeeders.joinToString("\n") { "  • ${it.feederName}" }
                    return Result.failure(Exception(
                        "❌ Invalid Feeder in Hourly Sheet!\n\n" +
                                "Row ${rowNum + 1}: FEEDER_NAME = '$feederName'\n\n" +
                                "This feeder does not exist in your station's feeder list.\n" +
                                "Do not edit FEEDER_NAME.\n\n" +
                                "Valid feeder names:\n$validNames\n\n" +
                                "➡️ Re-download the template."
                    ))
                }

                // ✅ STRICT: FEEDER_CODE must exactly match master
                if (feederCode != matchedFeeder.feederCode) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Feeder Code Mismatch in Hourly Sheet!\n\n" +
                                "Row ${rowNum + 1}: Feeder '$feederName'\n" +
                                "FEEDER_CODE in file : '$feederCode'\n" +
                                "Expected            : '${matchedFeeder.feederCode.ifEmpty { "(empty)" }}'\n\n" +
                                "Do not edit FEEDER_CODE.\n" +
                                "➡️ Re-download the template."
                    ))
                }

                // ✅ STRICT: FEEDER_CATEGORY must exactly match master
                if (feederCategory.uppercase() != matchedFeeder.feederCategory.uppercase()) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Feeder Category Mismatch in Hourly Sheet!\n\n" +
                                "Row ${rowNum + 1}: Feeder '$feederName'\n" +
                                "FEEDER_CATEGORY in file : '$feederCategory'\n" +
                                "Expected               : '${matchedFeeder.feederCategory}'\n\n" +
                                "Do not edit FEEDER_CATEGORY.\n" +
                                "➡️ Re-download the template."
                    ))
                }

                // PARAMETER valid
                if (parameter !in VALID_PARAMETERS) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Invalid Parameter in Hourly Sheet!\n\n" +
                                "Row ${rowNum + 1}, Feeder: $feederName\n" +
                                "PARAMETER = '$parameter'\n" +
                                "Allowed: ${VALID_PARAMETERS.joinToString(", ")}\n\n" +
                                "Do not edit PARAMETER column.\n" +
                                "➡️ Re-download the template."
                    ))
                }

                // Duplicate feeder+param check
                val rowKey = "${feederName.uppercase()}_$parameter"
                if (hourlyRowsSeen.contains(rowKey)) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Duplicate Row in Hourly Sheet!\n\n" +
                                "Row ${rowNum + 1}: Feeder '$feederName', Parameter '$parameter' appears more than once.\n\n" +
                                "Each feeder-parameter combination must appear exactly once.\n" +
                                "➡️ Re-download the template."
                    ))
                }
                hourlyRowsSeen.add(rowKey)

                // ✅ HOURLY VALUES: parse + validate
                val hours = mutableMapOf<String, String>()

                for (excelHour in 1..24) {
                    val cellValue = getCellValueAsString(row.getCell(6 + excelHour - 1))
                    val dbHour    = String.format("%02d", excelHour - 1)

                    if (cellValue.isNotEmpty()) {
                        if (!isValidNumeric(cellValue)) {
                            workbook.close()
                            return Result.failure(Exception(
                                "❌ Invalid Value in Hourly Sheet!\n\n" +
                                        "Row ${rowNum + 1}, Feeder: $feederName\n" +
                                        "Parameter: $parameter, Hour $excelHour: '$cellValue'\n\n" +
                                        "Only numeric values are allowed."
                            ))
                        }
                        val numValue = cellValue.toDouble()
                        if (parameter != "MVAR" && numValue < 0) {
                            workbook.close()
                            return Result.failure(Exception(
                                "❌ Negative Value Not Allowed!\n\n" +
                                        "Row ${rowNum + 1}, Feeder: $feederName\n" +
                                        "Parameter: $parameter, Hour $excelHour: $numValue\n\n" +
                                        "$parameter must be >= 0.\n" +
                                        "Only MVAR is allowed to be negative."
                            ))
                        }
                        hours[dbHour] = cellValue
                    }
                }

                hourlyData.add(
                    HourlyEntryData(
                        date           = date,
                        feederCode     = if (feederCode.isEmpty()) null else feederCode,
                        feederName     = feederName,
                        feederCategory = feederCategory,
                        parameter      = parameter,
                        hours          = hours
                    )
                )
            }

            if (hourlyData.isEmpty()) {
                workbook.close()
                return Result.failure(Exception(
                    "❌ No Hourly Data Found!\n\nThe Hourly sheet has no data rows.\nPlease fill the data and upload again."
                ))
            }

            // ✅ STRICT: Every master feeder must appear in the hourly sheet (no missing, no extra)
            val masterFeederNames = masterFeeders.map { it.feederName.uppercase() }.toSet()
            val hourlyFeederNames = hourlyData.map { it.feederName.uppercase() }.toSet()

            val missingFeeders = masterFeeders.filter { it.feederName.uppercase() !in hourlyFeederNames }
            if (missingFeeders.isNotEmpty()) {
                workbook.close()
                val missing = missingFeeders.joinToString("\n") { "  • ${it.feederName}" }
                return Result.failure(Exception(
                    "❌ Missing Feeders in Hourly Sheet!\n\n" +
                            "The following feeders from your station are missing:\n$missing\n\n" +
                            "Do not delete feeder rows.\n" +
                            "➡️ Re-download the template."
                ))
            }

            val extraFeeders = hourlyFeederNames.filter { it !in masterFeederNames }
            if (extraFeeders.isNotEmpty()) {
                workbook.close()
                return Result.failure(Exception(
                    "❌ Unknown Feeders in Hourly Sheet!\n\n" +
                            "The following feeders do not belong to your station:\n" +
                            extraFeeders.joinToString("\n") { "  • $it" } + "\n\n" +
                            "Do not add new feeder rows.\n" +
                            "➡️ Re-download the template."
                ))
            }

            // =============================================
            // ✅ COMPLETENESS CHECK
            // =============================================
            run {
                val checkHours: List<String> = if (isToday) {
                    if (currentISTHour == 0) emptyList()
                    else (0 until currentISTHour).map { String.format("%02d", it) }
                } else {
                    (0..23).map { String.format("%02d", it) }
                }

                if (checkHours.isNotEmpty()) {
                    var emptyCells = 0
                    for (entry in hourlyData) {
                        for (hour in checkHours) {
                            val v = entry.hours[hour]
                            if (v == null || v.isBlank()) emptyCells++
                        }
                    }

                    if (emptyCells > 0) {
                        if (!isToday) {
                            workbook.close()
                            inputStream.close()
                            return Result.failure(Exception(
                                "⚠️ Incomplete Data — Previous Day!\n\n" +
                                        "There are $emptyCells empty cells in the hourly sheet.\n\n" +
                                        "For previous day uploads, all 24 hours must be completely filled " +
                                        "for every feeder and parameter.\n\n" +
                                        "Please fill the data completely in the Excel file and upload again.\n\n" +
                                        "➡️ Open the downloaded template, fill all empty cells, and re-upload."
                            ))
                        } else {
                            if (!autofillApproved) {
                                workbook.close()
                                inputStream.close()
                                return Result.success(
                                    ExcelData(
                                        hourlyData = emptyList(),
                                        consumptionData = emptyList(),
                                        needsAutofill = true,
                                        emptyCellCount = emptyCells
                                    )
                                )
                            } else {
                                for (entry in hourlyData) {
                                    val mutableHours = entry.hours.toMutableMap()
                                    for (hour in checkHours) {
                                        if (mutableHours[hour].isNullOrBlank()) mutableHours[hour] = "0"
                                    }
                                    val idx = hourlyData.indexOf(entry)
                                    (hourlyData as MutableList)[idx] = entry.copy(hours = mutableHours)
                                }
                                Log.d(TAG, "✅ Autofill applied: $emptyCells cells filled with 0")
                            }
                        }
                    }
                }
            }

            // =============================================
            // 5. CONSUMPTION SHEET
            // =============================================
            if (isToday) {
                if (currentISTHour > 0) {
                    val futureHours = (currentISTHour..23).map { String.format("%02d", it) }.toSet()
                    for (i in hourlyData.indices) {
                        val entry = hourlyData[i]
                        val trimmedHours = entry.hours.filterKeys { it !in futureHours }.toMutableMap()
                        hourlyData[i] = entry.copy(hours = trimmedHours)
                    }
                    Log.d(TAG, "📅 Future hours stripped: $currentISTHour..23")
                }
                workbook.close()
                inputStream.close()
                Log.d(TAG, "📅 Today's date — consumption sheet skipped, returning hourly only.")
                return Result.success(ExcelData(hourlyData = hourlyData, consumptionData = emptyList()))
            }

            val consumptionData         = mutableListOf<ConsumptionEntryData>()
            val consumptionFeedersSeen  = mutableSetOf<String>()

            for (rowNum in 1..consumptionSheet.lastRowNum) {
                val row = consumptionSheet.getRow(rowNum) ?: continue

                // ✅ FIX: normalize() all locked-column values
                val date             = normalize(getCellValueAsString(row.getCell(0)))
                val stationName      = normalize(getCellValueAsString(row.getCell(1)))
                val feederName       = normalize(getCellValueAsString(row.getCell(2)))
                val feederCode       = normalize(getCellValueAsString(row.getCell(3)))
                val feederCategory   = normalize(getCellValueAsString(row.getCell(4)))
                val remark           = normalize(getCellValueAsString(row.getCell(5)))
                val totalConsumption = getCellValueAsString(row.getCell(6)).trim()
                val supply3phRaw     = getCellValueAsString(row.getCell(7)).trim()
                val supply1phRaw     = getCellValueAsString(row.getCell(8)).trim()

                if (date.isEmpty() && stationName.isEmpty() && feederName.isEmpty()) continue

                // ✅ FIX: DATE — normalize + exact match
                if (date != expectedDate) {
                    workbook.close()
                    return Result.failure(Exception(
                        "📅 Date Modified in Consumption Sheet!\n\n" +
                                "Row ${rowNum + 1}: DATE = '$date'\n" +
                                "Expected: '$expectedDate'\n\n" +
                                "The DATE column must not be edited.\n" +
                                "➡️ Re-download the template for '$expectedDate'."
                    ))
                }

                // ✅ FIX: STATION_NAME — normalize + case-insensitive
                if (stationName.uppercase() != loggedInUsername.trim().uppercase()) {
                    workbook.close()
                    return Result.failure(Exception(
                        "🏭 Station Name Modified in Consumption Sheet!\n\n" +
                                "Row ${rowNum + 1}: STATION_NAME = '$stationName'\n" +
                                "Your station: '$loggedInUsername'\n\n" +
                                "The STATION_NAME column must not be edited."
                    ))
                }

                if (feederName.isBlank()) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ FEEDER_NAME Missing!\n\nConsumption sheet, Row ${rowNum + 1}: FEEDER_NAME is empty.\n\n➡️ Re-download the template."
                    ))
                }

                // ✅ STRICT: FEEDER_NAME must exist in master list
                val matchedFeeder = masterFeeders.find {
                    it.feederName.uppercase() == feederName.uppercase()
                }
                if (matchedFeeder == null) {
                    workbook.close()
                    val validNames = masterFeeders.joinToString("\n") { "  • ${it.feederName}" }
                    return Result.failure(Exception(
                        "❌ Invalid Feeder in Consumption Sheet!\n\n" +
                                "Row ${rowNum + 1}: FEEDER_NAME = '$feederName'\n\n" +
                                "This feeder does not exist in your station's feeder list.\n" +
                                "Do not edit FEEDER_NAME.\n\n" +
                                "Valid feeder names:\n$validNames\n\n" +
                                "➡️ Re-download the template."
                    ))
                }

                // ✅ STRICT: FEEDER_CODE must match master
                if (feederCode != matchedFeeder.feederCode) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Feeder Code Mismatch in Consumption Sheet!\n\n" +
                                "Row ${rowNum + 1}: Feeder '$feederName'\n" +
                                "FEEDER_CODE in file : '$feederCode'\n" +
                                "Expected            : '${matchedFeeder.feederCode.ifEmpty { "(empty)" }}'\n\n" +
                                "Do not edit FEEDER_CODE.\n" +
                                "➡️ Re-download the template."
                    ))
                }

                // ✅ STRICT: FEEDER_CATEGORY must match master
                if (feederCategory.uppercase() != matchedFeeder.feederCategory.uppercase()) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Feeder Category Mismatch in Consumption Sheet!\n\n" +
                                "Row ${rowNum + 1}: Feeder '$feederName'\n" +
                                "FEEDER_CATEGORY in file : '$feederCategory'\n" +
                                "Expected               : '${matchedFeeder.feederCategory}'\n\n" +
                                "Do not edit FEEDER_CATEGORY.\n" +
                                "➡️ Re-download the template."
                    ))
                }

                // Duplicate feeder check
                val feederKey = feederName.uppercase()
                if (consumptionFeedersSeen.contains(feederKey)) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Duplicate Feeder in Consumption Sheet!\n\n" +
                                "Row ${rowNum + 1}: '$feederName' appears more than once.\n\n" +
                                "Each feeder must appear exactly once.\n➡️ Re-download the template."
                    ))
                }
                consumptionFeedersSeen.add(feederKey)

                // REMARK validation
                if (remark.isNotEmpty() && remark.uppercase() !in VALID_REMARKS) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Invalid REMARK in Consumption Sheet!\n\n" +
                                "Row ${rowNum + 1}, Feeder: $feederName\n" +
                                "REMARK = '$remark'\n\n" +
                                "Allowed values: ${VALID_REMARKS.joinToString(", ")}\n" +
                                "Or leave it empty (defaults to PROPER)."
                    ))
                }

                // TOTAL_CONSUMPTION — numeric and >= 0
                if (totalConsumption.isNotEmpty()) {
                    if (!isValidNumeric(totalConsumption)) {
                        workbook.close()
                        return Result.failure(Exception(
                            "❌ Invalid TOTAL_CONSUMPTION!\n\n" +
                                    "Row ${rowNum + 1}, Feeder: $feederName\n" +
                                    "Value: '$totalConsumption'\n\nOnly numeric values allowed."
                        ))
                    }
                    if ((totalConsumption.toDoubleOrNull() ?: 0.0) < 0) {
                        workbook.close()
                        return Result.failure(Exception(
                            "❌ Negative TOTAL_CONSUMPTION!\n\n" +
                                    "Row ${rowNum + 1}, Feeder: $feederName\n" +
                                    "Value: $totalConsumption\n\nMust be >= 0."
                        ))
                    }
                }

                val supply3ph = convertExcelTimeToHHMM(supply3phRaw)
                val supply1ph = convertExcelTimeToHHMM(supply1phRaw)

                if (supply3ph.isNotEmpty() && !isValidTimeFormat(supply3ph)) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Invalid SUPPLY_3PH!\n\nRow ${rowNum + 1}, Feeder: $feederName\nValue: '$supply3ph'\n\nUse HH:MM format (00:00 to 24:00)."
                    ))
                }
                if (supply1ph.isNotEmpty() && !isValidTimeFormat(supply1ph)) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Invalid SUPPLY_1PH!\n\nRow ${rowNum + 1}, Feeder: $feederName\nValue: '$supply1ph'\n\nUse HH:MM format (00:00 to 24:00)."
                    ))
                }

                val timeValidation = validateTotalSupplyTime(supply3ph, supply1ph)
                if (!timeValidation.first) {
                    workbook.close()
                    return Result.failure(Exception(
                        "❌ Supply Time Exceeds 24:00!\n\nRow ${rowNum + 1}, Feeder: $feederName\n${timeValidation.second}"
                    ))
                }

                consumptionData.add(
                    ConsumptionEntryData(
                        date             = date,
                        stationName      = stationName,
                        feederName       = feederName,
                        feederCode       = feederCode,
                        feederCategory   = feederCategory,
                        remark           = remark,
                        totalConsumption = totalConsumption.toDoubleOrNull(),
                        supply3ph        = supply3ph,
                        supply1ph        = supply1ph
                    )
                )
            }

            if (consumptionData.isEmpty()) {
                workbook.close()
                return Result.failure(Exception(
                    "❌ No Consumption Data Found!\n\nThe Consumption sheet has no data rows.\nPlease fill the data and upload again."
                ))
            }

            // ✅ STRICT: Every master feeder must appear in consumption sheet
            val consumptionFeederNames = consumptionData.map { it.feederName.uppercase() }.toSet()

            val missingConsumptionFeeders = masterFeeders.filter { it.feederName.uppercase() !in consumptionFeederNames }
            if (missingConsumptionFeeders.isNotEmpty()) {
                workbook.close()
                val missing = missingConsumptionFeeders.joinToString("\n") { "  • ${it.feederName}" }
                return Result.failure(Exception(
                    "❌ Missing Feeders in Consumption Sheet!\n\n" +
                            "The following feeders from your station are missing:\n$missing\n\n" +
                            "Do not delete feeder rows.\n" +
                            "➡️ Re-download the template."
                ))
            }

            val extraConsumptionFeeders = consumptionFeederNames.filter { it !in masterFeederNames }
            if (extraConsumptionFeeders.isNotEmpty()) {
                workbook.close()
                return Result.failure(Exception(
                    "❌ Unknown Feeders in Consumption Sheet!\n\n" +
                            "The following feeders do not belong to your station:\n" +
                            extraConsumptionFeeders.joinToString("\n") { "  • $it" } + "\n\n" +
                            "Do not add new feeder rows.\n" +
                            "➡️ Re-download the template."
                ))
            }

            workbook.close()
            inputStream.close()

            Log.d(TAG, "✅ Validation complete. Hourly: ${hourlyData.size}, Consumption: ${consumptionData.size}")
            Result.success(ExcelData(hourlyData, consumptionData))

        } catch (e: Exception) {
            Log.e(TAG, "❌ Validation error", e)
            Result.failure(e)
        }
    }

    // ===============================
    // TIME HELPERS
    // ===============================
    private fun isValidTimeFormat(time: String): Boolean {
        if (time.isBlank()) return true
        if (!time.trim().matches(Regex("^\\d{1,2}:[0-5][0-9]$"))) return false
        val parts = time.split(":")
        val h = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        return h in 0..24 && m in 0..59 && !(h == 24 && m > 0)
    }

    private fun parseTimeToMinutes(time: String): Int {
        if (time.isBlank()) return 0
        val parts = time.split(":")
        return (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
    }

    private fun validateTotalSupplyTime(supply3ph: String, supply1ph: String): Pair<Boolean, String?> {
        if (supply3ph.isEmpty() && supply1ph.isEmpty()) return Pair(true, null)
        val min3 = if (supply3ph.isNotEmpty()) parseTimeToMinutes(supply3ph) else 0
        val min1 = if (supply1ph.isNotEmpty()) parseTimeToMinutes(supply1ph) else 0
        if (min3 > 1440) return Pair(false, "3PH Supply ($supply3ph) exceeds 24:00!\nMaximum allowed per field: 24:00")
        if (min1 > 1440) return Pair(false, "1PH Supply ($supply1ph) exceeds 24:00!\nMaximum allowed per field: 24:00")
        val total = min3 + min1
        if (total > 1440) {
            val fmt = { m: Int -> String.format("%02d:%02d", m / 60, m % 60) }
            return Pair(false, "3PH (${fmt(min3)}) + 1PH (${fmt(min1)}) = ${fmt(total)}\nTotal supply cannot exceed 24:00.")
        }
        return Pair(true, null)
    }

    private fun convertExcelTimeToHHMM(excelTime: String): String {
        if (excelTime.isEmpty()) return ""
        if (excelTime.contains(":")) return excelTime
        val decimal = excelTime.toDoubleOrNull() ?: return ""
        val totalMinutes = (decimal * 24 * 60).toInt()
        val h = totalMinutes / 60; val m = totalMinutes % 60
        if (h < 0 || h > 24 || m < 0 || m >= 60) return ""
        return String.format("%02d:%02d", h, m)
    }

    // ===============================
    // CELL VALUE READER
    // ===============================
    private fun getCellValueAsString(cell: org.apache.poi.ss.usermodel.Cell?): String {
        if (cell == null) return ""
        val effectiveType = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
        return when (effectiveType) {
            CellType.STRING  -> cell.stringCellValue.trim()
            CellType.NUMERIC -> {
                val n = cell.numericCellValue
                if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.BLANK   -> ""
            CellType.FORMULA -> try { cell.stringCellValue.trim() } catch (e: Exception) {
                try { val n = cell.numericCellValue; if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString() }
                catch (e2: Exception) { "" }
            }
            else -> ""
        }
    }

    // ===============================
    // UPLOAD HOURLY
    // ===============================
    private suspend fun uploadHourlyData(hourlyData: List<HourlyEntryData>, token: String): Int {
        if (hourlyData.isEmpty()) return 0
        val rowsArray = JSONArray()
        hourlyData.groupBy { "${it.date}_${it.feederName}" }.forEach { (_, entries) ->
            val first = entries.first()
            VALID_PARAMETERS.forEach { param ->
                val paramEntries = entries.filter { it.parameter == param }
                if (paramEntries.isNotEmpty()) {
                    val hoursObject = JSONObject()
                    paramEntries.forEach { e -> e.hours.forEach { (h, v) -> hoursObject.put(h, v) } }
                    rowsArray.put(JSONObject().apply {
                        put("date", first.date); put("feeder_code", first.feederCode)
                        put("feeder_name", first.feederName); put("feeder_category", first.feederCategory)
                        put("parameter", param); put("hours", hoursObject)
                    })
                }
            }
        }
        Log.d(TAG, "📤 Sending ${rowsArray.length()} hourly rows in ONE API call")
        val result = submitToHourlyAPI(token, JSONObject().put("rows", rowsArray))
        return if (result) hourlyData.size else throw Exception("Hourly upload failed. Please try again.")
    }

    private suspend fun submitToHourlyAPI(token: String, requestBody: JSONObject): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val result1 = makeHourlyRequest(token, requestBody)
            if (result1.success) return@withContext true
            if (result1.allowAutofill) {
                Log.d(TAG, "🔄 Server autofill requested — retrying after 2s...")
                kotlinx.coroutines.delay(2000)
                val result2 = makeHourlyRequest(token, requestBody)
                if (result2.success) return@withContext true
            }
            throw Exception(result1.errorMessage ?: "Hourly upload failed")
        }
    }

    private data class HourlyRequestResult(val success: Boolean, val allowAutofill: Boolean = false, val errorMessage: String? = null)

    private fun makeHourlyRequest(token: String, requestBody: JSONObject): HourlyRequestResult {
        val connection = (URL(HOURLY_SAVE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = TIMEOUT; readTimeout = TIMEOUT
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token"); doOutput = true
        }
        return try {
            OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()); it.flush() }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val resp = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                Log.d(TAG, "✅ Hourly Response: $resp")
                HourlyRequestResult(success = JSONObject(resp).optBoolean("success", false))
            } else {
                val err = try { BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() } } catch (e: Exception) { "" }
                Log.e(TAG, "❌ Hourly HTTP ${connection.responseCode}: $err")
                val json = try { JSONObject(err) } catch (e: Exception) { JSONObject() }
                HourlyRequestResult(success = false, allowAutofill = json.optBoolean("allow_autofill", false), errorMessage = parseErrorResponse(err))
            }
        } catch (e: Exception) {
            HourlyRequestResult(success = false, errorMessage = e.message)
        } finally { connection.disconnect() }
    }

    // ===============================
    // UPLOAD CONSUMPTION
    // ===============================
    private suspend fun uploadConsumptionData(consumptionData: List<ConsumptionEntryData>, date: String, token: String): Int {
        if (consumptionData.isEmpty()) return 0
        val rowsArray = JSONArray()
        consumptionData.forEach { e ->
            rowsArray.put(JSONObject().apply {
                put("date", e.date); put("station_name", e.stationName)
                put("feeder_name", e.feederName); put("feeder_code", e.feederCode)
                put("feeder_category", e.feederCategory)
                put("remark", e.remark.ifEmpty { "PROPER" })
                put("total_consumption", e.totalConsumption ?: 0.0)
                put("supply_3ph", e.supply3ph.ifEmpty { "00:00" })
                put("supply_1ph", e.supply1ph.ifEmpty { "00:00" })
            })
        }
        Log.d(TAG, "📤 Consumption: ${rowsArray.length()} rows")
        val result = submitToConsumptionAPI(token, JSONObject().apply { put("rows", rowsArray) })
        return if (result) rowsArray.length() else 0
    }

    private suspend fun submitToConsumptionAPI(token: String, requestBody: JSONObject): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val connection = (URL(CONSUMPTION_SAVE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = TIMEOUT; readTimeout = TIMEOUT
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token"); doOutput = true
            }
            try {
                OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()); it.flush() }
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val resp = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                    Log.d(TAG, "✅ Consumption Response: $resp")
                    JSONObject(resp).optBoolean("success", false)
                } else {
                    val err = try { BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() } } catch (e: Exception) { "" }
                    Log.e(TAG, "❌ Consumption HTTP ${connection.responseCode}: $err")
                    throw Exception(parseErrorResponse(err))
                }
            } finally { connection.disconnect() }
        }
    }

    // ===============================
    // ERROR PARSER
    // ===============================
    private fun parseErrorResponse(errorBody: String?): String {
        return try {
            if (errorBody.isNullOrEmpty()) return "Unknown error occurred"
            val json = JSONObject(errorBody)
            val message = json.optString("message", "").ifEmpty { return errorBody }
            var full = message
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                full += "\n\nValidation Errors:\n"
                for (i in 0 until errors.length()) {
                    val e = errors.getJSONObject(i)
                    full += "• ${e.optString("feeder","?")} [${e.optString("parameter","")}] Hour ${e.optString("hour","")}: ${e.optString("error","")}\n"
                }
            }
            json.optJSONObject("details")?.optString("rule","")?.let { if (it.isNotEmpty()) full += "\nRule: $it" }
            full
        } catch (e: Exception) { errorBody ?: "Error: ${e.message}" }
    }
}

// ===============================
// DATA CLASSES
// ===============================
data class ExcelData(
    val hourlyData: List<HourlyEntryData>,
    val consumptionData: List<ConsumptionEntryData>,
    val needsAutofill: Boolean = false,
    val emptyCellCount: Int = 0
)

data class HourlyEntryData(
    val date: String, val feederCode: String?, val feederName: String,
    val feederCategory: String, val parameter: String,
    val hours: Map<String, String>
)

data class ConsumptionEntryData(
    val date: String, val stationName: String, val feederName: String,
    val feederCode: String, val feederCategory: String, val remark: String,
    val totalConsumption: Double?, val supply3ph: String, val supply1ph: String
)

data class UploadResult(
    val hourlyCount: Int,
    val consumptionCount: Int,
    val needsAutofill: Boolean = false,
    val emptyCellCount: Int = 0,
    val isTodayUpload: Boolean = false,
    val upToHour: Int = -1
)