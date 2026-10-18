package com.motogps.android.location

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class NetworkClient(private val gatewayUrl: String) {
    private val client = OkHttpClient()

    fun searchDestination(query: String, lat: Double, lon: Double): Result<JSONObject> {
        if (gatewayUrl.isBlank()) {
            return Result.failure(Exception("Gateway URL is empty"))
        }

        val url = "${gatewayUrl.trimEnd('/')}/search?q=$query&lat=$lat&lon=$lon"
        val request = Request.Builder().url(url).build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(IOException("Unexpected code $response"))
                } else {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        Result.success(JSONObject(responseBody))
                    } else {
                        Result.failure(Exception("Empty response body"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun requestRoute(originLat: Double, originLon: Double, destId: String): Result<JSONObject> {
         if (gatewayUrl.isBlank()) {
            return Result.failure(Exception("Gateway URL is empty"))
        }

        val url = "${gatewayUrl.trimEnd('/')}/route?originLat=$originLat&originLon=$originLon&destId=$destId"
        val request = Request.Builder().url(url).build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(IOException("Unexpected code $response"))
                } else {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        Result.success(JSONObject(responseBody))
                    } else {
                        Result.failure(Exception("Empty response body"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
