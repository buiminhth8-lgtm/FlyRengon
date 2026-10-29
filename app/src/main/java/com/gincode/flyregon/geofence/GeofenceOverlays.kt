package com.gincode.flyregon.geofence

import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.locationtech.jts.geom.LineString

/** Keep separate island polygons and their holes; do not draw bounding boxes. */
object GeofenceOverlays {
    fun create(map: MapView, dataset: GeofenceDataset): List<Polygon> = dataset.features.flatMap { feature ->
        (0 until feature.geometry.numGeometries).map { i ->
            val geometry = feature.geometry.getGeometryN(i) as org.locationtech.jts.geom.Polygon
            Polygon(map).apply {
                title = feature.id
                points = pointsOf(geometry.exteriorRing)
                holes = (0 until geometry.numInteriorRing).map { pointsOf(geometry.getInteriorRingN(it)) }
                fillPaint.color = 0x28D84343
                outlinePaint.color = 0xCCD84343.toInt()
                outlinePaint.strokeWidth = 2f * map.resources.displayMetrics.density
                // Let map gestures pass through so long-press querying also works inside fences.
                setOnClickListener { _, _, _ -> false }
            }
        }
    }

    private fun pointsOf(ring: LineString) = ring.coordinates.map { GeoPoint(it.y, it.x) }
}
