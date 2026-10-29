package com.gincode.flyregon.geofence

import android.util.AtomicFile
import java.io.File

/** Shared lock also serializes old/new Activity instances during configuration changes. */
class GeofenceStore(directory: File) {
    private val file = AtomicFile(File(directory, "active-geofences.zip"))
    fun load(default: () -> GeofencePackage): GeofencePackage = synchronized(lock) {
        val input = try { file.openRead() } catch (_: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) error("无法读取本地围栏")
            return@synchronized default()
        }
        input.use { GeofencePackageCodec.read(it) }
    }
    fun save(pack: GeofencePackage) = synchronized(lock) {
        val output = file.startWrite()
        try {
            GeofencePackageCodec.write(pack, output)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
    companion object { private val lock = Any() }
}
