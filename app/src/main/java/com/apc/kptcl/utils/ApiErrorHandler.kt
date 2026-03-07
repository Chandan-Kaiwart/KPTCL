package com.apc.kptcl.utils

import android.util.Log
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * ✅ CENTRALIZED API ERROR HANDLER
 *
 * Usage in any Fragment:
 *
 *   // 1. Parse server error body (when HTTP != 200):
 *   val msg = ApiErrorHandler.parseServerError(errorBodyString)
 *   showError(msg)
 *
 *   // 2. Handle network/exception errors:
 *   val msg = ApiErrorHandler.getExceptionMessage(e)
 *   showError(msg)
 *
 *   // 3. One-liner for the full catch block pattern:
 *   showError(ApiErrorHandler.handle(e))
 */
object ApiErrorHandler {

    private const val TAG = "ApiErrorHandler"

    // ─────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────

    /**
     * Call this inside every catch(e: Exception) block.
     * Converts ANY exception into a clean, user-friendly message.
     * Never exposes IP addresses or raw stack traces.
     */
    fun handle(e: Exception): String {
        Log.e(TAG, "Exception caught: ${e.javaClass.simpleName} - ${e.message}")
        return getExceptionMessage(e)
    }

    /**
     * Call this when HTTP response code != 200.
     * Parses the error body JSON from server and returns a clean message.
     *
     * @param errorBody  Raw string from connection.errorStream
     * @param httpCode   The HTTP status code (400, 401, 404, 500, etc.)
     */
    fun parseServerError(errorBody: String?, httpCode: Int = 0): String {
        Log.e(TAG, "Server error $httpCode: $errorBody")

        // Try to extract "message" field from server JSON response
        if (!errorBody.isNullOrBlank()) {
            try {
                val json = JSONObject(errorBody)
                val message = json.optString("message", "").trim()

                if (message.isNotEmpty()) {
                    // Append validation error details if present
                    val errorsArray = json.optJSONArray("errors")
                    if (errorsArray != null && errorsArray.length() > 0) {
                        val details = StringBuilder()
                        for (i in 0 until errorsArray.length()) {
                            val err = errorsArray.getJSONObject(i)
                            // Hourly validation errors have feeder/parameter/hour/error fields
                            val feeder = err.optString("feeder", "")
                            val param  = err.optString("parameter", "")
                            val hour   = err.optString("hour", "")
                            val errMsg = err.optString("error",
                                err.optString("message", ""))

                            details.append("\n• ")
                            if (feeder.isNotEmpty()) details.append("Feeder $feeder")
                            if (param.isNotEmpty())  details.append(" [$param]")
                            if (hour.isNotEmpty())   details.append(" Hour $hour")
                            if (errMsg.isNotEmpty()) details.append(": $errMsg")
                        }
                        return "$message$details"
                    }

                    return message
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse error body as JSON")
            }
        }

        // Fallback: use HTTP status code to give a meaningful message
        return getHttpStatusMessage(httpCode)
    }

    /**
     * One-liner to read errorStream from HttpURLConnection and return clean message.
     *
     * Usage:
     *   val msg = ApiErrorHandler.fromErrorStream(connection.errorStream, connection.responseCode)
     */
    fun fromErrorStream(errorStream: java.io.InputStream?, httpCode: Int): String {
        val body = try {
            errorStream?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        return parseServerError(body, httpCode)
    }

    // ─────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────

    private fun getExceptionMessage(e: Exception): String {
        return when (e) {
            // No internet / server unreachable
            is UnknownHostException ->
                "Unable to reach server. Please check your internet connection."

            is ConnectException ->
                "Cannot connect to server. Please check your network and try again."

            // Timeout
            is SocketTimeoutException ->
                "Request timed out. The server is taking too long to respond. Please try again."

            // SSL/TLS issues
            is SSLException ->
                "Secure connection failed. Please try again or contact support."

            // JSON parsing went wrong on our side
            is org.json.JSONException ->
                "Received unexpected data from server. Please try again."

            else -> {
                // For any other exception, give a generic message.
                // NEVER expose e.message directly as it may contain IP address.
                "Something went wrong. Please try again."
            }
        }
    }

    private fun getHttpStatusMessage(code: Int): String {
        return when (code) {
            400 -> "Invalid request. Please check your input and try again."
            401 -> "Session expired. Please login again."
            403 -> "You don't have permission to perform this action."
            404 -> "Requested data not found."
            409 -> "This data already exists. Duplicate entry detected."
            422 -> "Invalid data submitted. Please check all fields."
            429 -> "Too many requests. Please wait a moment and try again."
            500 -> "Server error. Please try again later or contact support."
            502, 503 -> "Server is currently unavailable. Please try again later."
            504 -> "Server response timeout. Please try again."
            0   -> "Unable to connect. Please check your internet connection."
            else -> "An error occurred (code: $code). Please try again."
        }
    }
}