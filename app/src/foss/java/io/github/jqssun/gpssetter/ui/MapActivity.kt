package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.SupportMapFragment

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, MapLibreMap.OnMapClickListener {

    private lateinit var mMap: MapLibreMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null
    private var routeSelectionCallback: ((Double, Double) -> Unit)? = null
    private var isInRouteSelectionMode = false
    private var routeLine: Polyline? = null

    override fun hasMarker(): Boolean {
        // TODO: if (!mMarker?.isVisible!!){
        if (mMarker != null) {
            return true
        }
        return false
    }
    private fun updateMarker(it: LatLng) {
        // TODO: mMarker?.isVisible = true
        if (mMarker == null) {
            mMarker = mMap.addMarker(
                MarkerOptions().position(it)
            )
        } else {
            mMarker?.position = it!!
        }
    }
    private fun removeMarker() {
        mMarker?.remove() // mMarker?.isVisible = false
        mMarker = null
    }
    override fun initializeMap() {
        val key = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData.getString("com.maplibre.AccessToken")
        MapLibre.getInstance(this, key, WellKnownTileServer.Mapbox)
        // val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
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
                // mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng!!, 12.0f.toDouble()))
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(12.0f.toDouble())
                        .bearing(0f.toDouble())
                        .tilt(0f.toDouble())
                        .build()
                ))
                mMarker?.apply {
                    position = latLng
                    // TODO:
                    // isVisible = true
                    // showInfoWindow()
                }
            }
        }
    }
    override fun onMapReady(mapLibreMap: MapLibreMap) {
        mMap = mapLibreMap
        with(mMap){


            // maplibre custom ui - using free MapLibre demo tiles
            var typeUrl = "https://demotiles.maplibre.org/style.json"
            if (viewModel.mapType.equals(2)) { // Satellite
                typeUrl = "https://demotiles.maplibre.org/style.json"
            } else if (viewModel.mapType.equals(3)) { // Terrain
                typeUrl = "https://demotiles.maplibre.org/style.json"
            } else if (viewModel.mapType.equals(4)) { // Hybrid
                typeUrl = "https://demotiles.maplibre.org/style.json"
            } else {
                typeUrl = "https://demotiles.maplibre.org/style.json"
            }
            setStyle(typeUrl) { style ->
                if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { 
                    val locationComponent = mMap.locationComponent
                    locationComponent.activateLocationComponent(
                        LocationComponentActivationOptions.builder(this@MapActivity, style)
                        .useDefaultLocationEngine(true)
                        .build()
                    )
                    locationComponent.isLocationComponentEnabled = true
                    locationComponent.cameraMode = CameraMode.TRACKING
                    locationComponent.renderMode = RenderMode.COMPASS
                } else {
                    ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99);
                }
            }
            // TODO: fix bug with drawer
            uiSettings.setAllGesturesEnabled(true)
            uiSettings.setCompassEnabled(true)
            uiSettings.setCompassMargins(0,480,120,0)
            uiSettings.setLogoEnabled(true)
            uiSettings.setLogoMargins(0,0,0,80)
            uiSettings.setAttributionEnabled(false)
            // uiSettings.setAttributionMargins(80,0,0,80)
            // setPadding(0,0,0,80)


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                updateMarker(it!!)
                // TODO: MarkerOptions().position(it!!)
                // .draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED).visible(false)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom.toDouble()))
            }

            addOnMapClickListener(this@MapActivity)
            if (viewModel.isStarted){
                mMarker?.let {
                    // TODO:
                    // it.isVisible = true
                    // it.showInfoWindow()
                }
            }
        }
    }
    override fun onMapClick(latLng: LatLng): Boolean {
        // If in route selection mode, handle the callback
        if (isInRouteSelectionMode && routeSelectionCallback != null) {
            routeSelectionCallback?.invoke(latLng.latitude, latLng.longitude)
            return true
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
        return true
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

        // Smooth marker movement for MapLibre
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
        } ?: run {
            // Create marker if it doesn't exist
            updateMarker(latLng)
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


    override fun handleNavigationStopped() {
        // Reset to start button
        binding.stopButton.visibility = View.GONE
        binding.startButton.visibility = View.VISIBLE

        // Hide progress bar
        binding.autoNavigationProgress.visibility = View.GONE

        // Remove route line
        routeLine?.let { mMap.removeAnnotation(it) }
        routeLine = null

        // Update the view model to show location as stopped
        viewModel.update(false, lat, lon)
    }

    override fun updateNavigationProgress(progress: Int) {
        binding.autoNavigationProgress.progress = progress
    }

    override fun showRouteOnMap(startLat: Double, startLon: Double, endLat: Double, endLon: Double) {
        // Remove existing route line
        routeLine?.let { mMap.removeAnnotation(it) }

        // For now, show simple line - will be updated when we get waypoints from NavigationService
        val startPoint = LatLng(startLat, startLon)
        val endPoint = LatLng(endLat, endLon)

        val polylineOptions = PolylineOptions()
            .add(startPoint)
            .add(endPoint)
            .color(android.graphics.Color.BLUE)
            .width(8f)

        routeLine = mMap.addPolyline(polylineOptions)

        // Adjust camera to show entire route
        val bounds = LatLngBounds.Builder()
            .include(startPoint)
            .include(endPoint)
            .build()

        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
    }

    override fun showRouteWithWaypoints(waypoints: List<CustomLatLng>) {
        // Remove existing route line
        routeLine?.let { mMap.removeAnnotation(it) }

        if (waypoints.size < 2) return

        // Create route line with all waypoints
        val polylineOptions = PolylineOptions()
            .color(android.graphics.Color.BLUE)
            .width(8f)

        waypoints.forEach { polylineOptions.add(it) }
        routeLine = mMap.addPolyline(polylineOptions)

        // Adjust camera to show entire route
        val boundsBuilder = LatLngBounds.Builder()
        waypoints.forEach { boundsBuilder.include(it) }
        val bounds = boundsBuilder.build()

        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
    }
}
