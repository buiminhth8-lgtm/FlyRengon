package com.gincode.flyregon.geofence

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.*

class GeofencePackageTest {
    private fun preview() = javaClass.classLoader!!.getResourceAsStream("geofences/all_countries_preview.geojson")!!
        .bufferedReader().use { it.readText() }
    private fun encoded(pack: GeofencePackage) = ByteArrayOutputStream().also { GeofencePackageCodec.write(pack, it) }.toByteArray()
    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes() }
        }
        return result
    }
    private fun zip(entries: List<Pair<String, ByteArray>>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    private fun changeManifest(change: (JSONObject) -> Unit): ByteArray {
        val source = entries(encoded(GeofencePackageCodec.fromGeoJson(preview())))
        val manifest = JSONObject(source.getValue("manifest.json").toString(Charsets.UTF_8))
        change(manifest)
        return zip(source.map { (name, bytes) -> name to if (name == "manifest.json") manifest.toString().toByteArray() else bytes })
    }

    @Test fun deviceMigrationRetainsGeometryProvenanceAndDisplaySettings() {
        val original = GeofencePackageCodec.fromGeoJson(preview(), "我的区域").copy(
            version = "2026-10-29", source = "Natural Earth", hiddenIds = setOf("country_USA"))
        val nextDevice = GeofencePackageCodec.read(ByteArrayInputStream(encoded(original)))
        assertEquals(original.geoJson, nextDevice.geoJson)
        assertEquals(original.hiddenIds, nextDevice.hiddenIds)
        assertEquals(original.name, nextDevice.name)
        assertEquals(original.version, nextDevice.version)
        assertEquals(original.source, nextDevice.source)
        assertEquals(listOf("country_USA"), nextDevice.dataset.query(-100.0, 40.0).map { it.id })
        assertTrue(JSONObject(nextDevice.geoJson).getJSONArray("features").getJSONObject(0)
            .getJSONObject("properties").getBoolean("preview_only"))
    }
    @Test fun importPlainGeoJson() {
        assertEquals(14, GeofencePackageCodec.read(ByteArrayInputStream(preview().toByteArray())).dataset.features.size)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectChecksumMismatch() {
        GeofencePackageCodec.read(ByteArrayInputStream(changeManifest { it.put("sha256", "wrong") }))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectFutureSchema() {
        GeofencePackageCodec.read(ByteArrayInputStream(changeManifest { it.put("schemaVersion", 2) }))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectWrongFeatureCount() {
        GeofencePackageCodec.read(ByteArrayInputStream(changeManifest { it.put("featureCount", 17) }))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectUnknownDisplayIds() {
        GeofencePackageCodec.read(ByteArrayInputStream(changeManifest { it.put("hiddenIds", org.json.JSONArray(listOf("unknown"))) }))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectZipPaths() {
        GeofencePackageCodec.read(ByteArrayInputStream(zip(listOf("../file" to byteArrayOf(1)))))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectMissingManifest() {
        GeofencePackageCodec.read(ByteArrayInputStream(zip(listOf("geofences.geojson" to preview().toByteArray()))))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectOversizedInput() {
        GeofencePackageCodec.read(ByteArrayInputStream(ByteArray(GeofencePackageCodec.MAX_BYTES + 1)))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectZipBomb() {
        GeofencePackageCodec.read(ByteArrayInputStream(zip(listOf("geofences.geojson" to ByteArray(GeofencePackageCodec.MAX_BYTES + 1)))))
    }
    @Test fun writerDoesNotCloseCallerStream() {
        var closed = false
        val out = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        GeofencePackageCodec.write(GeofencePackageCodec.fromGeoJson(preview()), out)
        assertFalse(closed)
        assertEquals(14, GeofencePackageCodec.read(ByteArrayInputStream(out.toByteArray())).dataset.features.size)
    }
}
