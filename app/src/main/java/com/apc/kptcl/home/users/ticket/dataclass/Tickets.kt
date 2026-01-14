package com.apc.kptcl.home.users.ticket.dataclass

import com.google.gson.annotations.SerializedName

data class TicketResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("username") val username: String?,
    @SerializedName("escom") val escom: String?,
    @SerializedName("count") val count: Int,
    @SerializedName("data") val data: List<Ticket>
)

data class Ticket(
    @SerializedName("USERNAME") val username: String?,
    @SerializedName("USER_DEPARTMENT") val userDepartment: String?,
    @SerializedName("EMAIL_ID") val emailId: String?,
    @SerializedName("MOBILE_NUMBER") val mobileNumber: String?,
    @SerializedName("TICKET_CLASSIFICATION") val ticketClassification: String?,
    @SerializedName("PROBLEM_STATEMENT") val problemStatement: String?,
    @SerializedName("START_DATETIME") val startDatetime: String?,
    @SerializedName("END_DATETIME") val endDatetime: String?,
    @SerializedName("RESOLUTION_PROVIDED") val resolutionProvided: String?,
    @SerializedName("TICKET_STATUS") val ticketStatus: String?,
    @SerializedName("ATTACHMENT_NAME") val attachmentName: String?,
    @SerializedName("ATTACHMENT") val attachment: String?,
    @SerializedName("TICKET_ID") val ticketId: String?,
    @SerializedName("CLASSIFICATION_DETAILS") val classificationDetails: String?,
    @SerializedName("FEEDER_NAME") val feederName: String?,
    @SerializedName("FEEDER_CATEGORY") val feederCategory: String?,
    @SerializedName("DETAILS_DICT") val detailsDict: String?,
    @SerializedName("ESCOM") val escom: String?,
    @SerializedName("STATUS") val status: String?,
    @SerializedName("ZONE") val zone: String?,
    @SerializedName("CIRCLE") val circle: String?,
    @SerializedName("DIVISION") val division: String?,
    @SerializedName("DISTRICT") val district: String?
)
