package com.apc.kptcl.home.users.upload

import ExcelUploadHandler
import UploadResult
import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentExcelDownloadBinding
import com.apc.kptcl.utils.ApiErrorHandler
import com.apc.kptcl.utils.SessionManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ExcelDownloadFragment : Fragment() {

    private var _binding: FragmentExcelDownloadBinding? = null
    private val binding get() = _binding!!

    private lateinit var excelGenerator: DynamicExcelGenerator
    private lateinit var excelUploadHandler: ExcelUploadHandler

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private var selectedDate: String = ""

    // File picker for Excel upload
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleSelectedFile(it) }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            downloadTemplate()
        } else {
            Toast.makeText(context, "Storage permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExcelDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!SessionManager.isLoggedIn(requireContext())) {
            Snackbar.make(binding.root, "Please login first", Snackbar.LENGTH_LONG).show()
            return
        }

        excelGenerator = DynamicExcelGenerator(requireContext())
        excelUploadHandler = ExcelUploadHandler(requireContext())
        selectedDate = dateFormat.format(calendar.time)

        setupViews()
        setupListeners()
        displayUserInfo()
    }

    private fun setupViews() {
        binding.btnSelectDate.text = "Selected Date: $selectedDate"
        binding.progressBar.visibility = View.GONE
    }

    private fun setupListeners() {
        binding.btnSelectDate.setOnClickListener {
            showDatePicker()
        }

        binding.btnDownload.setOnClickListener {
            checkPermissionAndDownload()
        }

        // ✅ NEW: Upload button listener
        binding.btnUpload.setOnClickListener {
            openFilePicker()
        }
    }

    private fun displayUserInfo() {
        val username = SessionManager.getUsername(requireContext())
        binding.tvStationName.text = username
        binding.tvUsername.text = "User: $username"
    }

    private fun showDatePicker() {
        val datePickerDialog = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                selectedDate = dateFormat.format(calendar.time)
                binding.btnSelectDate.text = "Selected Date: $selectedDate"
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        // Allow today — hourly upload up to current hour is valid
        datePickerDialog.datePicker.maxDate = Calendar.getInstance().timeInMillis

        datePickerDialog.show()
    }

    private fun checkPermissionAndDownload() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    downloadTemplate()
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:${requireContext().packageName}")
                    startActivity(intent)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                downloadTemplate()
            }
            else -> {
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    downloadTemplate()
                } else {
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private fun downloadTemplate() {
        binding.btnDownload.isEnabled = false
        binding.btnDownload.text = "Generating Excel..."
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    excelGenerator.generateTemplate(selectedDate)
                }

                if (result.isSuccess) {
                    val file = result.getOrNull()!!
                    openExcelFile(file)
                } else {
                    val error = result.exceptionOrNull()
                    showError("Failed to generate Excel. Please try again.")

                }

            } catch (e: Exception) {
                e.printStackTrace()
                showError(ApiErrorHandler.handle(e))
            } finally {
                binding.btnDownload.isEnabled = true
                binding.btnDownload.text = "Download Template"
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    // ✅ NEW: Open file picker for Excel upload
    private fun openFilePicker() {
        try {
            filePickerLauncher.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open file picker", Toast.LENGTH_SHORT).show()
        }
    }

    // Handle selected Excel file — with autofill dialog support
    private fun handleSelectedFile(uri: Uri, autofillApproved: Boolean = false) {
        binding.btnUpload.isEnabled = false
        binding.btnUpload.text = "Processing..."
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    excelUploadHandler.validateAndUpload(uri, selectedDate, autofillApproved)
                }

                if (result.isSuccess) {
                    val uploadResult = result.getOrNull()!!

                    if (uploadResult.needsAutofill) {
                        // Reset buttons before showing dialog
                        binding.btnUpload.isEnabled = true
                        binding.btnUpload.text = "Upload Excel"
                        binding.progressBar.visibility = View.GONE

                        val emptyCount = uploadResult.emptyCellCount
                        AlertDialog.Builder(requireContext())
                            .setTitle("⚠️ Incomplete Data")
                            .setMessage(
                                "There are $emptyCount empty cells.\n\n" +
                                        "Do you want to auto-fill all empty cells with 0?\n\n" +
                                        "• YES — Empty cells will be filled with 0, then uploaded.\n\n" +
                                        "• NO  — Cancel and fill manually in Excel."
                            )
                            .setPositiveButton("Yes, Autofill with 0") { _, _ ->
                                handleSelectedFile(uri, autofillApproved = true)
                            }
                            .setNegativeButton("No, Fill Manually") { dialog, _ ->
                                dialog.dismiss()
                            }
                            .setCancelable(false)
                            .show()
                        return@launch
                    }

                    showSuccessDialog(uploadResult)
                } else {
                    showError(result.exceptionOrNull()?.message ?: "Upload failed. Please try again.")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                showError(ApiErrorHandler.handle(e))
            } finally {
                if (binding.btnUpload.text == "Processing...") {
                    binding.btnUpload.isEnabled = true
                    binding.btnUpload.text = "Upload Excel"
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun openExcelFile(file: File) {
        try {
            var uri: Uri
            val intent = Intent(Intent.ACTION_VIEW)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.provider",
                        file
                    )
                    intent.setDataAndType(
                        uri,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    )
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } catch (e: Exception) {
                    uri = Uri.fromFile(file)
                    intent.setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                uri = Uri.fromFile(file)
                intent.setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val packageManager = requireContext().packageManager
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Toast.makeText(
                    requireContext(),
                    "✅ Excel Downloaded: ${file.name}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                try {
                    startActivity(Intent.createChooser(intent, "Open with"))
                    Toast.makeText(
                        requireContext(),
                        "✅ Excel Downloaded: ${file.name}",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    showNoAppFoundMessage(file)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            showNoAppFoundMessage(file)
        }
    }

    private fun showNoAppFoundMessage(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle("✅ Download Complete")
            .setMessage(
                """
                Excel file saved successfully!
                
                File: ${file.name}
                Location: Downloads folder
                Size: ${file.length() / 1024} KB
                
                ⚠️ No Excel app found on your device.
                Please install:
                • Microsoft Excel
                • Google Sheets
                • WPS Office
                """.trimIndent()
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // Success dialog — today = hourly only, previous = hourly + consumption
    private fun showSuccessDialog(uploadResult: UploadResult) {
        val message: String
        if (uploadResult.isTodayUpload) {
            val h = if (uploadResult.upToHour >= 0) String.format("%02d", uploadResult.upToHour) else "--"
            message = "Hourly data uploaded successfully!\n\n" +
                    "Date: $selectedDate (Today)\n" +
                    "Hours saved: 00:00 to " + h + ":59\n" +
                    "Records: ${uploadResult.hourlyCount}\n\n" +
                    "Consumption data for today will be\nsubmitted tomorrow."
        } else {
            message = "Data uploaded successfully!\n\n" +
                    "Hourly records: ${uploadResult.hourlyCount}\n" +
                    "Consumption records: ${uploadResult.consumptionCount}\n" +
                    "Total: ${uploadResult.hourlyCount + uploadResult.consumptionCount} entries"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("✅ Upload Successful")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showError(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("❌ Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}