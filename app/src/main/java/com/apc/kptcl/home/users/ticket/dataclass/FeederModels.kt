package com.apc.kptcl.home.users.ticket.dataclass


data class FeederListResponse(
    val success: Boolean,
    val username: String?,
    val escom: String?,
    val count: Int,
    val data: List<FeederItem>
)

data class FeederItem(
    val FEEDER_NAME: String,
    val FEEDER_CODE: String?,
    val FEEDER_CATEGORY: String,
    val STATION_NAME: String
)

data class FeederData(
    val name: String,
    val code: String?,
    val category: String,
    var confirmed: Boolean
)