package io.github.jqssun.gpssetter.utils

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.*

@AndroidEntryPoint
class NavigationService : Service() {

    @Inject
    lateinit var notificationsChannel: NotificationsChannel

    private var serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var navigationJob: Job? = null

    private val _currentRoute = MutableStateFlow<NavigationRoute?>(null)
    val currentRoute: StateFlow<NavigationRoute?> = _currentRoute.asStateFlow()

    private val _navigationState = MutableStateFlow(NavigationState.STOPPED)
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val _navigationProgress = MutableStateFlow<NavigationProgress?>(null)
    val navigationProgress: StateFlow<NavigationProgress?> = _navigationProgress.asStateFlow()

    private val _currentPosition = MutableStateFlow<RoutePoint?>(null)
    val currentPosition: StateFlow<RoutePoint?> = _currentPosition.asStateFlow()

    inner class NavigationBinder : Binder() {
        fun getService(): NavigationService = this@NavigationService
    }

    override fun onBind(intent: Intent): IBinder {
        return NavigationBinder()
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("NavigationService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNavigation()
        serviceScope.cancel()
        Timber.d("NavigationService destroyed")
    }

    fun startNavigation(route: NavigationRoute) {
        Timber.d("Starting navigation for route: ${route.name}")

        stopNavigation() // Stop any existing navigation

        _currentRoute.value = route.copy(isActive = true)
        _navigationState.value = NavigationState.RUNNING

        startForeground(NAVIGATION_NOTIFICATION_ID, createNavigationNotification(route))

        navigationJob = serviceScope.launch {
            simulateRoute(route)
        }
    }

    fun pauseNavigation() {
        if (_navigationState.value == NavigationState.RUNNING) {
            _navigationState.value = NavigationState.PAUSED
            navigationJob?.cancel()
        }
    }

    fun resumeNavigation() {
        if (_navigationState.value == NavigationState.PAUSED) {
            _currentRoute.value?.let { route ->
                _navigationState.value = NavigationState.RUNNING
                navigationJob = serviceScope.launch {
                    simulateRoute(route, _navigationProgress.value?.progressPercentage ?: 0f)
                }
            }
        }
    }

    fun stopNavigation() {
        navigationJob?.cancel()
        _navigationState.value = NavigationState.STOPPED
        _currentRoute.value = null
        _navigationProgress.value = null
        _currentPosition.value = null
        stopForeground(true)
    }

    private suspend fun simulateRoute(route: NavigationRoute, startProgress: Float = 0f) {
        val totalDistance = calculateDistance(route.startPoint, route.endPoint)
        val totalSteps = (totalDistance / (route.speed * 0.05)).toInt().coerceAtLeast(20) // More steps for smoother movement
        val stepDelay = 50L // Update every 50ms for very smooth movement

        var currentStep = (startProgress * totalSteps).toInt()
        val startTime = System.currentTimeMillis() - (startProgress * (route.duration.takeIf { it > 0 } ?: (totalDistance / route.speed * 1000).toLong())).toLong()

        do {
            while (currentStep < totalSteps && _navigationState.value == NavigationState.RUNNING) {
                val progress = currentStep.toFloat() / totalSteps
                val currentPos = interpolatePosition(route.startPoint, route.endPoint, progress)

                _currentPosition.value = currentPos

                val elapsedTime = System.currentTimeMillis() - startTime
                val estimatedTotalTime = if (route.duration > 0) route.duration else (totalDistance / route.speed * 1000).toLong()
                val remainingTime = (estimatedTotalTime - elapsedTime).coerceAtLeast(0)

                _navigationProgress.value = NavigationProgress(
                    currentPosition = currentPos,
                    progressPercentage = progress * 100f,
                    elapsedTime = elapsedTime,
                    remainingTime = remainingTime
                )

                // Check if duration limit is reached
                if (route.duration > 0 && elapsedTime >= route.duration) {
                    break
                }

                delay(stepDelay)
                currentStep++
            }

            // If repeating and not stopped
            if (route.isRepeating && _navigationState.value == NavigationState.RUNNING) {
                currentStep = 0
                delay(1000) // Small pause before repeating
            }

        } while (route.isRepeating && _navigationState.value == NavigationState.RUNNING)

        // Navigation completed
        if (_navigationState.value == NavigationState.RUNNING) {
            Timber.d("Navigation completed")
            stopNavigation()
        }
    }

    private fun interpolatePosition(start: RoutePoint, end: RoutePoint, progress: Float): RoutePoint {
        val lat = start.latitude + (end.latitude - start.latitude) * progress
        val lon = start.longitude + (end.longitude - start.longitude) * progress
        return RoutePoint(lat, lon, "Current Position")
    }

    private fun calculateDistance(start: RoutePoint, end: RoutePoint): Double {
        val earthRadius = 6371000.0 // Earth's radius in meters

        val lat1Rad = Math.toRadians(start.latitude)
        val lat2Rad = Math.toRadians(end.latitude)
        val deltaLatRad = Math.toRadians(end.latitude - start.latitude)
        val deltaLonRad = Math.toRadians(end.longitude - start.longitude)

        val a = sin(deltaLatRad / 2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(deltaLonRad / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    private fun createNavigationNotification(route: NavigationRoute): Notification {
        return notificationsChannel.showNotification(this) { builder ->
            builder.setContentTitle("Auto Navigation Active")
                .setContentText("Route: ${route.name}")
                .setSmallIcon(R.drawable.ic_play)
                .setOngoing(true)
        }
    }

    companion object {
        private const val NAVIGATION_NOTIFICATION_ID = 1001
    }
}