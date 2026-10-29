package com.gincode.flyregon.geofence

import org.json.JSONArray
import org.json.JSONObject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.index.strtree.STRtree
import kotlin.math.abs

/** Geometry and queries use [longitude, latitude], never Android's lat/lon order. */
class GeofenceFeature(val id: String, val name: String, val geometry: Geometry) {
    internal val prepared = PreparedGeometryFactory.prepare(geometry)
}

/** These administrative regions are demonstration fences, not aviation restrictions. */
class GeofenceDataset(val features: List<GeofenceFeature>) {
    private val factory = GeometryFactory()
    private val index = STRtree().apply {
        features.forEach { insert(it.geometry.envelopeInternal, it) }
        build()
    }

    fun query(longitude: Double, latitude: Double): List<GeofenceFeature> {
        require(longitude.isFinite() && latitude.isFinite())
        require(longitude in -180.0..180.0 && latitude in -90.0..90.0)
        // +180 and -180 denote the same meridian; include both sides of a split fence.
        val longitudes = if (abs(longitude) == 180.0) listOf(-180.0, 180.0) else listOf(longitude)
        return longitudes.flatMap { lon ->
            val point = factory.createPoint(Coordinate(lon, latitude))
            index.query(point.envelopeInternal).map { it as GeofenceFeature }
                .filter { it.prepared.covers(point) }
        }.distinctBy { it.id }.sortedBy { it.id }
    }
}

object GeofenceGeoJson {
    private val factory = GeometryFactory()

    fun parse(text: String): GeofenceDataset {
        val root = JSONObject(text)
        require(root.getString("type") == "FeatureCollection") { "Expected FeatureCollection" }
        val features = root.getJSONArray("features")
        require(features.length() in 1..500) { "区域数量需为 1–500" }
        require(!root.has("crs")) { "请先转换为 RFC 7946 WGS84 GeoJSON，并移除 crs 字段" }
        var totalPoints = 0
        val fences = (0 until features.length()).map { i ->
            val feature = features.getJSONObject(i)
            require(feature.getString("type") == "Feature")
            val properties = feature.getJSONObject("properties")
            val id = feature.optString("id").ifBlank { properties.getString("geofence_id") }
            require(id.isNotBlank() && id.length <= 128) { "区域 ID 需为 1–128 字符" }
            val geometryJson = feature.getJSONObject("geometry")
            val coordinates = geometryJson.getJSONArray("coordinates")
            val geometry = when (geometryJson.getString("type")) {
                "Polygon" -> polygon(coordinates)
                "MultiPolygon" -> factory.createMultiPolygon(Array(coordinates.length()) {
                    polygon(coordinates.getJSONArray(it))
                })
                else -> error("Unsupported geometry for $id")
            }
            require(!geometry.isEmpty && geometry.isValid) { "Invalid geometry: $id" }
            totalPoints += geometry.numPoints
            require(totalPoints <= 100000) { "坐标点数量超过 100000，请分批导入或预处理" }
            val name = properties.optString("name_zh", id)
            require(name.isNotBlank() && name.length <= 160) { "区域名称需为 1–160 字符" }
            GeofenceFeature(id, name, geometry)
        }
        require(fences.map { it.id }.toSet().size == fences.size) { "Duplicate fence IDs" }
        return GeofenceDataset(fences)
    }

    private fun polygon(rings: JSONArray): Polygon {
        require(rings.length() > 0)
        return factory.createPolygon(ring(rings.getJSONArray(0)),
            Array(rings.length() - 1) { ring(rings.getJSONArray(it + 1)) })
    }

    private fun ring(values: JSONArray): LinearRing {
        require(values.length() >= 4) { "Ring needs at least four positions" }
        val points = Array(values.length()) { i ->
            val value = values.getJSONArray(i)
            val lon = value.getDouble(0)
            val lat = value.getDouble(1)
            // Supplied RUS preview has 180.00000000000006 after floating-point clipping.
            // Clamp only machine-scale overshoot; reject real out-of-range coordinates.
            require(lon.isFinite() && lat.isFinite() &&
                lon in (-180.0 - 1e-9)..(180.0 + 1e-9) &&
                lat in (-90.0 - 1e-9)..(90.0 + 1e-9)) { "Coordinate outside geographic range" }
            Coordinate(lon.coerceIn(-180.0, 180.0), lat.coerceIn(-90.0, 90.0))
        }
        require(points.first().equals2D(points.last())) { "Ring is not closed" }
        // Reject unprocessed dateline jumps instead of interpreting them as world-spanning areas.
        require(points.asList().zipWithNext().none { (a, b) -> abs(a.x - b.x) > 180.0 }) {
            "Split antimeridian crossings before importing"
        }
        return factory.createLinearRing(points)
    }
}
