# FlyRegon 天地图 Demo

原生 Android Activity + Kotlin + XML + ViewBinding，不使用 Compose。

## 运行

1. 在天地图开发平台申请具有地图服务访问权限的 Key：https://lbs.tianditu.gov.cn/ 。
2. 在项目根目录的 `local.properties` 中新增一行（保留原来的 sdk.dir）：

   ```properties
   TIANDITU_KEY=你的天地图Key
   ```

   也可使用 Gradle 参数 `-PTIANDITU_KEY=...`，参数优先。本地配置已被 .gitignore 忽略。
3. 使用 JDK 17、Android SDK 34，同步 Gradle 并运行 app。当前项目 AGP 8.6.0 / Gradle 8.7 保持不变。

   ```powershell
   ./gradlew.bat :app:assembleDebug :app:lintDebug
   ```

未配置 Key 仍可构建并启动，页面显示配置说明，底图和注记均不会发起网络请求。配置后需要重新编译。Key 会进入 APK，生产环境请按天地图平台能力配置访问限制，不要把客户端 Key 当作服务端秘密。

## 功能

- 初始北京中心（纬度 39.9042、经度 116.4074），缩放级别 12。
- 矢量底图 vec + 中文注记 cva；卫星影像 img + 中文注记 cia。
- HTTPS WMTS，使用 `_w` / `TILEMATRIXSET=w` 墨卡托瓦片，级别 1–18。
- 拖动、双指缩放、缩放按钮、复位到北京。
- 长按添加/替换标记，点标记查看经纬度。
- 显示地图中心经纬度与缩放层级；旋转屏幕后恢复中心、层级、底图类型和标记。
- osmdroid 缓存位于应用私有 cacheDir/osmdroid，无需存储权限或定位权限。
- Activity 转发 MapView 生命周期；切换底图时移除并释放旧注记 provider。

## 主要代码

- `app/src/main/java/com/gincode/flyregon/MainActivity.kt`：页面、图层、交互与生命周期。
- `app/src/main/java/com/gincode/flyregon/map/TiandituTileSource.kt`：WMTS URL 和瓦片索引映射。
- `app/src/main/res/layout/activity_main.xml`：XML 布局。
- `app/build.gradle`：ViewBinding、Key 注入。
- `gradle/libs.versions.toml`：osmdroid 6.1.20 依赖。

## 验证与排查

在设备上配置有效 Key 后检查：首次显示中文地图、切换影像及注记、长按标点、缩放与复位、旋转屏幕、退出重进。若只有网格，请检查网络、Key 是否有效以及对应服务权限；配置非空不代表服务已鉴权成功。此 Demo 不把请求已发起显示为加载成功。

osmdroid 官方最新正式版为 6.1.20，项目于 2024 年归档，不再发布更新：
https://github.com/osmdroid/osmdroid

天地图官方地图服务说明：
https://lbs.tianditu.gov.cn/server/MapService.html

## 本次构建验证（2026-10-29）

- `:app:assembleDebug :app:lintDebug` 执行成功。
- Lint：0 errors、15 warnings，主要为原模板残留资源、依赖/targetSdk 提示及布局样式建议。
- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 当前无已连接的 Android 设备，未提供实际天地图 Key，尚未进行真机瓦片加载和交互验收。
