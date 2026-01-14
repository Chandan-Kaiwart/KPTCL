package com.apc.kptcl.home.users.ticket.dataclass

import com.google.gson.annotations.SerializedName

/**
 * ✅ UPDATED: Request model for creating feeder tickets
 * Matches portal's expected data structure
 */
data class CreateTicketRequest(
    @SerializedName("ticket_classification")
    val ticketClassification: String,

    @SerializedName("problem_statement")
    val problemStatement: String,

    @SerializedName("feeder_name")
    val feederName: String? = null,

    @SerializedName("feeder_category")
    val feederCategory: String? = null,

    @SerializedName("feeder_code")
    val feederCode: String? = null,  // ✅ NEW: For FEEDER CODE classification

    @SerializedName("email_id")
    val emailId: String,

    @SerializedName("mobile_number")
    val mobileNumber: String,

    @SerializedName("user_department")
    val userDepartment: String,

    @SerializedName("attachment_name")
    val attachmentName: String? = null,

    @SerializedName("attachment")
    val attachment: String? = null,

    @SerializedName("resolution_provided")
    val resolutionProvided: String? = null,

    // ✅ NEW: Fields for different classification types
    @SerializedName("old_feeder_name")
    val oldFeederName: String? = null,  // For FEEDER NAME classification

    @SerializedName("new_feeder_name")
    val newFeederName: String? = null,  // For FEEDER NAME & NEW FEEDER ADDITION

    @SerializedName("new_feeder_category")
    val newFeederCategory: String? = null,  // For FEEDER CATEGORY & NEW FEEDER ADDITION

    @SerializedName("new_status")
    val newStatus: String? = null  // For FEEDER STATUS classification

    // ✅ REMOVED: classificationDetails - API builds this server-side
    // ✅ REMOVED: detailsDict - not needed
    // ✅ REMOVED: status - API sets this automatically
)

/**
 * Response model for ticket creation
 */
data class CreateTicketResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String?,

    @SerializedName("ticket_id")
    val ticketId: String?,

    @SerializedName("ticket_status")
    val ticketStatus: String?,  // Should be "ACTIVE"

    @SerializedName("start_datetime")
    val startDatetime: String?,  // Format: "YYYY-MM-DD HH:MM"

    @SerializedName("classification_details")
    val classificationDetails: String?
)