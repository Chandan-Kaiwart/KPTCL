package com.apc.kptcl.home.users.ticket.dataclass

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.apc.kptcl.R
import java.text.SimpleDateFormat
import java.util.Locale

class TicketsAdapter(
    private val onTicketClick: (Ticket) -> Unit
) : ListAdapter<Ticket, TicketsAdapter.TicketViewHolder>(TicketDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TicketViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ticket_row, parent, false)
        return TicketViewHolder(view)
    }

    override fun onBindViewHolder(holder: TicketViewHolder, position: Int) {
        val ticket = getItem(position)
        holder.bind(ticket, onTicketClick)
    }

    class TicketViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvUsername: TextView = itemView.findViewById(R.id.tvUsername)
        private val tvDepartment: TextView = itemView.findViewById(R.id.tvDepartment)
        private val tvEmail: TextView = itemView.findViewById(R.id.tvEmail)
        private val tvMobile: TextView = itemView.findViewById(R.id.tvMobile)
        private val tvClassification: TextView = itemView.findViewById(R.id.tvClassification)
        private val tvProblem: TextView = itemView.findViewById(R.id.tvProblem)
        private val tvStartDate: TextView = itemView.findViewById(R.id.tvStartDate)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)

        fun bind(ticket: Ticket, onTicketClick: (Ticket) -> Unit) {
            tvUsername.text = ticket.username ?: "-"
            tvDepartment.text = ticket.userDepartment ?: "-"
            tvEmail.text = ticket.emailId ?: "-"
            tvMobile.text = ticket.mobileNumber ?: "-"
            tvClassification.text = ticket.ticketClassification ?: "-"
            tvProblem.text = ticket.problemStatement ?: "-"

            // Format date
            val formattedDate = formatDate(ticket.startDatetime)
            tvStartDate.text = formattedDate

            // Set status with color
            tvStatus.text = getStatusText(ticket.ticketStatus)
            tvStatus.setTextColor(getStatusColor(ticket.ticketStatus))

            itemView.setOnClickListener {
                onTicketClick(ticket)
            }
        }

        private fun formatDate(dateString: String?): String {
            if (dateString.isNullOrEmpty()) return "-"
            return try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                val outputFormat = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
                val date = inputFormat.parse(dateString)
                date?.let { outputFormat.format(it) } ?: dateString
            } catch (e: Exception) {
                dateString.substring(0, 10) // Fallback to date portion
            }
        }

        private fun getStatusText(status: String?): String {
            return when (status?.uppercase()) {
                "OPEN" -> "OPEN"
                "APPROVED & CLOSED" -> "CLOSED"
                "PENDING" -> "PENDING"
                "REJECTED" -> "REJECTED"
                else -> status ?: "-"
            }
        }

        private fun getStatusColor(status: String?): Int {
            return when (status?.uppercase()) {
                "OPEN" -> ContextCompat.getColor(itemView.context, R.color.warning_yellow)
                "APPROVED & CLOSED" -> ContextCompat.getColor(itemView.context, R.color.success_green)
                "PENDING" -> ContextCompat.getColor(itemView.context, R.color.warning_orange)
                "REJECTED" -> ContextCompat.getColor(itemView.context, R.color.error_red)
                else -> ContextCompat.getColor(itemView.context, R.color.text_secondary)
            }
        }
    }

    class TicketDiffCallback : DiffUtil.ItemCallback<Ticket>() {
        override fun areItemsTheSame(oldItem: Ticket, newItem: Ticket): Boolean {
            return oldItem.ticketId == newItem.ticketId
        }

        override fun areContentsTheSame(oldItem: Ticket, newItem: Ticket): Boolean {
            return oldItem == newItem
        }
    }
}