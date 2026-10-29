# Natural Earth 1:110m：国家边界低精度预览

此目录有 14 个国家 GeoJSON 和 1 个合并 GeoJSON（均以 WGS84/EPSG:4326 编码）。来自离线 `naturalearth_lowres` 测试数据（Natural Earth 公有领域），用于地图 UI 预览、文件格式验证和调试。

**禁止将这些低精度预览文件直接用于要求可靠空间判断的正式电子围栏。** 小岛/海岸/边境细节缺失，且不包含克里米亚、顿涅茨克、卢甘斯克独立区域多边形。索马里预览中按地理覆盖需求合并 Natural Earth 中单独表示的 Somaliland；这不构成政治立场。国家实际边界由数据源的惯例决定。

生成正式 17 个围栏请阅读上级 `README.md` 并运行 `build_geofences.py`。
