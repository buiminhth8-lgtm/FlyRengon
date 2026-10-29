package com.gincode.flyregon.geofence

import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

data class GeofencePackage(
    val name: String, val version: String, val source: String,
    val geoJson: String, val dataset: GeofenceDataset,
    val hiddenIds: Set<String> = emptySet()
)

/** Portable, versioned data with integrity checks. ZIP entries are never extracted to disk. */
object GeofencePackageCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun fromGeoJson(text: String, name: String = "导入的区域数据"): GeofencePackage {
        require(text.toByteArray(Charsets.UTF_8).size <= 7 * 1024 * 1024) { "GeoJSON 超出 7 MB 限制" }
        return GeofencePackage(name, "1", "用户导入；来源与适用规则请核实", text, GeofenceGeoJson.parse(text))
    }

    fun read(input: InputStream): GeofencePackage {
        val bytes = readLimited(input, MAX_BYTES)
        if (bytes.size < 2 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) {
            return fromGeoJson(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        }
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name in setOf("manifest.json", "geofences.geojson") && !entry.isDirectory) { "数据包包含未知文件" }
                require(entry.name !in entries) { "数据包包含重复文件" }
                val data = readLimited(zip, MAX_BYTES - total)
                total += data.size
                entries[entry.name] = data
                zip.closeEntry()
            }
        }
        val manifest = JSONObject(requireNotNull(entries["manifest.json"]) { "缺少 manifest.json" }.toString(Charsets.UTF_8))
        require(manifest.getInt("schemaVersion") == 1) { "不支持的数据包版本" }
        val geo = requireNotNull(entries["geofences.geojson"]) { "缺少 GeoJSON" }
        require(geo.size <= 7 * 1024 * 1024) { "GeoJSON 超出 7 MB 限制" }
        require(manifest.getString("sha256").equals(sha256(geo), true)) { "数据校验失败：SHA256 不匹配" }
        val text = geo.toString(Charsets.UTF_8)
        val dataset = GeofenceGeoJson.parse(text)
        require(manifest.getInt("featureCount") == dataset.features.size) { "区域数量不匹配" }
        val hidden = manifest.getJSONArray("hiddenIds").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        require(dataset.features.map { it.id }.toSet().containsAll(hidden)) { "显示设置引用了不存在的区域" }
        return GeofencePackage(field(manifest, "name"), field(manifest, "version"), field(manifest, "source"), text, dataset, hidden)
    }

    fun write(pack: GeofencePackage, output: OutputStream) {
        val geo = pack.geoJson.toByteArray(Charsets.UTF_8)
        require(geo.size <= 7 * 1024 * 1024) { "数据太大" }
        val manifest = JSONObject().put("schemaVersion", 1).put("name", pack.name)
            .put("version", pack.version).put("source", pack.source)
            .put("featureCount", pack.dataset.features.size).put("sha256", sha256(geo))
            .put("hiddenIds", JSONArray(pack.hiddenIds.sorted()))
        // Do not close the caller's stream: AtomicFile must fsync/finish it itself.
        val protectedOutput = object : FilterOutputStream(output) {
            override fun close() { flush() }
        }
        ZipOutputStream(protectedOutput).use { zip ->
            for ((name, bytes) in listOf("manifest.json" to manifest.toString(2).toByteArray(Charsets.UTF_8), "geofences.geojson" to geo)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    private fun field(json: JSONObject, name: String): String = json.getString(name).also {
        require(it.isNotBlank() && it.length <= 512) { "无效的 $name 字段" }
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= max) { "文件超出 8 MB 限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
