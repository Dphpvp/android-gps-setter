package io.github.jqssun.gpssetter.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder

data class RoutingWaypoint(
    val latitude: Double,
    val longitude: Double
)

data class RouteResponse(
    val waypoints: List<RoutingWaypoint>,
    val distance: Double, // in meters
    val duration: Double  // in seconds
)

class RoutingService {
    private val client = OkHttpClient()

    // Using OpenRouteService API (free tier: 2000 requests/day)
    // Alternative: you can also use MapBox, Google Directions, or OSRM
    private val baseUrl = "https://api.openrouteservice.org/v2/directions"

    // Free API key for OpenRouteService (you can get your own at openrouteservice.org)
    private val apiKey = "5b3ce3597851110001cf6248b6fbcad0d5974b2c9f8342b5947c95ee"

    suspend fun getRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        profile: String = "driving-car" // driving-car, cycling-regular, foot-walking
    ): RouteResponse? {
        return withContext(Dispatchers.IO) {
            try {
                // OpenRouteService expects coordinates as: longitude,latitude (note the order)
                val coordinates = "$startLon,$startLat|$endLon,$endLat"
                val url = "$baseUrl/$profile?" +
                        "api_key=$apiKey&" +
                        "coordinates=$coordinates&" +
                        "format=geojson"

                Timber.d("Making routing request to: $url")

                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                Timber.d("Routing response: ${response.code}, body length: ${responseBody?.length}")

                if (response.isSuccessful && responseBody != null) {
                    val result = parseRouteResponse(responseBody)
                    Timber.d("Parsed route with ${result?.waypoints?.size ?: 0} waypoints")
                    result
                } else {
                    Timber.e("Routing failed: ${response.code} - $responseBody")
                    // Fallback to straight line if routing fails
                    Timber.d("Using fallback straight line route")
                    createStraightLineRoute(startLat, startLon, endLat, endLon)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error getting route")
                // Fallback to straight line if routing fails
                createStraightLineRoute(startLat, startLon, endLat, endLon)
            }
        }
    }

    private fun parseRouteResponse(json: String): RouteResponse? {
        return try {
            val jsonObject = JSONObject(json)
            val features = jsonObject.getJSONArray("features")

            if (features.length() > 0) {
                val feature = features.getJSONObject(0)
                val geometry = feature.getJSONObject("geometry")
                val coordinates = geometry.getJSONArray("coordinates")
                val properties = feature.getJSONObject("properties")
                val summary = properties.getJSONObject("summary")

                val waypoints = mutableListOf<RoutingWaypoint>()

                for (i in 0 until coordinates.length()) {
                    val coord = coordinates.getJSONArray(i)
                    val lon = coord.getDouble(0)
                    val lat = coord.getDouble(1)
                    waypoints.add(RoutingWaypoint(lat, lon))
                }

                val distance = summary.getDouble("distance")
                val duration = summary.getDouble("duration")

                RouteResponse(waypoints, distance, duration)
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing route response")
            null
        }
    }

    private fun createStraightLineRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): RouteResponse {
        // Create a simple straight line route as fallback
        val waypoints = listOf(
            RoutingWaypoint(startLat, startLon),
            RoutingWaypoint(endLat, endLon)
        )

        // Calculate approximate distance using Haversine formula
        val distance = calculateDistance(startLat, startLon, endLat, endLon)
        val duration = distance / 13.89 // Assume ~50 km/h average speed

        return RouteResponse(waypoints, distance, duration)
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }

    // Get route profile based on navigation speed
    fun getProfileForSpeed(speedMps: Float): String {
        return when {
            speedMps <= 2.0f -> "foot-walking"      // Walking speed
            speedMps <= 8.0f -> "cycling-regular"   // Cycling speed
            else -> "driving-car"                    // Driving speed
        }
    }
}