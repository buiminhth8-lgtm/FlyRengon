package com.gincode.flyregon.geofence

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GeofenceStoreTest {
    @Test fun persistedDataSurvivesNewStoreAndFailedReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "store-test-${System.nanoTime()}")
        try {
            val text = context.assets.open("geofences/all_countries_preview.geojson").bufferedReader().use { it.readText() }
            val expected = GeofencePackageCodec.fromGeoJson(text).copy(hiddenIds = setOf("country_USA"))
            val store = GeofenceStore(directory)
            store.save(expected)
            try {
                store.save(expected.copy(geoJson = "x".repeat(8 * 1024 * 1024)))
                fail("Oversized replacement should fail")
            } catch (_: IllegalArgumentException) { }
            val restored = GeofenceStore(directory).load { error("Must not use builtin fallback") }
            assertEquals(expected.geoJson, restored.geoJson)
            assertEquals(expected.hiddenIds, restored.hiddenIds)
        } finally { directory.deleteRecursively() }
    }
}
