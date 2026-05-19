package com.apc.kptcl.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class AppUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val APK_DOWNLOAD_URL = "https://api.vidyut-suvidha.in/apk"
        private const val APK_FILE_NAME = "KPTCL_App_Latest.apk"
        private const val DOWNLOAD_TIMEOUT = 30000
        private const val AUTHORITY = "com.apc.kptcl.fileprovider"

        // SharedPreferences to store last checked update time
        private const val PREF_NAME = "app_update_prefs"
        private const val KEY_LAST_APK_MODIFIED = "last_apk_modified"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
    }

    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    private var updateCallback: UpdateCheckCallback? = null

    /**
     * Callback interface for update status
     */
    interface UpdateCheckCallback {
        fun onUpdateAvailable(updateInfo: UpdateInfo)
        fun onUpdateNotAvailable()
        fun onCheckError(error: String)
        fun onDownloadProgress(progress: Int)
        fun onDownloadComplete()
        fun onDownloadFailed(error: String)
    }

    /**
     * Data class for update information
     */
    data class UpdateInfo(
        val apkLastModified: Long,
        val apkLastModifiedDate: String,
        val currentAppInstallDate: String,
        val isUpdateAvailable: Boolean,
        val apkUrl: String,
        val apkSize: Long,
        val message: String
    )

    /**
     * Check for updates based on APK last modified date
     */
    suspend fun checkForUpdates(callback: UpdateCheckCallback): UpdateInfo? {
        updateCallback = callback
        return withContext(Dispatchers.IO) {
            try {
                val updateInfo = checkAPKLastModified()

                if (updateInfo != null) {
                    if (updateInfo.isUpdateAvailable) {
                        Log.d(TAG, "Update available - APK modified: ${updateInfo.apkLastModifiedDate}")
                        withContext(Dispatchers.Main) {
                            callback.onUpdateAvailable(updateInfo)
                        }
                    } else {
                        Log.d(TAG, "App is up to date")
                        withContext(Dispatchers.Main) {
                            callback.onUpdateNotAvailable()
                        }
                    }
                }
                updateInfo
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback.onCheckError(e.message ?: "Unknown error")
                }
                null
            }
        }
    }

    /**
     * Check APK file's Last-Modified header to determine if update is available
     */
    private suspend fun checkAPKLastModified(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(APK_DOWNLOAD_URL)
                val connection = url.openConnection() as HttpURLConnection

                // Use HEAD request to get headers without downloading the file
                connection.requestMethod = "HEAD"
                connection.connectTimeout = DOWNLOAD_TIMEOUT
                connection.readTimeout = DOWNLOAD_TIMEOUT

                val responseCode = connection.responseCode
                Log.d(TAG, "APK check response code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Get Last-Modified header (timestamp when APK was last uploaded)
                    val lastModifiedHeader = connection.getHeaderField("Last-Modified")
                    val contentLength = connection.getHeaderFieldLong("Content-Length", 0L)

                    Log.d(TAG, "APK Last-Modified: $lastModifiedHeader")
                    Log.d(TAG, "APK Size: ${contentLength / 1024 / 1024} MB")

                    // Parse Last-Modified header
                    val apkLastModified = if (lastModifiedHeader != null) {
                        try {
                            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                            sdf.timeZone = TimeZone.getTimeZone("GMT")
                            sdf.parse(lastModifiedHeader)?.time ?: System.currentTimeMillis()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing Last-Modified header: ${e.message}")
                            System.currentTimeMillis()
                        }
                    } else {
                        System.currentTimeMillis()
                    }

                    // Get stored last modified time
                    val storedLastModified = getStoredAPKModifiedTime()

                    // Get current app installation time
                    val appInstallTime = getAppInstallTime()

                    Log.d(TAG, "Stored APK Modified: ${formatDate(storedLastModified)}")
                    Log.d(TAG, "Server APK Modified: ${formatDate(apkLastModified)}")
                    Log.d(TAG, "App Install Time: ${formatDate(appInstallTime)}")

                    // Update is available if:
                    // 1. Server APK is newer than stored time OR
                    // 2. This is first check (storedLastModified == 0) AND server APK is newer than app install time
                    val isUpdateAvailable = if (storedLastModified == 0L) {
                        // First check - compare with app install time
                        apkLastModified > appInstallTime
                    } else {
                        // Subsequent checks - compare with stored time
                        apkLastModified > storedLastModified
                    }

                    // Store the current APK modified time for future checks
                    if (isUpdateAvailable) {
                        storeAPKModifiedTime(apkLastModified)
                    }

                    UpdateInfo(
                        apkLastModified = apkLastModified,
                        apkLastModifiedDate = formatDate(apkLastModified),
                        currentAppInstallDate = formatDate(appInstallTime),
                        isUpdateAvailable = isUpdateAvailable,
                        apkUrl = APK_DOWNLOAD_URL,
                        apkSize = contentLength,
                        message = if (isUpdateAvailable) {
                            "New update available (uploaded on ${formatDate(apkLastModified)})"
                        } else {
                            "Your app is up to date"
                        }
                    )
                } else {
                    Log.e(TAG, "Failed to check APK: $responseCode")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during APK check: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Get app installation time
     */
    private fun getAppInstallTime(): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.firstInstallTime
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app install time: ${e.message}")
            0L
        }
    }

    /**
     * Store APK last modified time in SharedPreferences
     */
    private fun storeAPKModifiedTime(time: Long) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putLong(KEY_LAST_APK_MODIFIED, time)
                putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "Stored APK modified time: ${formatDate(time)}")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing APK modified time: ${e.message}")
        }
    }

    /**
     * Get stored APK last modified time from SharedPreferences
     */
    private fun getStoredAPKModifiedTime(): Long {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.getLong(KEY_LAST_APK_MODIFIED, 0L)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting stored APK modified time: ${e.message}")
            0L
        }
    }

    /**
     * Format timestamp to readable date
     */
    private fun formatDate(timestamp: Long): String {
        return try {
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            sdf.format(Date(timestamp))
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Download APK with auto-install
     */
    fun downloadAndInstallAPK(updateInfo: UpdateInfo? = null) {
        // Check permissions before download
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Log.w(TAG, "Install permission not granted")
                updateCallback?.onDownloadFailed("Install permission required")
                return
            }
        }

        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(APK_DOWNLOAD_URL)

            val request = DownloadManager.Request(uri).apply {
                setTitle("KPTCL App Update")
                setDescription("Downloading latest version...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
                setMimeType("application/vnd.android.package-archive")
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                setAllowedOverRoaming(true)
            }

            downloadId = downloadManager.enqueue(request)
            Log.d(TAG, "APK download started with ID: $downloadId")

            // Setup receiver for download completion
            setupDownloadReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting download: ${e.message}", e)
            updateCallback?.onDownloadFailed(e.message ?: "Download failed")
        }
    }

    /**
     * Setup broadcast receiver for download completion
     */
    private fun setupDownloadReceiver() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)

                if (id == downloadId) {
                    Log.d(TAG, "Download completed: $id")
                    updateCallback?.onDownloadComplete()

                    // Auto-install the APK
                    try {
                        installAPK()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error installing APK: ${e.message}", e)
                        updateCallback?.onDownloadFailed("Installation failed: ${e.message}")
                    }
                }
            }
        }

        try {
            val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(downloadReceiver, intentFilter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(downloadReceiver, intentFilter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering download receiver: ${e.message}", e)
        }
    }

    /**
     * Install APK automatically
     */
    private fun installAPK() {
        try {
            val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)

            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found: ${apkFile.absolutePath}")
                updateCallback?.onDownloadFailed("APK file not found")
                return
            }

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, AUTHORITY, apkFile)
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            Log.d(TAG, "Starting APK installation from: ${apkFile.absolutePath}")
            context.startActivity(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK: ${e.message}", e)
            updateCallback?.onDownloadFailed(e.message ?: "Installation failed")
        }
    }

    /**
     * Get current app version from PackageManager
     */
    fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app version: ${e.message}", e)
            "1.0"
        }
    }

    /**
     * Get device unique ID
     */
    private fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device ID: ${e.message}", e)
            "unknown"
        }
    }

    /**
     * Cancel ongoing download
     */
    fun cancelDownload() {
        if (downloadId != -1L) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.remove(downloadId)
            Log.d(TAG, "Download cancelled: $downloadId")
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
                Log.d(TAG, "Download receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver: ${e.message}")
            }
        }
        downloadReceiver = null
    }

    /**
     * Check if a download is in progress
     */
    fun isDownloadInProgress(): Boolean = downloadId != -1L

    /**
     * Reset stored update information (for testing)
     */
    fun resetUpdateCheck() {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            Log.d(TAG, "Update check data reset")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting update check: ${e.message}")
        }
    }
}