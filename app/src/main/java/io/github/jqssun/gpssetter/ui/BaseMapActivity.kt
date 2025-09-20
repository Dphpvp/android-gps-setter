package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.ElevationOverlayProvider
import com.google.android.material.progressindicator.LinearProgressIndicator
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.adapter.FavListAdapter
import io.github.jqssun.gpssetter.databinding.ActivityMapBinding
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.utils.JoystickService
import io.github.jqssun.gpssetter.utils.NotificationsChannel
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.ext.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.properties.Delegates

@AndroidEntryPoint
abstract class BaseMapActivity: AppCompatActivity() {

    protected var lat by Delegates.notNull<Double>()
    protected var lon by Delegates.notNull<Double>()
    protected val viewModel by viewModels<MainViewModel>()
    protected val binding by lazy { ActivityMapBinding.inflate(layoutInflater) }
    protected lateinit var alertDialog: MaterialAlertDialogBuilder
    protected lateinit var dialog: AlertDialog
    protected val update by lazy { viewModel.getAvailableUpdate() }

    private val notificationsChannel by lazy { NotificationsChannel() }
    private var favListAdapter: FavListAdapter = FavListAdapter()
    private var xposedDialog: AlertDialog? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val PERMISSION_ID = 42
    private var autoNavigationDialog: AutoNavigationDialog? = null

    private val elevationOverlayProvider by lazy {
        ElevationOverlayProvider(this)
    }

    private val headerBackground by lazy {
        elevationOverlayProvider.compositeOverlayWithThemeSurfaceColorIfNeeded(
            resources.getDimension(R.dimen.bottom_sheet_elevation)
        )
    }

    protected abstract fun getActivityInstance(): BaseMapActivity
    protected abstract fun hasMarker(): Boolean
    protected abstract fun initializeMap()
    protected abstract fun setupButtons()
    protected abstract fun moveMapToNewLocation(moveNewLocation: Boolean)
    abstract fun updateGPSLocation(latitude: Double, longitude: Double)
    abstract fun setMapClickMode(enabled: Boolean, callback: ((Double, Double) -> Unit)?)
    abstract fun handleNavigationRunning()
    abstract fun handleNavigationStopped()
    abstract fun updateNavigationProgress(progress: Int)
    abstract fun showRouteOnMap(startLat: Double, startLon: Double, endLat: Double, endLon: Double)
    abstract fun showRouteWithWaypoints(waypoints: List<CustomLatLng>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycleScope.launchWhenCreated {
            setContentView(binding.root)
        }
        setSupportActionBar(binding.toolbar)
        initializeMap()
        checkModuleEnabled()
        checkUpdates()
        setupNavView()
        setupButtons()
        setupDrawer()
        if (PrefManager.isJoystickEnabled){
            startService(Intent(this, JoystickService::class.java))
        }
    }

    private fun setupDrawer() {
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val mDrawerToggle = object : ActionBarDrawerToggle(
            this,
            binding.container,
            binding.toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        ) {
            override fun onDrawerClosed(view: View) {
                super.onDrawerClosed(view)
                invalidateOptionsMenu()
            }

            override fun onDrawerOpened(drawerView: View) {
                super.onDrawerOpened(drawerView)
                invalidateOptionsMenu()
            }
        }
        binding.container.setDrawerListener(mDrawerToggle)
    }

    private fun setupNavView() {

        binding.mapContainer.map.setOnApplyWindowInsetsListener { _, insets ->
            val topInset: Int = insets.systemWindowInsetTop
            val bottomInset: Int = insets.systemWindowInsetBottom
            binding.navView.setPadding(0,topInset,0,0)
            insets.consumeSystemWindowInsets()
        }

        val progress = binding.search.searchProgress
        binding.search.searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                if (isNetworkConnected()) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        val getInput = v.text.toString()
                        if (getInput.isNotEmpty()){
                            getSearchAddress(getInput).let {
                                it.collect { result ->
                                    when(result) {
                                        is SearchProgress.Progress -> {
                                            progress.visibility = View.VISIBLE
                                        }
                                        is SearchProgress.Complete -> {
                                            progress.visibility = View.GONE
                                            lat = result.lat
                                            lon = result.lon
                                            moveMapToNewLocation(true)
                                        }
                                        is SearchProgress.Fail -> {
                                            progress.visibility = View.GONE
                                            showToast(result.error!!)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    showToast(getString(R.string.no_internet))
                }
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }

        binding.navView.setNavigationItemSelectedListener {
            when(it.itemId){
                R.id.get_favorite -> {
                    openFavoriteListDialog()
                }
                R.id.settings -> {
                    startActivity(Intent(this,ActivitySettings::class.java))
                }
                R.id.about -> {
                    aboutDialog()
                }
            }
            binding.container.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun checkModuleEnabled(){
        viewModel.isXposed.observe(this) { isXposed ->
            xposedDialog?.dismiss()
            xposedDialog = null
            if (!isXposed) {
                xposedDialog = MaterialAlertDialogBuilder(this).run {
                    setTitle(R.string.error_xposed_module_missing)
                    setMessage(R.string.error_xposed_module_missing_desc)
                    // setCancelable(BuildConfig.DEBUG)
                    setCancelable(true)
                    show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateXposedState()
    }

    protected fun aboutDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        layoutInflater.inflate(R.layout.about,null).apply {
            val  titlele = findViewById<TextView>(R.id.design_about_title)
            val  version = findViewById<TextView>(R.id.design_about_version)
            val  info = findViewById<TextView>(R.id.design_about_info)
            titlele.text = getString(R.string.app_name)
            version.text = BuildConfig.VERSION_NAME
            info.text = getString(R.string.about_info)
        }.run {
            alertDialog.setView(this)
            alertDialog.show()
        }
    }

    protected fun addFavoriteDialog() {
        alertDialog =  MaterialAlertDialogBuilder(this).apply {
            val view = layoutInflater.inflate(R.layout.dialog,null)
            val editText = view.findViewById<EditText>(R.id.search_edittxt)
            setTitle(getString(R.string.add_fav_dialog_title))
            setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                val s = editText.text.toString()
                if (hasMarker()){
                  showToast(getString(R.string.location_not_select))
                }else{
                    viewModel.storeFavorite(s, lat, lon)
                    viewModel.response.observe(getActivityInstance()){
                        if (it == (-1).toLong()) showToast(getString(R.string.cant_save)) else showToast(getString(R.string.save))
                    }
                }
            }
            setView(view)
            show()
        }
    }

    private fun openFavoriteListDialog() {
        getAllUpdatedFavList()
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(getString(R.string.favorites))
        val view = layoutInflater.inflate(R.layout.fav,null)
        val rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        favListAdapter.onItemClick = {
            it.let {
                lat = it.lat!!
                lon = it.lng!!
            }
            moveMapToNewLocation(true)
            if (dialog.isShowing) dialog.dismiss()

        }
        favListAdapter.onItemDelete = {
            viewModel.deleteFavorite(it)
        }
        alertDialog.setView(view)
        dialog = alertDialog.create()
        dialog.show()

    }

    private fun getAllUpdatedFavList(){
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
                viewModel.doGetUserDetails()
                viewModel.allFavList.collect {
                    favListAdapter.submitList(it)
                }
            }
        }

    }

    private fun checkUpdates(){
        lifecycleScope.launchWhenResumed {
            viewModel.update.collect{
                if (it!= null){
                    updateDialog()
                }
            }
        }
    }

    private fun updateDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(R.string.update_available)
        alertDialog.setMessage(update?.changelog)
        alertDialog.setPositiveButton(getString(R.string.update_button)) { _, _ ->
            MaterialAlertDialogBuilder(this).apply {
                val view = layoutInflater.inflate(R.layout.update_dialog, null)
                val progress = view.findViewById<LinearProgressIndicator>(R.id.update_download_progress)
                val cancel = view.findViewById<AppCompatButton>(R.id.update_download_cancel)
                setView(view)
                cancel.setOnClickListener {
                    viewModel.cancelDownload(getActivityInstance())
                    dialog.dismiss()
                }
                lifecycleScope.launch {
                    viewModel.downloadState.collect {
                        when (it) {
                            is MainViewModel.State.Downloading -> {
                                if (it.progress > 0) {
                                    progress.isIndeterminate = false
                                    progress.progress = it.progress
                                }
                            }
                            is MainViewModel.State.Done -> {
                                viewModel.openPackageInstaller(getActivityInstance(), it.fileUri)
                                viewModel.clearUpdate()
                                dialog.dismiss()
                            }
                            is MainViewModel.State.Failed -> {
                                Toast.makeText(
                                    getActivityInstance(),
                                    R.string.bs_update_download_failed,
                                    Toast.LENGTH_LONG
                                ).show()
                                dialog.dismiss()

                            }
                            else -> {}
                        }
                    }
                }
                update?.let { it ->
                    viewModel.startDownload(getActivityInstance(), it)
                } ?: run {
                    dialog.dismiss()
                }
            }.run {
                dialog = create()
                dialog.show()
            }
        }
        dialog = alertDialog.create()
        dialog.show()
    }

    private suspend fun getSearchAddress(address: String) = callbackFlow {
        withContext(Dispatchers.IO){
            trySend(SearchProgress.Progress)
            val matcher: Matcher =
                Pattern.compile("[-+]?\\d{1,3}([.]\\d+)?, *[-+]?\\d{1,3}([.]\\d+)?").matcher(address)

            if (matcher.matches()){
                delay(3000)
                trySend(SearchProgress.Complete(matcher.group().split(",")[0].toDouble(),matcher.group().split(",")[1].toDouble()))
            }else {
                val geocoder = Geocoder(getActivityInstance())
                val addressList: List<Address>? = geocoder.getFromLocationName(address,3)

                try {
                    addressList?.let {
                        if (it.size == 1){
                           trySend(SearchProgress.Complete(addressList[0].latitude, addressList[0].longitude))
                        }else {
                            trySend(SearchProgress.Fail(getString(R.string.address_not_found)))
                        }
                    }
                } catch (io : IOException){
                    trySend(SearchProgress.Fail(getString(R.string.no_internet)))
                }
            }
        }
        awaitClose { this.cancel() }
    }

    protected fun showStartNotification(address: String){
        notificationsChannel.showNotification(this){
            it.setSmallIcon(R.drawable.ic_stop)
            it.setContentTitle(getString(R.string.location_set))
            it.setContentText(address)
            it.setAutoCancel(true)
            it.setCategory(Notification.CATEGORY_EVENT)
            it.priority = NotificationCompat.PRIORITY_HIGH
        }
    }

    protected fun cancelNotification(){
        notificationsChannel.cancelAllNotifications(this)
    }

    // Get current location
    @SuppressLint("MissingPermission")
    protected fun getLastLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (checkPermissions()) {
            if (isLocationEnabled()) {
                fusedLocationClient.lastLocation.addOnCompleteListener(this) { task ->
                    val location: Location? = task.result
                    if (location == null) {
                        requestNewLocationData()
                    } else {
                        lat = location.latitude
                        lon = location.longitude
                        moveMapToNewLocation(true)
                    }
                }
            } else {
                showToast("Turn on location")
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
            }
        } else {
            requestPermissions()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNewLocationData() {
        val mLocationRequest = LocationRequest()
        mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        mLocationRequest.interval = 0
        mLocationRequest.fastestInterval = 0
        mLocationRequest.numUpdates = 1

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.requestLocationUpdates(
            mLocationRequest, mLocationCallback,
            Looper.myLooper()
        )
    }

    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location = locationResult.lastLocation!!
            lat = mLastLocation.latitude
            lon = mLastLocation.longitude
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager: LocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
            LocationManager.NETWORK_PROVIDER
        )
    }

    private fun checkPermissions(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return false
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            PERMISSION_ID
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
            getLastLocation()
        }
    }

    protected fun openAutoNavigationDialog() {
        autoNavigationDialog = AutoNavigationDialog(this, lat, lon)
        autoNavigationDialog?.show()
    }

    protected fun stopAutoNavigation() {
        autoNavigationDialog?.stopNavigation()
    }

    override fun onDestroy() {
        autoNavigationDialog?.cleanup()
        autoNavigationDialog = null
        super.onDestroy()
    }
}

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double , val lon : Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}
