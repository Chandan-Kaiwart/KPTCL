package com.apc.kptcl.home

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentLoginBinding
import com.apc.kptcl.utils.SessionManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private var currentCaptcha = ""
    private var isCaptchaVerified = false
    private var isPasswordVisible = false

    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null

    // Download progress dialog components
    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var tvDownloadProgress: TextView? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private val escomList = listOf(
        "BESCOM",
        "HESCOM",
        "GESCOM",
        "MESCOM",
        "CESC"
    )

    companion object {
        private const val TAG = "LoginFragment"
        private const val BASE_URL = "http://62.72.59.119:7000"
        private const val APK_DOWNLOAD_URL = "https://api.vidyut-suvidha.in/apk"
        private const val APK_FILE_NAME = "KPTCL_App_Latest.apk"
    }

    // ✅ Permission launcher for storage access (Android < 13)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startAPKDownload()
        } else {
            Toast.makeText(context, "Storage permission required to download APK", Toast.LENGTH_LONG).show()
        }
    }

    // ✅ Install permission launcher (Android 8+)
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // After user grants install permission, check again
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (requireContext().packageManager.canRequestPackageInstalls()) {
                Log.d(TAG, "Install permission granted")
            } else {
                Toast.makeText(context, "Install permission required to update app", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupEscomDropdown()
        generateCaptcha()
        setupClickListeners()
        checkExistingSession()
        setupDownloadReceiver()
    }

    private fun checkExistingSession() {
        if (SessionManager.isLoggedIn(requireContext())) {
            val username = SessionManager.getUsername(requireContext())
            val escom = SessionManager.getEscom(requireContext())
            val stationName = SessionManager.getStationName(requireContext())

            Log.d(TAG, "User already logged in: $username - Auto navigating to welcome screen")

            try {
                val bundle = Bundle().apply {
                    putString("username", username)
                    putString("escom", escom)
                    putString("station_name", stationName)
                }
                findNavController().navigate(R.id.action_loginFragment_to_welcomeFragment, bundle)
            } catch (e: Exception) {
                Log.e(TAG, "Navigation error to welcome: ${e.message}", e)
            }
        }
    }

    private fun setupEscomDropdown() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            escomList
        )
        binding.actvEscom.setAdapter(adapter)

        binding.actvEscom.setOnClickListener {
            binding.actvEscom.showDropDown()
        }

        binding.actvEscom.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.actvEscom.showDropDown()
            }
        }

        binding.actvEscom.threshold = 1
    }

    private fun generateCaptcha() {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        currentCaptcha = (1..6)
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")

        binding.tvCaptcha.text = currentCaptcha.toCharArray().joinToString(" ")

        isCaptchaVerified = false
        binding.ivVerified.visibility = View.GONE
        binding.tvSuccessMessage.visibility = View.GONE
        binding.etCaptcha.text?.clear()

        binding.cardCaptcha.strokeColor = ContextCompat.getColor(requireContext(), android.R.color.transparent)

        Log.d(TAG, "Generated captcha: $currentCaptcha")
    }

    private fun setupClickListeners() {
        binding.etCaptcha.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.postDelayed({
                    var parent = view.parent
                    while (parent != null && parent !is android.widget.ScrollView) {
                        parent = parent.parent
                    }
                    (parent as? android.widget.ScrollView)?.apply {
                        smoothScrollTo(0, view.bottom + 200)
                    }
                }, 300)
            }
        }

        binding.btnTogglePassword.setOnClickListener {
            togglePasswordVisibility()
        }

        binding.btnRefreshCaptcha.setOnClickListener {
            generateCaptcha()
        }

        binding.btnVerify.setOnClickListener {
            verifyCaptcha()
        }

        binding.btnLogin.setOnClickListener {
            attemptLogin()
        }

        // ✅ APK Download Button
        binding.apkDownload.setOnClickListener {
            downloadLatestAPK()
        }
    }

    // ✅ APK Download Function
    private fun downloadLatestAPK() {
        Log.d(TAG, "📥 APK Download button clicked")

        // Show confirmation dialog
        AlertDialog.Builder(requireContext())
            .setTitle("Download Latest APK")
            .setMessage("Do you want to download and install the latest version of KPTCL App?")
            .setPositiveButton("Download") { _, _ ->
                checkPermissionsAndDownload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPermissionsAndDownload() {
        when {
            // ✅ Android 13+ (API 33+) - No storage permission needed
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                Log.d(TAG, "Android 13+: No storage permission needed")
                startAPKDownload()
            }

            // ✅ Android 11-12 (API 30-32) - Scoped storage, no permission needed
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                Log.d(TAG, "Android 11-12: Using scoped storage")
                startAPKDownload()
            }

            // ✅ Android 6-10 (API 23-29) - Need WRITE_EXTERNAL_STORAGE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    startAPKDownload()
                } else {
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }

            // ✅ Android 5 and below - Direct download
            else -> {
                startAPKDownload()
            }
        }
    }

    private fun startAPKDownload() {
        try {
            Log.d(TAG, "🚀 Starting APK download from: $APK_DOWNLOAD_URL")

            // Delete old APK if exists
            val apkFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                APK_FILE_NAME
            )
            if (apkFile.exists()) {
                apkFile.delete()
                Log.d(TAG, "🗑️ Deleted old APK file")
            }

            // Configure download request
            val request = DownloadManager.Request(Uri.parse(APK_DOWNLOAD_URL)).apply {
                setTitle("KPTCL App Update")
                setDescription("Downloading latest version...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
                setMimeType("application/vnd.android.package-archive")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            // Start download
            val downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)

            Log.d(TAG, "✅ Download started with ID: $downloadId")

            // Show progress dialog
            showDownloadProgressDialog()

            // Start monitoring download progress
            startProgressMonitoring()

            // Disable button during download
            binding.apkDownload.isEnabled = false
            binding.apkDownload.text = "Downloading..."

        } catch (e: Exception) {
            Log.e(TAG, "❌ Download error: ${e.message}", e)
            Toast.makeText(
                context,
                "Download failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showDownloadProgressDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_download_progress, null)

        progressBar = dialogView.findViewById(R.id.progressBar)
        tvDownloadProgress = dialogView.findViewById(R.id.tvDownloadProgress)
        val btnCancelDownload = dialogView.findViewById<Button>(R.id.btnCancelDownload)

        progressDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancelDownload.setOnClickListener {
            cancelDownload()
        }

        progressDialog?.show()
    }

    private fun startProgressMonitoring() {
        val downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        progressRunnable = object : Runnable {
            override fun run() {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)

                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                    val bytesDownloaded = cursor.getLong(bytesDownloadedIndex)
                    val bytesTotal = cursor.getLong(bytesTotalIndex)
                    val status = cursor.getInt(statusIndex)

                    if (bytesTotal > 0) {
                        val progress = ((bytesDownloaded * 100L) / bytesTotal).toInt()

                        progressBar?.progress = progress

                        val downloadedMB = bytesDownloaded / (1024.0 * 1024.0)
                        val totalMB = bytesTotal / (1024.0 * 1024.0)

                        tvDownloadProgress?.text = String.format(
                            "%.2f MB / %.2f MB (%d%%)",
                            downloadedMB,
                            totalMB,
                            progress
                        )

                        Log.d(TAG, "Download progress: $progress% - $downloadedMB MB / $totalMB MB")
                    } else {
                        tvDownloadProgress?.text = "Preparing download..."
                    }

                    // Check if download is complete or failed
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            Log.d(TAG, "Download completed successfully")
                            dismissProgressDialog()
                            cursor.close()
                            return
                        }
                        DownloadManager.STATUS_FAILED -> {
                            Log.e(TAG, "Download failed")
                            dismissProgressDialog()
                            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                            resetDownloadButton()
                            cursor.close()
                            return
                        }
                    }

                    cursor.close()

                    // Continue monitoring
                    progressHandler.postDelayed(this, 500)
                }
            }
        }

        progressHandler.post(progressRunnable!!)
    }

    private fun cancelDownload() {
        val downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.remove(downloadId)

        dismissProgressDialog()
        resetDownloadButton()

        Toast.makeText(context, "Download cancelled", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Download cancelled by user")
    }

    private fun dismissProgressDialog() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun resetDownloadButton() {
        binding.apkDownload.isEnabled = true
        binding.apkDownload.text = "Click Here To Download Latest APK"
    }

    private fun setupDownloadReceiver() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1

                if (id == downloadId) {
                    Log.d(TAG, "✅ Download completed for ID: $downloadId")

                    // Dismiss progress dialog
                    dismissProgressDialog()

                    // Re-enable button
                    resetDownloadButton()

                    Toast.makeText(
                        context,
                        "✅ APK Downloaded! Installing...",
                        Toast.LENGTH_LONG
                    ).show()

                    // Install APK
                    installAPK()
                }
            }
        }

        // Register receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            requireContext().registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun installAPK() {
        try {
            val apkFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                APK_FILE_NAME
            )

            if (!apkFile.exists()) {
                Log.e(TAG, "❌ APK file not found: ${apkFile.absolutePath}")
                Toast.makeText(context, "APK file not found", Toast.LENGTH_SHORT).show()
                return
            }

            Log.d(TAG, "📦 Installing APK from: ${apkFile.absolutePath}")

            // ✅ Check install permission for Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!requireContext().packageManager.canRequestPackageInstalls()) {
                    Log.w(TAG, "⚠️ Install permission not granted, requesting...")

                    // Request install permission
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    installPermissionLauncher.launch(intent)
                    return
                }
            }

            // ✅ Install APK
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7+ (Use FileProvider)
                FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.provider",
                    apkFile
                )
            } else {
                // Android 6 and below
                Uri.fromFile(apkFile)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(installIntent)
            Log.d(TAG, "✅ Install intent launched")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Installation error: ${e.message}", e)
            Toast.makeText(
                context,
                "Installation failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible

        if (isPasswordVisible) {
            binding.etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            binding.btnTogglePassword.setImageResource(R.drawable.ic_visibility)
        } else {
            binding.etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.btnTogglePassword.setImageResource(R.drawable.ic_visibility_off)
        }

        binding.etPassword.setSelection(binding.etPassword.text?.length ?: 0)
    }

    private fun verifyCaptcha() {
        val enteredCaptcha = binding.etCaptcha.text.toString().uppercase().trim()
        Log.d(TAG, "Verifying captcha. Entered: $enteredCaptcha, Expected: $currentCaptcha")

        if (enteredCaptcha == currentCaptcha) {
            isCaptchaVerified = true
            binding.ivVerified.visibility = View.VISIBLE
            binding.tvSuccessMessage.visibility = View.VISIBLE

            binding.cardCaptcha.strokeWidth = 4
            binding.cardCaptcha.strokeColor = ContextCompat.getColor(requireContext(), R.color.green_500)

            Log.d(TAG, "Captcha verified successfully")

        } else {
            isCaptchaVerified = false
            Snackbar.make(binding.root, "Invalid captcha. Please try again.", Snackbar.LENGTH_SHORT).show()
            Log.w(TAG, "Invalid captcha entered")
            generateCaptcha()
        }
    }

    private fun attemptLogin() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val escom = binding.actvEscom.text.toString().trim()

        Log.d(TAG, "Attempting login - Username: $username, ESCOM: $escom")

        var hasError = false

        when {
            username.isEmpty() -> {
                Snackbar.make(binding.root, "Username is required", Snackbar.LENGTH_SHORT).show()
                binding.etUsername.requestFocus()
                Log.w(TAG, "Username is empty")
                hasError = true
            }
            password.isEmpty() -> {
                Snackbar.make(binding.root, "Password is required", Snackbar.LENGTH_SHORT).show()
                binding.etPassword.requestFocus()
                Log.w(TAG, "Password is empty")
                hasError = true
            }
            escom.isEmpty() -> {
                Snackbar.make(binding.root, "Please select ESCOM", Snackbar.LENGTH_SHORT).show()
                binding.actvEscom.requestFocus()
                Log.w(TAG, "ESCOM not selected")
                hasError = true
            }
            !isCaptchaVerified -> {
                Snackbar.make(binding.root, "Please verify captcha first", Snackbar.LENGTH_SHORT).show()
                Log.w(TAG, "Captcha not verified")
                hasError = true
            }
        }

        if (hasError) return

        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = "Logging in..."

        performLoginAPI(username, password, escom)
    }

    private fun performLoginAPI(username: String, password: String, escom: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val loginUrl = "$BASE_URL/api/login"
                Log.d(TAG, "Calling login API: $loginUrl")

                val url = URL(loginUrl)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true
                connection.doInput = true
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val jsonBody = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                    put("escom", escom)
                }

                Log.d(TAG, "Login request body: $jsonBody")

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(jsonBody.toString())
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                Log.d(TAG, "Login response code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?

                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val responseText = response.toString()
                    Log.d(TAG, "Login response: $responseText")

                    val jsonResponse = JSONObject(responseText)

                    withContext(Dispatchers.Main) {
                        handleLoginResponse(jsonResponse, username, escom)
                    }
                } else {
                    val errorStream = connection.errorStream
                    val errorMessage = if (errorStream != null) {
                        val errorReader = BufferedReader(InputStreamReader(errorStream))
                        val errorResponse = StringBuilder()
                        var line: String?
                        while (errorReader.readLine().also { line = it } != null) {
                            errorResponse.append(line)
                        }
                        errorReader.close()
                        errorResponse.toString()
                    } else {
                        "Server Error: $responseCode"
                    }

                    Log.e(TAG, "Login error response: $errorMessage")

                    withContext(Dispatchers.Main) {
                        binding.btnLogin.isEnabled = true
                        binding.btnLogin.text = "Login"

                        try {
                            val errorJson = JSONObject(errorMessage)
                            val message = errorJson.optString("message", "Login failed")
                            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Snackbar.make(binding.root, "Error: $errorMessage", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }

                connection.disconnect()

            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "Exception during login: ${e.message}", e)

                withContext(Dispatchers.Main) {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = "Login"

                    Snackbar.make(
                        binding.root,
                        "Error: ${e.message ?: "Network error occurred"}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun handleLoginResponse(jsonResponse: JSONObject, username: String, escom: String) {
        val success = jsonResponse.optBoolean("success", false)
        val message = jsonResponse.optString("message", "")

        Log.d(TAG, "Login response - Success: $success, Message: $message")

        if (success) {
            val token = jsonResponse.optString("token", "")
            val data = jsonResponse.optJSONObject("data")

            Log.d(TAG, "Token received: ${if (token.isNotEmpty()) "Yes" else "No"}")

            val userId = data?.optInt("user_id", -1) ?: -1
            val stationName = data?.optString("station_name", "") ?: ""
            val dbName = data?.optString("db_name", "") ?: ""

            if (token.isEmpty()) {
                Log.e(TAG, "Token is empty in response")
                Snackbar.make(binding.root, "Login failed: No token received", Snackbar.LENGTH_LONG).show()
                binding.btnLogin.isEnabled = true
                binding.btnLogin.text = "Login"
                return
            }

            SessionManager.saveLoginData(
                requireContext(),
                userId = userId,
                username = username,
                stationName = stationName,
                dbName = dbName,
                escom = escom,
                token = token,
                serverUrl = BASE_URL
            )

            Log.d(TAG, "Login successful - Token saved, navigating to welcome")

            Snackbar.make(binding.root, message.ifEmpty { "Login successful" }, Snackbar.LENGTH_SHORT).show()

            navigateToWelcome(username, escom, stationName)

        } else {
            Log.w(TAG, "Login failed: $message")
            binding.btnLogin.isEnabled = true
            binding.btnLogin.text = "Login"
            Snackbar.make(binding.root, message.ifEmpty { "Login failed" }, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun navigateToWelcome(username: String, escom: String, stationName: String) {
        try {
            val bundle = Bundle().apply {
                putString("username", username)
                putString("escom", escom)
                putString("station_name", stationName)
            }
            Log.d(TAG, "Navigating to welcome screen")
            findNavController().navigate(R.id.action_loginFragment_to_welcomeFragment, bundle)
        } catch (e: Exception) {
            Log.e(TAG, "Navigation error: ${e.message}", e)
            Snackbar.make(binding.root, "Navigation error: ${e.message}", Snackbar.LENGTH_LONG).show()
            binding.btnLogin.isEnabled = true
            binding.btnLogin.text = "Login"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Dismiss progress dialog if showing
        dismissProgressDialog()

        // ✅ Unregister download receiver
        try {
            downloadReceiver?.let {
                requireContext().unregisterReceiver(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }

        _binding = null
    }
}