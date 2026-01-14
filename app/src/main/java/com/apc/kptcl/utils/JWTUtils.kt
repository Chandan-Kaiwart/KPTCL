package com.apc.kptcl.utils

import android.util.Base64
import android.util.Log
import org.json.JSONObject

object JWTUtils {
    private const val TAG = "JWTUtils"

    /**
     * Decode JWT token and extract payload
     */
    fun decodeToken(token: String): TokenPayload? {
        try {
            // JWT format: header.payload.signature
            val parts = token.split(".")

            if (parts.size != 3) {
                Log.e(TAG, "Invalid JWT token format")
                return null
            }

            // Decode the payload (second part)
            val payloadJson = parts[1]

            // Base64 decode
            val decodedBytes = Base64.decode(payloadJson, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val decodedString = String(decodedBytes, Charsets.UTF_8)

            Log.d(TAG, "Decoded JWT payload: $decodedString")

            // Parse JSON
            val jsonObject = JSONObject(decodedString)

            return TokenPayload(
                username = jsonObject.optString("username", ""),
                role = jsonObject.optString("role", ""),
                escom = jsonObject.optString("escom", ""),
                dbHost = jsonObject.optString("db_host", ""),
                database = jsonObject.optString("database", ""),
                authDatabase = jsonObject.optString("auth_database", ""),
                stationDatabase = jsonObject.optString("station_database", ""),
                iat = jsonObject.optLong("iat", 0L),
                exp = jsonObject.optLong("exp", 0L)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding JWT token", e)
            return null
        }
    }

    /**
     * Check if token is expired
     */
    fun isTokenExpired(token: String): Boolean {
        val payload = decodeToken(token) ?: return true
        val currentTime = System.currentTimeMillis() / 1000 // Convert to seconds
        return currentTime > payload.exp
    }

    /**
     * Get token expiry time in readable format
     */
    fun getExpiryTime(token: String): String {
        val payload = decodeToken(token) ?: return "Unknown"
        val expiryTime = payload.exp * 1000 // Convert to milliseconds
        val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
        return dateFormat.format(java.util.Date(expiryTime))
    }

    /**
     * Get time remaining until expiry
     */
    fun getTimeRemaining(token: String): String {
        val payload = decodeToken(token) ?: return "Unknown"
        val currentTime = System.currentTimeMillis() / 1000
        val remainingSeconds = payload.exp - currentTime

        if (remainingSeconds <= 0) {
            return "Expired"
        }

        val hours = remainingSeconds / 3600
        val minutes = (remainingSeconds % 3600) / 60

        return "${hours}h ${minutes}m"
    }
}

/**
 * Data class to hold decoded JWT payload
 */
data class TokenPayload(
    val username: String,
    val role: String,
    val escom: String,
    val dbHost: String,
    val database: String,
    val authDatabase: String = "",
    val stationDatabase: String = "",
    val iat: Long, // Issued at (timestamp)
    val exp: Long  // Expiry (timestamp)
)