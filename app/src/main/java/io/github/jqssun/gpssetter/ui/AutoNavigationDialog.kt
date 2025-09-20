package io.github.jqssun.gpssetter.ui

import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.data.*
import io.github.jqssun.gpssetter.databinding.AutoNavigationDialogBinding
import io.github.jqssun.gpssetter.databinding.NavigationControlDialogBinding
import io.github.jqssun.gpssetter.utils.NavigationService
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

class AutoNavigationDialog(
    private val context: BaseMapActivity,
    private val currentLat: Double,
    private val currentLon: Double
) {

    private var navigationService: NavigationService? = null
    private var serviceBound = false
    private var setupDialog: AlertDialog? = null
    private var controlDialog: AlertDialog? = null
    private var isSelectingStartPoint = false
    private var isSelectingEndPoint = false
    private var dialogBinding: AutoNavigationDialogBinding? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NavigationService.NavigationBinder
            navigationService = binder.getService()
            serviceBound = true
            observeNavigationState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            navigationService = null
            serviceBound = false
        }
    }

    fun show() {
        bindNavigationService()
        showSetupDialog()
    }

    private fun bindNavigationService() {
        if (!serviceBound) {
            val intent = Intent(context, NavigationService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindNavigationService() {
        if (serviceBound) {
            context.unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun showSetupDialog() {
        val binding = AutoNavigationDialogBinding.inflate(LayoutInflater.from(context))
        dialogBinding = binding

        // Pre-fill current location as start point
        binding.startLatInput.setText(currentLat.toString())
        binding.startLonInput.setText(currentLon.toString())

        // Setup speed spinner
        val speedOptions = NavigationSpeed.values().map { it.displayName }
        val speedAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, speedOptions)
        binding.speedSpinner.setAdapter(speedAdapter)
        binding.speedSpinner.setText(NavigationSpeed.WALKING.displayName, false)

        // Handle speed selection
        binding.speedSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedSpeed = NavigationSpeed.values()[position]
            if (selectedSpeed == NavigationSpeed.CUSTOM) {
                binding.customSpeedLayout.visibility = View.VISIBLE
            } else {
                binding.customSpeedLayout.visibility = View.GONE
            }
        }

        // Use current location buttons
        binding.useCurrentStartBtn.setOnClickListener {
            binding.startLatInput.setText(currentLat.toString())
            binding.startLonInput.setText(currentLon.toString())
        }

        binding.useCurrentEndBtn.setOnClickListener {
            binding.endLatInput.setText(currentLat.toString())
            binding.endLonInput.setText(currentLon.toString())
        }

        // Select start point on map
        binding.selectStartOnMapBtn.setOnClickListener {
            isSelectingStartPoint = true
            isSelectingEndPoint = false
            setupDialog?.hide() // Hide dialog to show map
            context.showToast("Tap on the map to select start point")
            context.setMapClickMode(true) { lat, lon ->
                binding.startLatInput.setText(lat.toString())
                binding.startLonInput.setText(lon.toString())
                isSelectingStartPoint = false
                context.setMapClickMode(false, null)
                context.showToast("Start point selected")
                if (!context.isFinishing && !context.isDestroyed) {
                    setupDialog?.show() // Show dialog again
                }
            }
        }

        // Select end point on map
        binding.selectEndOnMapBtn.setOnClickListener {
            isSelectingStartPoint = false
            isSelectingEndPoint = true
            setupDialog?.hide() // Hide dialog to show map
            context.showToast("Tap on the map to select end point")
            context.setMapClickMode(true) { lat, lon ->
                binding.endLatInput.setText(lat.toString())
                binding.endLonInput.setText(lon.toString())
                isSelectingEndPoint = false
                context.setMapClickMode(false, null)
                context.showToast("End point selected")
                if (!context.isFinishing && !context.isDestroyed) {
                    setupDialog?.show() // Show dialog again
                }
            }
        }

        // Create dialog
        setupDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.auto_navigation)
            .setView(binding.root)
            .setCancelable(true)
            .setOnCancelListener {
                // Clean up if user cancels while in selection mode
                context.setMapClickMode(false, null)
                isSelectingStartPoint = false
                isSelectingEndPoint = false
            }
            .create()

        // Handle buttons
        binding.cancelBtn.setOnClickListener {
            context.setMapClickMode(false, null)
            isSelectingStartPoint = false
            isSelectingEndPoint = false
            setupDialog?.dismiss()
        }

        binding.startNavigationBtn.setOnClickListener {
            if (validateInputs(binding)) {
                val route = createRouteFromInputs(binding)
                startNavigation(route)
                setupDialog?.dismiss()
            }
        }

        setupDialog?.show()
    }

    private fun validateInputs(binding: AutoNavigationDialogBinding): Boolean {
        try {
            val startLat = binding.startLatInput.text.toString().toDoubleOrNull()
            val startLon = binding.startLonInput.text.toString().toDoubleOrNull()
            val endLat = binding.endLatInput.text.toString().toDoubleOrNull()
            val endLon = binding.endLonInput.text.toString().toDoubleOrNull()

            if (startLat == null || startLon == null || endLat == null || endLon == null) {
                context.showToast(context.getString(R.string.invalid_coordinates))
                return false
            }

            if (startLat < -90 || startLat > 90 || endLat < -90 || endLat > 90) {
                context.showToast(context.getString(R.string.invalid_coordinates))
                return false
            }

            if (startLon < -180 || startLon > 180 || endLon < -180 || endLon > 180) {
                context.showToast(context.getString(R.string.invalid_coordinates))
                return false
            }

            return true
        } catch (e: Exception) {
            context.showToast(context.getString(R.string.invalid_coordinates))
            return false
        }
    }

    private fun createRouteFromInputs(binding: AutoNavigationDialogBinding): NavigationRoute {
        val routeName = binding.routeNameInput.text.toString().ifEmpty { "Auto Route" }

        val startLat = binding.startLatInput.text.toString().toDouble()
        val startLon = binding.startLonInput.text.toString().toDouble()
        val endLat = binding.endLatInput.text.toString().toDouble()
        val endLon = binding.endLonInput.text.toString().toDouble()

        val startPoint = RoutePoint(startLat, startLon, "Start")
        val endPoint = RoutePoint(endLat, endLon, "End")

        // Get selected speed
        val selectedSpeedText = binding.speedSpinner.text.toString()
        val speed = if (selectedSpeedText == NavigationSpeed.CUSTOM.displayName) {
            binding.customSpeedInput.text.toString().toFloatOrNull() ?: NavigationSpeed.WALKING.value
        } else {
            NavigationSpeed.values().find { it.displayName == selectedSpeedText }?.value ?: NavigationSpeed.WALKING.value
        }

        // Get duration
        val minutes = binding.durationMinutesInput.text.toString().toIntOrNull() ?: 0
        val seconds = binding.durationSecondsInput.text.toString().toIntOrNull() ?: 0
        val duration = (minutes * 60 + seconds) * 1000L // Convert to milliseconds

        val isRepeating = binding.repeatRouteSwitch.isChecked

        return NavigationRoute(
            name = routeName,
            startPoint = startPoint,
            endPoint = endPoint,
            speed = speed,
            duration = duration,
            isRepeating = isRepeating
        )
    }

    private fun startNavigation(route: NavigationRoute) {
        navigationService?.let { service ->
            service.startNavigation(route)
            context.showToast(context.getString(R.string.route_started))
            showControlDialog()
        }
    }

    private fun showControlDialog() {
        // Check if activity is still alive and not finishing
        if (context.isFinishing || context.isDestroyed) {
            return
        }

        val binding = NavigationControlDialogBinding.inflate(LayoutInflater.from(context))

        controlDialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(false)
            .create()

        // Update UI with current navigation state
        updateControlDialogUI(binding)

        // Handle control buttons
        binding.pauseResumeBtn.setOnClickListener {
            navigationService?.let { service ->
                when (service.navigationState.value) {
                    NavigationState.RUNNING -> {
                        service.pauseNavigation()
                        context.showToast(context.getString(R.string.route_paused))
                    }
                    NavigationState.PAUSED -> {
                        service.resumeNavigation()
                        context.showToast(context.getString(R.string.route_resumed))
                    }
                    else -> {}
                }
            }
        }

        binding.stopNavigationBtn.setOnClickListener {
            navigationService?.stopNavigation()
            context.showToast(context.getString(R.string.route_stopped))
            // Dialog will be dismissed automatically by observeNavigationState when STOPPED state is received
        }

        // Only show dialog if activity is still alive
        if (!context.isFinishing && !context.isDestroyed) {
            controlDialog?.show()
        }
    }

    private fun observeNavigationState() {
        navigationService?.let { service ->
            context.lifecycleScope.launch {
                service.navigationState.collect { state ->
                    when (state) {
                        NavigationState.RUNNING -> {
                            if (controlDialog?.isShowing != true && !context.isFinishing && !context.isDestroyed) {
                                showControlDialog()
                            }
                        }
                        NavigationState.STOPPED -> {
                            controlDialog?.dismiss()
                            controlDialog = null
                        }
                        NavigationState.PAUSED -> {}
                    }
                }
            }

            context.lifecycleScope.launch {
                service.currentPosition.collect { position ->
                    position?.let {
                        // Update the GPS location in the main activity
                        context.updateGPSLocation(it.latitude, it.longitude)
                    }
                }
            }
        }
    }

    private fun updateControlDialogUI(binding: NavigationControlDialogBinding) {
        navigationService?.let { service ->
            context.lifecycleScope.launch {
                service.currentRoute.collect { route ->
                    route?.let {
                        binding.routeNameText.text = it.name
                    }
                }
            }

            context.lifecycleScope.launch {
                service.navigationProgress.collect { progress ->
                    progress?.let {
                        binding.progressText.text = "${it.progressPercentage.toInt()}%"
                        binding.progressBar.progress = it.progressPercentage.toInt()
                        binding.elapsedTimeText.text = formatTime(it.elapsedTime)
                        binding.remainingTimeText.text = formatTime(it.remainingTime)
                        binding.currentPositionText.text = String.format(
                            "%.6f, %.6f",
                            it.currentPosition.latitude,
                            it.currentPosition.longitude
                        )
                    }
                }
            }

            context.lifecycleScope.launch {
                service.navigationState.collect { state ->
                    binding.pauseResumeBtn.text = when (state) {
                        NavigationState.RUNNING -> context.getString(R.string.pause_navigation)
                        NavigationState.PAUSED -> context.getString(R.string.resume_navigation)
                        else -> context.getString(R.string.pause_navigation)
                    }
                }
            }
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun cleanup() {
        try {
            setupDialog?.dismiss()
            setupDialog = null
            controlDialog?.dismiss()
            controlDialog = null
            context.setMapClickMode(false, null)
            dialogBinding = null
            isSelectingStartPoint = false
            isSelectingEndPoint = false
        } catch (e: Exception) {
            // Ignore exceptions during cleanup
        } finally {
            unbindNavigationService()
        }
    }
}