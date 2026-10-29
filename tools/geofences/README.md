# 14 国 + 乌克兰 3 个特殊地区 GeoJSON 电子围栏工具包

## 1. 交付说明

| 内容 | 数量 | 位置 | 是否可用于正式围栏 |
|---|---:|---|---|
| 预览版：Natural Earth 1:110m 国家边界 | 14 | `preview_110m/countries/*.geojson` | **否**，仅验证文件格式与地图展示 |
| 预览版：合并国家图层 | 14 | `preview_110m/all_countries_preview.geojson` | **否** |
| 正式国家围栏生成脚本 | 14 | `build_geofences.py` | 运行并验证数据源后使用 |
| 正式乌克兰地区围栏生成脚本 | 3 | `build_geofences.py` | 运行并验证数据源后使用 |
| 电子围栏点包含测试脚本 | 1 | `geofence_lookup.py` | 读取生成的数据文件进行精准多边形判定 |
| 自动化测试 | 4 | `tests/test_geofences.py` | 单元测试，非边界准确性证明 |

**重要：当前随包提供的只有 14 个国家的低分辨率预览文件。** 当前构建环境无法访问外部边界文件下载服务器，因此没有下载 14 国高精度原始图层，也没有生成克里米亚、顿涅茨克、卢甘斯克的真实 ADM1 多边形。没有用矩形或人工猜测坐标冒充这些地区。请在能联网的电脑上运行以下脚本，生成所需的 **17 个正式围栏**。若任何图层缺失或检查失败，脚本会报错，不会假装构建成功。

## 2. 国家清单（已去重）

| ISO3 | 中文 | English |
|---|---|---|
| PRK | 朝鲜 | North Korea |
| SOM | 索马里 | Somalia |
| CAF | 中非共和国 | Central African Republic |
| SSD | 南苏丹 | South Sudan |
| IRN | 伊朗 | Iran |
| SYR | 叙利亚 | Syria |
| YEM | 也门 | Yemen |
| LBY | 利比亚 | Libya |
| RUS | 俄罗斯 | Russia |
| USA | 美国 | United States |
| ISR | 以色列 | Israel |
| TUR | 土耳其 | Turkey |
| JPN | 日本 | Japan |
| UKR | 乌克兰 | Ukraine |

额外 ADM1 地区（使用乌克兰 ADM1 2017 历史边界图层）：`crimea`（克里米亚自治共和国 + 塞瓦斯托波尔）、`donetsk`（顿涅茨克州完整行政边界）、`luhansk`（卢甘斯克州完整行政边界）。这不是实际控制范围，也不是实时前线。**原列表的“卢甘斯克等乌东部冲突地区”在本次严格定义为卢甘斯克州与顿涅茨克州完整行政区；如需其他区域或动态前线，应另列入数据源。**

## 3. 在 Windows / Ubuntu 联网电脑上生成正式 GeoJSON

需要 Python 3.10+。在解压后的工具包根目录执行：

```bash
python -m pip install -r requirements.txt
python build_geofences.py --output output --cache cache
```

生成目录：

```text
output/
├── countries/
│   ├── PRK.geojson
│   ├── SOM.geojson
│   ├── CAF.geojson
│   ├── SSD.geojson
│   ├── IRN.geojson
│   ├── SYR.geojson
│   ├── YEM.geojson
│   ├── LBY.geojson
│   ├── RUS.geojson
│   ├── USA.geojson
│   ├── ISR.geojson
│   ├── TUR.geojson
│   ├── JPN.geojson
│   └── UKR.geojson
├── regions/
│   ├── crimea.geojson
│   ├── donetsk.geojson
│   └── luhansk.geojson
├── metadata/
│   ├── source_metadata.json
│   └── reference_point_checks.json
├── manifest.json
└── all_geofences.geojson
```

数据抓取：从 `https://www.geoboundaries.org/api/current/gbOpen/{ISO3}/ADM0/` 下载 14 国 ADM0，从 `https://www.geoboundaries.org/api/current/gbOpen/UKR/ADM1/` 下载 27 个一级行政区，并按 `shapeName` 提取 3 个地区。自动保留源元数据（包括 `boundaryYearRepresented`、`boundaryLicense`、`gjDownloadURL`），以及每个围栏的 Polygon/MultiPolygon。源图层中可能存在争议区域，**脚本保留国家 ADM0 原图层，不自动修改其主权归属**，特殊地区单独作为可叠加的围栏。

### 离线方式

联网机器下载好后，`cache` 目录保留 15 份 GeoJSON 文件和来源 metadata，再复制到离线电脑：

```bash
python build_geofences.py --offline --cache cache --output output
```

如手工下载，也可将文件命名为 `cache/USA_ADM0.geojson`、`cache/UKR_ADM1.geojson` 等，离线脚本允许使用这些文件；但手动输入没有来源记录时会标为 `unknown`，上线前务必补充许可及版本信息。

### 测试点是否位于围栏

```bash
python geofence_lookup.py --file output/all_geofences.geojson --lon 139.6917 --lat 35.6895
```

输出匹配到的全部围栏 ID 与属性。边界点使用 `covers` 视为命中；国家 / 地区范围重叠时可返回多项结果，交给业务进行优先级判断。

## 4. 电子围栏部署注意事项

1. **GeoJSON 为 EPSG:4326，坐标排列为 `[longitude, latitude]`**。不要反写成 Android 常见的 `Location(latitude, longitude)` 构造顺序。
2. 真实电子围栏请使用生成的**完整**几何，避免 110m 小比例尺国家预览或只用 `bbox`；俄罗斯跨国际日期变更线，使用边界切分后的真实多边形判断。
3. 预览 110m 图层会遗漏小岛、海岸细节，国界附近误差可能达公里级乃至更高，不适合判断越境、禁飞或合规区域。
4. 离岸距离、领海、领空等不等于国家陆地区域边界；如业务需要 12 海里领海、200 海里 EEZ、海岸缓冲、空域限制，需另建图层和规则。
5. 日期线、大量岛屿、多环洞、共享边界、行政区嵌套、边界点命中、GPS 定位误差都需要在正式应用中测试。缓冲米数应在合适的地理投影或测地算法下实现，**不要直接把经纬度加减若干度当作米**。
6. 这批数据具有**地理参考性质**，不应仅凭此包作为法律归属、最新军事控制范围或重大安全决策的唯一依据。
7. 建议在 Android JTS/GEOS 类库实现 Polygon/MultiPolygon + PreparedGeometry + STRtree，加上位置误差安全边距与滞回判定，而非频繁遍历所有顶点。

## 5. 资料来源与版权

- [geoBoundaries API](https://www.geoboundaries.org/api.html) — William & Mary geoLab，个别图层的边界年份及原始许可可能不同。例如当前 API 的乌克兰 ADM0/ADM1 标注 2017 年 / ODbL 1.0，索马里 ADM0 为 2021 年 / CC BY 3.0 IGO，俄罗斯 ADM0 2017 / ODbL。**请遵守各图层 `metadata/source_metadata.json` 的原始许可，而非假设所有图层同一种 CC 许可**。
- [Natural Earth](https://www.naturalearthdata.com/) — `preview_110m/` 采用容器现有 `naturalearth_lowres` 数据，只提供低分辨率地图示例。Natural Earth 数据为公有领域。
- [Natural Earth 争议边界政策](https://www.naturalearthdata.com/about/disputed-boundaries-policy/) — 国家、争议地区存在不同制图立场。

生成后建议记录 **源数据下载日期 + 具体 source boundaryID + checksum**，以便复现与后续版本追溯。
