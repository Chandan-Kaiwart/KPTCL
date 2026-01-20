package com.apc.kptcl.home.adapter

import android.util.Log
import kotlinx.coroutines.Dispatchers
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
 * Repository class for fetching feeder hourly data from API
 */
class FeederHourlyRepository {

    companion object {
        private const val TAG = "FeederHourlyRepository"
        private const val BASE_URL = "http://62.72.59.119:7000/api/feeder"
        private const val HOURLY_ENDPOINT = "$BASE_URL/hourly"
        private const val FEEDER_LIST_URL = "http://62.72.59.119:7000/api/feeder/list"
        private const val TIMEOUT = 15000
    }

    /**
     * Fetch all available feeders from the dedicated feeder list API
     */
    suspend fun fetchAllFeeders(token: String): Result<List<FeederInfo>> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching all feeders from feeder list API")

                val url = URL(FEEDER_LIST_URL)
                val connection = url.openConnection() as HttpURLConnection

                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT
                    readTimeout = TIMEOUT
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "Feeder list API response code: $responseCode")

                when (responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val response = BufferedReader(
                            InputStreamReader(connection.inputStream)
                        ).use { it.readText() }

                        Log.d(TAG, "Response: ${response.take(200)}...")
                        val feeders = parseFeederListResponse(response)
                        Log.d(TAG, "✅ Successfully parsed ${feeders.size} feeders")
                        Result.success(feeders)
                    }
                    401 -> {
                        Log.e(TAG, "Unauthorized - invalid token")
                        Result.failure(Exception("Unauthorized. Please login again."))
                    }
                    else -> {
                        val errorMessage = connection.errorStream?.let { stream ->
                            BufferedReader(InputStreamReader(stream)).use { it.readText() }
                        } ?: "Server Error: $responseCode"

                        Log.e(TAG, "API Error ($responseCode): $errorMessage")
                        Result.failure(Exception("Server error: $responseCode"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in fetchAllFeeders", e)
                Result.failure(e)
            }
        }

    /**
     * Parse feeder list API response
     */
    private fun parseFeederListResponse(jsonString: String): List<FeederInfo> {
        val feeders = mutableListOf<FeederInfo>()
        try {
            val jsonObject = JSONObject(jsonString)

            val success = jsonObject.optBoolean("success", false)
            if (!success) {
                throw Exception(jsonObject.optString("message", "Failed to fetch feeders"))
            }

            val station = jsonObject.optString("station", "")
            val dataArray = jsonObject.optJSONArray("data")

            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    val feederCode = item.optString("FEEDER_CODE", "")
                    val feederName = item.optString("FEEDER_NAME", "")

                    if (feederCode.isNotEmpty() && feederName.isNotEmpty()) {
                        feeders.add(
                            FeederInfo(
                                feederId = feederCode,  // Use FEEDER_CODE as ID
                                feederName = feederName,
                                stationName = station,
                                category = ""  // Not available in this API
                            )
                        )
                        Log.d(TAG, "Added feeder: $feederName ($feederCode)")
                    }
                }
            }

            // Return feeders sorted by name
            return feeders.sortedBy { it.feederName }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing feeder list", e)
            return emptyList()
        }
    }

    /**
     * Fetch hourly data for a specific feeder
     */
    suspend fun fetchFeederHourlyData(
        feederId: String,
        token: String,
        limit: Int = 24,
        date: String? = null
    ): Result<FeederHourlyResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching hourly data for feeder: $feederId")

            val url = URL(HOURLY_ENDPOINT)
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

            // Create request body
            val requestBody = JSONObject().apply {
                put("feeder_id", feederId)
                put("limit", limit)
                if (date != null) {
                    put("date", date)
                }
            }

            Log.d(TAG, "Request: $requestBody")

            // Send request
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode")

            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val response = BufferedReader(
                        InputStreamReader(connection.inputStream)
                    ).use { it.readText() }

                    Log.d(TAG, "Response received: ${response.take(200)}...")
                    val parsedData = parseHourlyDataResponse(response)
                    Log.d(TAG, "✅ Parsed ${parsedData.count} records")
                    Result.success(parsedData)
                }
                401 -> {
                    Log.e(TAG, "Unauthorized - invalid token")
                    Result.failure(Exception("Unauthorized. Please login again."))
                }
                else -> {
                    val errorMessage = connection.errorStream?.let { stream ->
                        BufferedReader(InputStreamReader(stream)).use { it.readText() }
                    } ?: "Server Error: $responseCode"

                    Log.e(TAG, "API Error ($responseCode): $errorMessage")
                    Result.failure(Exception("Server error: $responseCode"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in fetchFeederHourlyData", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch data for multiple feeders
     */
    suspend fun fetchMultipleFeederData(
        feederIds: List<String>,
        token: String,
        limit: Int = 24
    ): Map<String, Result<FeederHourlyResponse>> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Result<FeederHourlyResponse>>()

        feederIds.forEach { feederId ->
            results[feederId] = fetchFeederHourlyData(feederId, token, limit)
        }

        results
    }

    /**
     * Parse JSON response into FeederHourlyResponse object
     */
    private fun parseHourlyDataResponse(jsonString: String): FeederHourlyResponse {
        val jsonObject = JSONObject(jsonString)

        val success = jsonObject.optBoolean("success", false)
        val station = jsonObject.optString("station", "")
        val count = jsonObject.optInt("count", 0)
        val dataArray = jsonObject.optJSONArray("data") ?: return FeederHourlyResponse(
            success = success,
            station = station,
            count = 0,
            data = emptyList()
        )

        val dataList = mutableListOf<FeederHourlyData>()

        for (i in 0 until dataArray.length()) {
            val item = dataArray.getJSONObject(i)

            // Parse hourly values (00-23)
            val hourlyValues = mutableMapOf<String, Double?>()
            for (hour in 0..23) {
                val hourKey = String.format("%02d", hour)
                hourlyValues[hourKey] = if (item.isNull(hourKey)) {
                    null
                } else {
                    item.optDouble(hourKey, 0.0)
                }
            }

            // Parse and format date
            val dateString = item.optString("DATE", "")
            val formattedDate = formatDate(dateString)

            dataList.add(
                FeederHourlyData(
                    id = item.optString("ID", ""),
                    date = formattedDate,
                    stationName = item.optString("STATION_NAME", ""),
                    feederName = item.optString("FEEDER_NAME", ""),
                    feederCode = item.optString("FEEDER_CODE", ""),
                    feederCategory = item.optString("FEEDER_CATEGORY", ""),
                    parameter = item.optString("PARAMETER", ""),
                    hourlyValues = hourlyValues
                )
            )
        }

        Log.d(TAG, "Parsed ${dataList.size} records")

        return FeederHourlyResponse(
            success = success,
            station = station,
            count = count,
            data = dataList
        )
    }

    /**
     * Format ISO date string to dd-MM-yyyy
     */
    private fun formatDate(dateString: String): String {
        if (dateString.isEmpty()) return ""

        return try {
            // Try ISO format first
            var inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            inputFormat.timeZone = TimeZone.getTimeZone("UTC")

            var date = try {
                inputFormat.parse(dateString)
            } catch (e: Exception) {
                // Try simple date format
                inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                inputFormat.parse(dateString)
            }

            val outputFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
            date?.let { outputFormat.format(it) } ?: dateString
        } catch (e: Exception) {
            Log.w(TAG, "Date parsing error: ${e.message}")
            dateString
        }
    }
}

/**
 * Response wrapper for API response
 */
data class FeederHourlyResponse(
    val success: Boolean,
    val station: String,
    val count: Int,
    val data: List<FeederHourlyData>
)

/**
 * Data class for hourly feeder data
 */
data class FeederHourlyData(
    val id: String,
    val date: String,
    val stationName: String,
    val feederName: String,
    val feederCode: String,
    val feederCategory: String,
    val parameter: String, // MW, MVAR, IR, IB, IY
    val hourlyValues: Map<String, Double?> // Hour (00-23) to value
)

/**
 * Data class for feeder information
 */
data class FeederInfo(
    val feederId: String,
    val feederName: String,
    val stationName: String,
    val category: String
)

/**
 * Extension function to group data by parameter
 */
fun List<FeederHourlyData>.groupByParameter(): Map<String, List<FeederHourlyData>> {
    return this.groupBy { it.parameter }
}

/**
 * Extension function to get specific parameter data
 */
fun List<FeederHourlyData>.getParameterData(parameter: String): List<FeederHourlyData> {
    return this.filter { it.parameter == parameter }
}

/**
 * Extension function to calculate total/average for a specific hour
 */
fun List<FeederHourlyData>.getHourValue(hour: String): Double? {
    val hourKey = String.format("%02d", hour.toIntOrNull() ?: 0)
    return this.firstOrNull()?.hourlyValues?.get(hourKey)
}

/**
 * Extension function to get all non-null values for a specific hour across parameters
 */
fun List<FeederHourlyData>.getAllHourValues(hour: String): Map<String, Double> {
    val hourKey = String.format("%02d", hour.toIntOrNull() ?: 0)
    return this.mapNotNull { data ->
        data.hourlyValues[hourKey]?.let { value ->
            data.parameter to value
        }
    }.toMap()
}