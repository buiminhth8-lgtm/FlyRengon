package com.gincode.flyregon.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.net.URLEncoder

/** 天地图 Web Mercator WMTS，与 osmdroid 的 XYZ 索引一致。 */
class TiandituTileSource(private val layer: String, key: String) : OnlineTileSourceBase(
    "Tianditu_${layer}_w", 1, 18, 256, ".png",
    Array(8) { "https://t$it.tianditu.gov.cn/${layer}_w/wmts" },
    "© 天地图"
) {
    private val encodedKey = URLEncoder.encode(key, "UTF-8")

    override fun getTileURLString(pMapTileIndex: Long): String =
        "$baseUrl?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
            "&LAYER=$layer&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles" +
            "&TILEMATRIX=${MapTileIndex.getZoom(pMapTileIndex)}" +
            "&TILEROW=${MapTileIndex.getY(pMapTileIndex)}" +
            "&TILECOL=${MapTileIndex.getX(pMapTileIndex)}&tk=$encodedKey"
}
