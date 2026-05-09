package com.ongrid.app

import android.app.Application
import com.ongrid.app.data.network.OllamaApi
import com.ongrid.app.data.network.McpApi
import com.ongrid.app.data.network.NetworkScanner
import com.ongrid.app.data.repository.McpRepository
import com.ongrid.app.data.repository.OllamaRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class OnGridApplication : Application() {

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val ollamaApi: OllamaApi by lazy { OllamaApi(httpClient) }
    val mcpApi: McpApi by lazy { McpApi(httpClient) }
    val networkScanner: NetworkScanner by lazy { NetworkScanner(httpClient) }
    val ollamaRepository: OllamaRepository by lazy { OllamaRepository(ollamaApi) }
    val mcpRepository: McpRepository by lazy { McpRepository(mcpApi, this) }
}
