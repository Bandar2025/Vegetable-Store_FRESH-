package com.example.data.api

import com.example.data.api.model.CategoryDto
import com.example.data.api.model.ProductDto
import com.example.data.api.model.OrderRequest
import com.example.data.api.model.OrderResponse
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

interface FreshApiService {
    @GET("categories/")
    suspend fun getCategories(): List<CategoryDto>

    @GET("products/")
    suspend fun getProducts(): List<ProductDto>

    @GET("products/{id}/")
    suspend fun getProduct(@Path("id") id: Int): ProductDto

    @POST("orders/")
    suspend fun createOrder(@Body request: OrderRequest): OrderResponse
}

object FreshRetrofitClient {
    // Note: In production, configure this via buildConfigField
    private const val BASE_URL = "https://api.fresh-market.sa/api/v1/"
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val service: FreshApiService by lazy {
        val json = Json { ignoreUnknownKeys = true }
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FreshApiService::class.java)
    }
}
