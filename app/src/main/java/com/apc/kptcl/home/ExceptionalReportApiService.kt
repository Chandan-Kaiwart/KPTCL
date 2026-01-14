package com.apc.kptcl.home



import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * API Service for Exceptional Reports
 */
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
}

/**
 * Response data class
 */
data class ExceptionalReportResponse(
    val success: Boolean,
    val escom: String,
    val count: Int,
    val data: List<ExceptionalReportItem>
)

/**
 * Individual report item
 */
data class ExceptionalReportItem(
    val DBNAME: String,
    val TABLENAME: String,
    val STATION_NAME: String,
    val FEEDER_NAME: String,
    val FEEDER_CODE: String,
    val DATE: String,
    val MISSINGDATE: String?,
    val MISSINGHOUR: String?,
    val MISSINGPARAMETERINEACHHOUR: String?
)