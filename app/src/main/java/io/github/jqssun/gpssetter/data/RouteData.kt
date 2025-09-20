package io.github.jqssun.gpssetter.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val name: String = ""
) : Parcelable

@Parcelize
data class NavigationRoute(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val startPoint: RoutePoint,
    val endPoint: RoutePoint,
    val waypoints: List<RoutePoint> = emptyList(),
    val isRepeating: Boolean = false,
    val speed: Float = 5.0f, // meters per second
    val duration: Long = 0L, // milliseconds, 0 means no time limit
    val isActive: Boolean = false
) : Parcelable

enum class NavigationSpeed(val value: Float, val displayName: String) {
    WALKING(1.4f, "Walking (5 km/h)"),
    CYCLING(5.6f, "Cycling (20 km/h)"),
    DRIVING_SLOW(13.9f, "Driving Slow (50 km/h)"),
    DRIVING_NORMAL(22.2f, "Driving Normal (80 km/h)"),
    DRIVING_FAST(33.3f, "Driving Fast (120 km/h)"),
    CUSTOM(5.0f, "Custom")
}

enum class NavigationState {
    STOPPED,
    RUNNING,
    PAUSED
}

@Parcelize
data class NavigationProgress(
    val currentPosition: RoutePoint,
    val progressPercentage: Float,
    val elapsedTime: Long,
    val remainingTime: Long
) : Parcelable