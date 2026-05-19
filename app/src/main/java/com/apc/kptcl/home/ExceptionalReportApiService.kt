package com.apc.kptcl.home

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

// ==========================================
// API SERVICE INTERFACE
// ==========================================

interface ExceptionalReportApiService {

    @GET("api/feeder/exceptional-report")
    suspend fun getAllReports(
        @Header("Authorization") token: String
    ): Response<ExceptionalReportResponse>

    @GET("api/feeder/exceptional-report/date/{date}")
    suspend fun getReportsByDate(
        @Header("Authorization") token: String,
        @Path("date") date: String
    ): Response<ExceptionalReportResponse>

    @GET("api/feeder/exceptional-report/filter")
    suspend fun getFilteredReports(
        @Header("Authorization") token: String,
        @Query("date") date: String? = null,
        @Query("station") station: String? = null,
        @Query("feeder_code") feederCode: String? = null,
        @Query("missing_date") missingDate: String? = null
    ): Response<ExceptionalReportResponse>

    // ✅ FIXED: Separate response type for validator summary
    @GET("api/dcc/validator/summary")
    suspend fun getValidatorSummary(
        @Header("Authorization") token: String,
        @Query("date") date: String
    ): Response<ValidatorApiResponse>  // ← ValidatorApiResponse, NOT ExceptionalReportResponse
}

// ==========================================
// EXCEPTIONAL REPORT — Response & Item
// ==========================================

data class ExceptionalReportResponse(
    val success: Boolean,
    val escom: String? = null,   // nullable — validator summary mein nahi aata
    val count: Int,
    val data: List<ExceptionalReportItem>
)

data class ExceptionalReportItem(
    val DBNAME: String? = null,
    val TABLENAME: String? = null,
    val STATION_NAME: String,
    val FEEDER_NAME: String? = null,
    val FEEDER_CODE: String? = null,
    val DATE: String,
    val MISSINGDATE: String? = null,
    val MISSINGHOUR: String? = null,
    val MISSINGPARAMETERINEACHHOUR: String? = null
)

// ==========================================
// VALIDATOR SUMMARY — Response & Item
// (fdms_app_station_status se aata hai)
// ==========================================

data class ValidatorApiResponse(
    val success: Boolean,
    val count: Int,
    val data: List<StationStatusItem>
)

data class StationStatusItem(
    val STATION_NAME: String,
    val DATE: String,
    val HOURLY_STATUS: String,   // FILLED / PARTIAL DATA / MISSING
    val DAILY_STATUS: String     // FILLED / PARTIAL DATA / MISSING
)