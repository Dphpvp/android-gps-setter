package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null
    private var routeSelectionCallback: ((Double, Double) -> Unit)? = null
    private var isInRouteSelectionMode = false
    private var routeLine: Polyline? = null

    override fun hasMarker(): Boolean {
        if (!mMarker?.isVisible!!) {
            return true
        }
        return false
    }
    private fun updateMarker(it: LatLng) {
        mMarker?.position = it!!
        mMarker?.isVisible = true
    }
    private fun removeMarker() {
        mMarker?.isVisible = false
    }
    override fun initializeMap() {
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(12.0f)
                        .bearing(0f)
                        .tilt(0f)
                        .build()
                ))
                mMarker?.apply {
                    position = latLng
                    isVisible = true
                    showInfoWindow()
                }
            }
        }
    }
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        with(mMap){

            
            // gms custom ui
            if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { 
                setMyLocationEnabled(true); 
            } else {
                ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99);
            }
            setTrafficEnabled(true)
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isCompassEnabled = false
            setPadding(0,80,0,0)
            mapType = viewModel.mapType


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                mMarker = addMarker(
                    MarkerOptions().position(it!!).draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(false)
                )
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom))
            }

            
            setOnMapClickListener(this@MapActivity)
            if (viewModel.isStarted){
                mMarker?.let {
                    // TODO:
                    // it.isVisible = true
                    // it.showInfoWindow()
                }
            }
        }
    }
    override fun onMapClick(latLng: LatLng) {
        // If in route selection mode, handle the callback
        if (isInRouteSelectionMode && routeSelectionCallback != null) {
            routeSelectionCallback?.invoke(latLng.latitude, latLng.longitude)
            return
        }

        // Normal map click behavior
        mLatLng = latLng
        mMarker?.let { marker ->
            mLatLng.let {
                // marker.isVisible = true
                updateMarker(it!!)
                mMap.animateCamera(CameraUpdateFactory.newLatLng(it))
                lat = it.latitude
                lon = it.longitude
            }
        }
    }

    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        binding.addfavorite.setOnClickListener {
            addFavoriteDialog()
        }
        binding.getlocation.setOnClickListener {
            getLastLocation()
        }
        binding.autoNavigation?.setOnClickListener {
            openAutoNavigationDialog()
        }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            viewModel.update(true, lat, lon)
            mLatLng.let {
                updateMarker(it!!)
            }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                mLatLng?.getAddress(getActivityInstance())?.let { address ->
                    address.collect{ value ->
                        showStartNotification(value)
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stopButton.setOnClickListener {
            mLatLng.let {
                viewModel.update(false, it!!.latitude, it.longitude)
            }
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE

            // Stop auto navigation if it's running
            stopAutoNavigation()
            binding.autoNavigationProgress.visibility = View.GONE

            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }

    override fun updateGPSLocation(latitude: Double, longitude: Double) {
        lat = latitude
        lon = longitude
        val latLng = LatLng(latitude, longitude)
        mLatLng = latLng

        // Smooth marker movement
        mMarker?.let { marker ->
            // Animate marker position for smooth movement
            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
            val startPosition = marker.position

            animator.duration = 200 // 200ms smooth animation
            animator.addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val newLat = startPosition.latitude + (latLng.latitude - startPosition.latitude) * fraction
                val newLng = startPosition.longitude + (latLng.longitude - startPosition.longitude) * fraction
                marker.position = LatLng(newLat, newLng)
            }
            animator.start()

            marker.isVisible = true
        }

        // Smooth camera follow (less aggressive than marker movement)
        mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng), 300, null)

        // Update the GPS mock through the view model
        viewModel.update(true, latitude, longitude)
    }

    override fun setMapClickMode(enabled: Boolean, callback: ((Double, Double) -> Unit)?) {
        isInRouteSelectionMode = enabled
        routeSelectionCallback = callback
    }

    override fun handleNavigationRunning() {
        // Show the existing start/stop button system
        binding.startButton.visibility = View.GONE
        binding.stopButton.visibility = View.VISIBLE

        // Show progress bar for auto navigation
        binding.autoNavigationProgress.visibility = View.VISIBLE

        // Update the view model to show location as started
        viewModel.update(true, lat, lon)
    }

    override fun showRouteOnMap(startLat: Double, startLon: Double, endLat: Double, endLon: Double) {
        // Remove existing route line
        routeLine?.remove()

        // Create route line
        val startPoint = LatLng(startLat, startLon)
        val endPoint = LatLng(endLat, endLon)

        routeLine = mMap.addPolyline(
            PolylineOptions()
                .add(startPoint, endPoint)
                .width(8f)
                .color(android.graphics.Color.BLUE)
                .pattern(listOf(Dash(20f), Gap(10f)))
        )

        // Adjust camera to show entire route
        val bounds = LatLngBounds.Builder()
            .include(startPoint)
            .include(endPoint)
            .build()

        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
    }

    override fun handleNavigationStopped() {
        // Reset to start button
        binding.stopButton.visibility = View.GONE
        binding.startButton.visibility = View.VISIBLE

        // Hide progress bar
        binding.autoNavigationProgress.visibility = View.GONE

        // Remove route line
        routeLine?.remove()
        routeLine = null

        // Update the view model to show location as stopped
        viewModel.update(false, lat, lon)
    }

    override fun updateNavigationProgress(progress: Int) {
        binding.autoNavigationProgress.progress = progress
    }

}
