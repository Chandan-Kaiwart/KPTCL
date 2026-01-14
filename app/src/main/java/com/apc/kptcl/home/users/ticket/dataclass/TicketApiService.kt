package com.apc.kptcl.home.users.ticket.dataclass

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface TicketApiService {

    @GET("api/feeder/ticket/view")
    suspend fun getTickets(
        @Header("Authorization") token: String
    ): Response<TicketResponse>

    @POST("api/feeder/ticket/create")
    suspend fun createTicket(
        @Header("Authorization") token: String,
        @Body request: CreateTicketRequest
    ): Response<CreateTicketResponse>
}