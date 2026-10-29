package com.gincode.flyregon

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle

import android.view.View
import android.view.ViewGroup
import com.gincode.flyregon.databinding.ActivityMainBinding
import com.gincode.flyregon.map.TiandituTileSource
import com.gincode.flyregon.geofence.GeofenceDataset
import com.gincode.flyregon.geofence.GeofencePackage
import com.gincode.flyregon.geofence.GeofenceManager
import com.gincode.flyregon.geofence.GeofenceOverlays
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.Polygon
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var map: MapView
    private var labels: TilesOverlay? = null
    private var selectedMarker: Marker? = null
    private var geofences: GeofenceDataset? = null
    private lateinit var geofenceManager: GeofenceManager
    private var geofenceLoadFailed = false
    private val geofenceWorker = Executors.newSingleThreadExecutor()
    private var geofencePolygons: List<Polygon> = emptyList()
    private val key get() = BuildConfig.TIANDITU_KEY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configure before constructing MapView. App-private cache needs no storage permission.
        Configuration.getInstance().apply {
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Construct with our provider so no default OpenStreetMap tiles are requested.
        val provider = MapTileProviderBasic(this, TiandituTileSource("vec", key)).apply {
            setUseDataConnection(key.isNotBlank())
        }
        map = MapView(this, provider).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 1.0
            maxZoomLevel = 18.0
            setTilesScaledToDpi(true)
        }
        binding.mapContainer.addView(map, 0, ViewGroup.LayoutParams(-1, -1))
        binding.keyNotice.visibility = if (key.isBlank()) View.VISIBLE else View.GONE
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint) = false
            override fun longPressHelper(p: GeoPoint): Boolean {
                placeMarker(p)
                return true
            }
        }))
        val satellite = savedInstanceState?.getBoolean("satellite") ?: false
        binding.mapType.check(if (satellite) R.id.satelliteMap else R.id.vectorMap)
        setMapType(satellite)
        binding.mapType.setOnCheckedChangeListener { _, id -> setMapType(id == R.id.satelliteMap) }
        binding.zoomIn.setOnClickListener { map.controller.zoomIn() }
        binding.zoomOut.setOnClickListener { map.controller.zoomOut() }
        binding.resetMap.setOnClickListener {
            map.controller.setZoom(12.0)
            map.controller.animateTo(HOME)
        }
        binding.showGeofences.isChecked = savedInstanceState?.getBoolean("showGeofences", true) ?: true
        binding.showGeofences.setOnCheckedChangeListener { _, visible ->
            geofencePolygons.forEach { it.isEnabled = visible }
            map.invalidate()
        }
        binding.geofenceOverview.setOnClickListener {
            map.controller.setZoom(2.0)
            map.controller.setCenter(GeoPoint(25.0, 0.0))
        }
        map.controller.setZoom(savedInstanceState?.getDouble("zoom") ?: 12.0)
        map.controller.setCenter(if (savedInstanceState == null) HOME else GeoPoint(
            savedInstanceState.getDouble("latitude"), savedInstanceState.getDouble("longitude")))
        if (savedInstanceState?.containsKey("markerLatitude") == true) {
            placeMarker(GeoPoint(savedInstanceState.getDouble("markerLatitude"),
                savedInstanceState.getDouble("markerLongitude")))
        }
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean { updateStatus(); return false }
            override fun onZoom(event: ZoomEvent): Boolean { updateStatus(); return false }
        })
        updateStatus()
        loadGeofences()
    }

    private fun loadGeofences() {
        geofenceManager = GeofenceManager(this, geofenceWorker, binding.dataManagement,
            onPack = { pack -> applyPackage(pack) },
            onFocus = { feature ->
                binding.showGeofences.isChecked = true
                val largest = (0 until feature.geometry.numGeometries).map { feature.geometry.getGeometryN(it) }.maxBy { it.area }
                val e = largest.envelopeInternal
                map.post {
                    map.zoomToBoundingBox(org.osmdroid.util.BoundingBox(e.maxY, e.maxX, e.minY, e.minX), false, 48)
                    highlightFences(setOf(feature.id))
                }
            },
            onLoadError = {
                geofenceLoadFailed = true
                binding.geofenceNotice.setText(R.string.geofence_failed)
            })
        binding.dataManagement.setOnClickListener { geofenceManager.showMenu() }
        geofenceManager.load()
    }

    private fun applyPackage(pack: GeofencePackage) {
        // Remove both overlays and their info windows before replacing the data set.
        geofencePolygons.forEach { map.overlays.remove(it); it.onDetach(map) }
        geofences = pack.dataset
        geofenceLoadFailed = false
        val displayed = GeofenceDataset(pack.dataset.features.filter { it.id !in pack.hiddenIds })
        geofencePolygons = GeofenceOverlays.create(map, displayed)
        geofencePolygons.forEach { it.isEnabled = binding.showGeofences.isChecked }
        map.overlays.addAll(1, geofencePolygons)
        binding.geofenceNotice.text = getString(R.string.dataset_summary,
            pack.name, pack.version, pack.dataset.features.size, displayed.features.size, pack.source)
        selectedMarker?.position?.let { updateGeofenceResult(it) }
        map.invalidate()
    }

    private fun highlightFences(ids: Set<String>) {
        geofencePolygons.forEach {
            val selected = it.title in ids
            it.fillPaint.color = if (selected) 0x66F59E0B else 0x28D84343
            it.outlinePaint.color = if (selected) 0xFFF59E0B.toInt() else 0xCCD84343.toInt()
        }
        map.invalidate()
    }

    @Deprecated("Uses the platform document picker on API 24+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::geofenceManager.isInitialized) geofenceManager.onActivityResult(requestCode, resultCode, data)
    }
    private fun updateGeofenceResult(point: GeoPoint) {
        val dataset = geofences
        binding.geofenceResult.text = when {
            geofenceLoadFailed -> getString(R.string.geofence_failed)
            dataset == null -> getString(R.string.geofence_loading)
            else -> {
                val matches = dataset.query(point.longitude, point.latitude)
                highlightFences(matches.map { it.id }.toSet())
                if (matches.isEmpty()) getString(R.string.geofence_no_match)
                else getString(R.string.geofence_matches, matches.joinToString("、") { it.name })
            }
        }
    }

    private fun setMapType(satellite: Boolean) {
        // Remove and detach old annotation provider to release its worker threads and cache.
        labels?.let { map.overlays.remove(it); it.onDetach(map) }
        map.setTileSource(TiandituTileSource(if (satellite) "img" else "vec", key))
        val provider = MapTileProviderBasic(this,
            TiandituTileSource(if (satellite) "cia" else "cva", key)).apply {
            setUseDataConnection(key.isNotBlank())
            // Separate provider must invalidate MapView when annotation downloads finish.
            tileRequestCompleteHandlers.add(map.tileRequestCompleteHandler)
        }
        labels = TilesOverlay(provider, this).apply {
            loadingBackgroundColor = Color.TRANSPARENT
            loadingLineColor = Color.TRANSPARENT
        }
        map.overlays.add(0, labels!!)
        map.invalidate()
    }

    private fun placeMarker(point: GeoPoint) {
        selectedMarker?.let { map.overlays.remove(it); it.onDetach(map) }
        selectedMarker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = getString(R.string.marker_title)
            snippet = getString(R.string.coordinate_format, point.latitude, point.longitude)
        }
        map.overlays.add(selectedMarker)
        selectedMarker?.showInfoWindow()
        updateGeofenceResult(point)
        map.invalidate()
    }

    private fun updateStatus() {
        binding.mapStatus.text = getString(R.string.status_format,
            map.mapCenter.latitude, map.mapCenter.longitude, map.zoomLevelDouble)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("showGeofences", binding.showGeofences.isChecked)
        outState.putBoolean("satellite", binding.mapType.checkedRadioButtonId == R.id.satelliteMap)
        outState.putDouble("latitude", map.mapCenter.latitude)
        outState.putDouble("longitude", map.mapCenter.longitude)
        outState.putDouble("zoom", map.zoomLevelDouble)
        selectedMarker?.position?.let {
            outState.putDouble("markerLatitude", it.latitude)
            outState.putDouble("markerLongitude", it.longitude)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }
    override fun onDestroy() {
        geofenceWorker.shutdownNow()
        map.onDetach()
        super.onDestroy()
    }

    companion object {
        private val HOME = GeoPoint(39.9042, 116.4074)
    }
}
