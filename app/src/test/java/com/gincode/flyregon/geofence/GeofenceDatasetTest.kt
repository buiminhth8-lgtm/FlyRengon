package com.gincode.flyregon.geofence

import org.junit.Assert.*
import org.junit.Test

class GeofenceDatasetTest {
    private val square = "[[[0,0],[10,0],[10,10],[0,10],[0,0]]]"
    private fun feature(id: String, coords: String, type: String = "Polygon") =
        """{"type":"Feature","id":"$id","properties":{"name_zh":"$id"},"geometry":{"type":"$type","coordinates":$coords}}"""
    private fun dataset(vararg features: String) = GeofenceGeoJson.parse(
        """{"type":"FeatureCollection","features":[${features.joinToString(",") }]}""")

    @Test fun boundaryAndVerticesAreIncluded() {
        val data = dataset(feature("a", square))
        assertEquals(listOf("a"), data.query(0.0, 5.0).map { it.id })
        assertEquals(1, data.query(10.0, 10.0).size)
        assertTrue(data.query(11.0, 5.0).isEmpty())
    }

    @Test fun holesAreExcludedButHoleBoundaryIsIncluded() {
        val data = dataset(feature("hole", "[[[0,0],[10,0],[10,10],[0,10],[0,0]],[[2,2],[2,8],[8,8],[8,2],[2,2]]]"))
        assertTrue(data.query(5.0, 5.0).isEmpty())
        assertEquals(1, data.query(2.0, 5.0).size)
        assertEquals(1, data.query(1.0, 5.0).size)
    }

    @Test fun overlapsReturnEveryFeature() {
        assertEquals(listOf("a", "b"), dataset(feature("b", square), feature("a", square))
            .query(5.0, 5.0).map { it.id })
    }

    @Test fun splitDateLineDoesNotCoverGreenwich() {
        val east = "[[[170,50],[180,50],[180,60],[170,60],[170,50]]]"
        val west = "[[[-180,50],[-170,50],[-170,60],[-180,60],[-180,50]]]"
        val data = dataset(feature("date", "[$east,$west]", "MultiPolygon"))
        assertEquals(1, data.query(179.0, 55.0).size)
        assertEquals(1, data.query(-179.0, 55.0).size)
        assertEquals(1, data.query(180.0, 55.0).size)
        assertEquals(1, data.query(-180.0, 55.0).size)
        assertTrue(data.query(0.0, 55.0).isEmpty())
    }

    @Test fun dateLineCoordinatesAreEquivalent() {
        val data = dataset(feature("eastOnly", "[[[170,50],[180,50],[180,60],[170,60],[170,50]]]"))
        assertEquals(data.query(180.0, 55.0).map { it.id }, data.query(-180.0, 55.0).map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectUnsplitDateLine() {
        dataset(feature("bad", "[[[170,50],[-170,50],[-170,60],[170,60],[170,50]]]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectInvalidTopology() {
        dataset(feature("bad", "[[[0,0],[10,10],[10,0],[0,10],[0,0]]]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectOpenRing() {
        dataset(feature("bad", "[[[0,0],[10,0],[10,10],[0,10]]]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectDuplicateIds() { dataset(feature("a", square), feature("a", square)) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectNanQuery() { dataset(feature("a", square)).query(Double.NaN, 0.0) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectOutOfRangeQuery() { dataset(feature("a", square)).query(181.0, 0.0) }

    @Test fun normalizeTinyDateLineOvershoot() {
        val data = dataset(feature("edge", "[[[170,50],[180.00000000000006,50],[180.00000000000006,60],[170,60],[170,50]]]"))
        assertEquals(1, data.query(-180.0, 55.0).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectRealCoordinateOvershoot() {
        dataset(feature("bad", "[[[170,50],[181,50],[181,60],[170,60],[170,50]]]"))
    }

    @Test fun shippedPreviewLoadsAndUsesLongitudeLatitudeOrder() {
        val text = javaClass.classLoader!!.getResourceAsStream("geofences/all_countries_preview.geojson")!!
            .bufferedReader().use { it.readText() }
        val data = GeofenceGeoJson.parse(text)
        assertEquals(14, data.features.size)
        assertEquals(listOf("country_USA"), data.query(-100.0, 40.0).map { it.id })
        assertEquals(listOf("country_RUS"), data.query(37.6173, 55.7558).map { it.id })
        assertEquals(listOf("country_JPN"), data.query(138.0, 36.0).map { it.id })
        assertTrue(data.query(116.4074, 39.9042).isEmpty())
        assertTrue(data.query(0.0, 0.0).isEmpty())
    }
}
