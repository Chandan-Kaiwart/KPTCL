package com.apc.kptcl.home.users.ticket.confirmation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.databinding.ItemTicketApprovalBinding
import java.text.SimpleDateFormat
import java.util.*

class TicketApprovalAdapter(
    private val onApproveClick: (DCCApprovalFragment.PendingTicket, String?) -> Unit,
    private val onRejectClick: (DCCApprovalFragment.PendingTicket) -> Unit
) : ListAdapter<DCCApprovalFragment.PendingTicket, TicketApprovalAdapter.ViewHolder>(TicketDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTicketApprovalBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemTicketApprovalBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(ticket: DCCApprovalFragment.PendingTicket) {
            binding.apply {
                // Ticket ID
                tvTicketId.text = ticket.ticketId

                // Station
                tvStation.text = "Station: ${ticket.username}"

                // Classification
                tvClassification.text = ticket.ticketClassification

                // Problem Statement
                tvProblemStatement.text = ticket.problemStatement

                // ✅ Format date properly (remove ISO format with Z)
                tvDateTime.text = formatDateTime(ticket.startDateTime)

                // ✅ Display classification details
                displayClassificationDetails(ticket)

                // ✅ Show/Hide feeder code input based on classification
                val needsFeederCodeInput = ticket.ticketClassification.uppercase() == "FEEDER CODE" ||
                        ticket.ticketClassification.uppercase() == "NEW FEEDER ADDITION"

                if (needsFeederCodeInput) {
                    tilNewFeederCode.visibility = View.VISIBLE
                    tvFeederCodeNote.visibility = View.VISIBLE
                    tvFeederCodeNote.text = "⚠️ DCC: Enter new feeder code"
                } else {
                    tilNewFeederCode.visibility = View.GONE
                    tvFeederCodeNote.visibility = View.GONE
                }

                // ✅ Approve button
                btnApprove.setOnClickListener {
                    val newFeederCode = if (needsFeederCodeInput) {
                        etNewFeederCode.text.toString().trim()
                    } else {
                        null
                    }

                    // Validate feeder code if required
                    if (needsFeederCodeInput && newFeederCode.isNullOrBlank()) {
                        tilNewFeederCode.error = "Feeder code required"
                        return@setOnClickListener
                    }

                    tilNewFeederCode.error = null
                    onApproveClick(ticket, newFeederCode)
                }

                // ✅ Reject button
                btnReject.setOnClickListener {
                    onRejectClick(ticket)
                }
            }
        }

        /**
         * ✅ Format ISO datetime to readable format
         * Input: "2026-01-15T16:12:00.000Z"
         * Output: "15/01/2026 16:12"
         */
        private fun formatDateTime(isoDateTime: String): String {
            return try {
                // Parse ISO format
                val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                isoFormat.timeZone = TimeZone.getTimeZone("UTC")
                val date = isoFormat.parse(isoDateTime)

                // Format to readable
                val outputFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                date?.let { outputFormat.format(it) } ?: isoDateTime
            } catch (e: Exception) {
                // If parsing fails, try simpler format without milliseconds
                try {
                    val simpleFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                    simpleFormat.timeZone = TimeZone.getTimeZone("UTC")
                    val date = simpleFormat.parse(isoDateTime)

                    val outputFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    date?.let { outputFormat.format(it) } ?: isoDateTime
                } catch (e2: Exception) {
                    // If all fails, return original
                    isoDateTime
                }
            }
        }

        /**
         * ✅ Display parsed classification details
         */
        private fun displayClassificationDetails(ticket: DCCApprovalFragment.PendingTicket) {
            val details = ticket.detailsMap
            val detailsText = StringBuilder()

            when (ticket.ticketClassification.uppercase()) {
                "FEEDER CODE" -> {
                    val feederName = details["FEEDER_NAME"] ?: "N/A"
                    val oldCode = details["OLD_FEEDER_CODE"] ?: "N/A"
                    detailsText.append("Feeder: $feederName\n")
                    detailsText.append("Old Code: $oldCode\n")
                    detailsText.append("New Code: [DCC will assign]")
                }

                "FEEDER NAME" -> {
                    val oldName = details["OLD_FEEDER_NAME"] ?: "N/A"
                    val newName = details["NEW_FEEDER_NAME"] ?: "N/A"
                    detailsText.append("Old Name: $oldName\n")
                    detailsText.append("New Name: $newName")
                }

                "FEEDER CATEGORY" -> {
                    val feederName = details["FEEDER_NAME"] ?: "N/A"
                    val oldCategory = details["OLD_FEEDER_CATEGORY"] ?: "N/A"
                    val newCategory = details["NEW_FEEDER_CATEGORY"] ?: "N/A"
                    detailsText.append("Feeder: $feederName\n")
                    detailsText.append("Old Category: $oldCategory\n")
                    detailsText.append("New Category: $newCategory")
                }

                "FEEDER STATUS" -> {
                    val feederName = details["FEEDER_NAME"] ?: "N/A"
                    val oldStatus = details["OLD_STATUS"] ?: "N/A"
                    val newStatus = details["NEW_STATUS"] ?: "N/A"
                    detailsText.append("Feeder: $feederName\n")
                    detailsText.append("Old Status: $oldStatus\n")
                    detailsText.append("New Status: $newStatus")
                }

                "NEW FEEDER ADDITION" -> {
                    val newName = details["NEW_FEEDER_NAME"] ?: "N/A"
                    val newCategory = details["NEW_FEEDER_CATEGORY"] ?: "N/A"
                    detailsText.append("New Feeder: $newName\n")
                    detailsText.append("Category: $newCategory\n")
                    detailsText.append("Code: [DCC will assign]")
                }

                "GENERAL TICKET" -> {
                    detailsText.append("General issue - see problem statement")
                }

                else -> {
                    detailsText.append(ticket.classificationDetails)
                }
            }

            binding.tvDetails.text = detailsText.toString()
        }
    }

    class TicketDiffCallback : DiffUtil.ItemCallback<DCCApprovalFragment.PendingTicket>() {
        override fun areItemsTheSame(
            oldItem: DCCApprovalFragment.PendingTicket,
            newItem: DCCApprovalFragment.PendingTicket
        ): Boolean {
            return oldItem.ticketId == newItem.ticketId
        }

        override fun areContentsTheSame(
            oldItem: DCCApprovalFragment.PendingTicket,
            newItem: DCCApprovalFragment.PendingTicket
        ): Boolean {
            return oldItem == newItem
        }
    }
}