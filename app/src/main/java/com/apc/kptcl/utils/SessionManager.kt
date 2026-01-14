package com.apc.kptcl.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object SessionManager {

    private const val PREF_NAME = "user_session"  // Use single consistent preference name
    private const val TAG = "SessionManager"

    // Keys
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"
    private const val KEY_STATION_NAME = "station_name"
    private const val KEY_DB_NAME = "db_name"
    private const val KEY_ESCOM = "escom"
    private const val KEY_TOKEN = "token"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_LOGIN_TIMESTAMP = "login_timestamp"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Check if user is logged in
     */
    fun isLoggedIn(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_IS_LOGGED_IN, false)
    }

    /**
     * Save user login data
     */
    fun saveLoginData(
        context: Context,
        userId: Int,
        username: String,
        stationName: String,
        dbName: String,
        escom: String,
        token: String,
        serverUrl: String
    ) {
        with(getPreferences(context).edit()) {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putInt(KEY_USER_ID, userId)
            putString(KEY_USERNAME, username)
            putString(KEY_STATION_NAME, stationName)
            putString(KEY_DB_NAME, dbName)
            putString(KEY_ESCOM, escom)
            putString(KEY_TOKEN, token)
            putString(KEY_SERVER_URL, serverUrl)
            putLong(KEY_LOGIN_TIMESTAMP, System.currentTimeMillis())
            apply()
        }
        Log.d(TAG, "Login data saved for user: $username with server: $serverUrl")
    }

    /**
     * Get JWT token
     */
    fun getToken(context: Context): String {
        return getPreferences(context).getString(KEY_TOKEN, "") ?: ""
    }

    /**
     * Get user ID
     */
    fun getUserId(context: Context): Int {
        return getPreferences(context).getInt(KEY_USER_ID, -1)
    }

    /**
     * Get username
     */
    fun getUsername(context: Context): String {
        return getPreferences(context).getString(KEY_USERNAME, "") ?: ""
    }

    /**
     * Get station name
     */
    fun getStationName(context: Context): String {
        return getPreferences(context).getString(KEY_STATION_NAME, "") ?: ""
    }

    /**
     * Get database name
     */
    fun getDbName(context: Context): String {
        return getPreferences(context).getString(KEY_DB_NAME, "") ?: ""
    }

    /**
     * Get ESCOM
     */
    fun getEscom(context: Context): String {
        return getPreferences(context).getString(KEY_ESCOM, "") ?: ""
    }


    /**
     * Get server URL
     */
    fun getServerUrl(context: Context): String {
        return getPreferences(context).getString(KEY_SERVER_URL, "") ?: ""
    }
    /**
     * Get login timestamp
     */
    fun getLoginTimestamp(context: Context): Long {
        return getPreferences(context).getLong(KEY_LOGIN_TIMESTAMP, 0L)
    }

    /**
     * Logout user - Clear all session data
     */
    fun logout(context: Context) {
        val username = getUsername(context)
        with(getPreferences(context).edit()) {
            clear()
            apply()
        }
        Log.d(TAG, "User logged out: $username")
    }

    /**
     * Get all user data as a map
     */
    fun getUserData(context: Context): Map<String, Any> {
        return mapOf(
            "userId" to getUserId(context),
            "username" to getUsername(context),
            "stationName" to getStationName(context),
            "dbName" to getDbName(context),
            "escom" to getEscom(context),
            "token" to getToken(context),
            "serverUrl" to getServerUrl(context),
            "loginTimestamp" to getLoginTimestamp(context),
            "isLoggedIn" to isLoggedIn(context)
        )
    }
}