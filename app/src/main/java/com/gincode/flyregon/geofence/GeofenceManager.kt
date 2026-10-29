package com.gincode.flyregon.geofence

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.widget.*
import java.util.concurrent.ExecutorService

/** File picker + local storage + region browser. No storage permission is needed. */
class GeofenceManager(
    private val activity: Activity, private val worker: ExecutorService,
    private val button: View, private val onPack: (GeofencePackage) -> Unit,
    private val onFocus: (GeofenceFeature) -> Unit, private val onLoadError: () -> Unit
) {
    private val store = GeofenceStore(java.io.File(activity.filesDir, "geofences"))
    private var active: GeofencePackage? = null
    private var busy = false
    private var pendingResult: Triple<Int, Int, Intent?>? = null

    private fun builtin(): GeofencePackage {
        val text = activity.assets.open("geofences/all_countries_preview.geojson").bufferedReader().use { it.readText() }
        return GeofencePackageCodec.fromGeoJson(text, "14 国行政区预览").copy(
            version = "NaturalEarth-110m-preview-v1", source = "Natural Earth · 1:1.1 亿 · 仅预览")
    }
    fun load() = execute({ store.load { builtin() } }, { install(it) }, { onLoadError() })

    fun showMenu() {
        if (busy) return
        val pack = active
        AlertDialog.Builder(activity).setTitle("围栏数据管理")
            .setMessage(pack?.let { "${it.name}\n版本：${it.version}\n${it.source}\n${it.dataset.features.size} 个区域 · 显示 ${it.dataset.features.size - it.hiddenIds.size} 个\n导出后可在新设备导入" } ?: "数据未加载，可导入数据或恢复内置预览")
            .setPositiveButton("操作") { _, _ ->
                val choices = if (pack == null) arrayOf("导入数据", "恢复内置预览")
                    else arrayOf("区域列表 / 定位 / 显示", "编辑数据说明", "导入数据", "导出迁移包", "恢复内置预览")
                AlertDialog.Builder(activity).setItems(choices) { _, index ->
                    when (choices[index]) {
                        "区域列表 / 定位 / 显示" -> showRegions()
                        "编辑数据说明" -> editMetadata()
                        "导入数据" -> chooseImport()
                        "导出迁移包" -> chooseExport()
                        else -> AlertDialog.Builder(activity).setMessage("恢复内置预览将替换当前区域和显示设置，是否继续？")
                            .setPositiveButton("恢复") { _, _ -> execute({ builtin().also { store.save(it) } }, { install(it) }) }
                            .setNegativeButton("取消", null).show()
                    }
                }.show()
            }.setNegativeButton("关闭", null).show()
    }

    private fun editMetadata() {
        val pack = active ?: return
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        fun field(label: String, initial: String) = EditText(activity).apply {
            hint = label; setText(initial); maxLines = 2; layout.addView(this)
        }
        val name = field("数据名称", pack.name)
        val version = field("数据版本 / 日期", pack.version)
        val source = field("来源与适用范围", pack.source)
        val dialog = AlertDialog.Builder(activity).setTitle("编辑数据说明").setView(layout)
            .setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val fields = listOf(name, version, source)
                if (fields.any { it.text.toString().trim().isEmpty() || it.text.length > 512 }) {
                    toast("名称、版本、来源均需填写，且各不超过 512 字符")
                    return@setOnClickListener
                }
                val next = pack.copy(name = name.text.toString().trim(), version = version.text.toString().trim(), source = source.text.toString().trim())
                execute({ store.save(next); next }, { install(it); dialog.dismiss() })
            }
        }
        dialog.show()
    }

    private fun showRegions() {
        val pack = active ?: return
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val search = EditText(activity).apply { hint = "搜索名称或 ID"; isSingleLine = true }
        val list = ListView(activity)
        layout.addView(search)
        layout.addView(list, LinearLayout.LayoutParams(-1, minOf(
            (320 * activity.resources.displayMetrics.density).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.45f).toInt())))
        var shown = pack.dataset.features
        fun refresh(filter: String) {
            shown = pack.dataset.features.filter { it.name.contains(filter, true) || it.id.contains(filter, true) }
            list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1,
                shown.map { "${if (it.id in pack.hiddenIds) "○" else "●"} ${it.name}\n${it.id}" })
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val dialog = AlertDialog.Builder(activity).setTitle("区域列表（● 显示 / ○ 隐藏）").setView(layout)
            .setNegativeButton("关闭", null).create()
        list.setOnItemClickListener { _, _, position, _ ->
            val feature = shown[position]
            val props = org.json.JSONObject(pack.geoJson).getJSONArray("features").let { features ->
                (0 until features.length()).map { features.getJSONObject(it) }.first {
                    it.optString("id", it.getJSONObject("properties").optString("geofence_id")) == feature.id
                }.getJSONObject("properties")
            }
            val description = listOf("source_year", "source_license", "boundary_kind", "preview_only")
                .filter { props.has(it) }.joinToString("\n") { "$it: ${props.opt(it)}" }
            AlertDialog.Builder(activity).setTitle(feature.name)
                .setMessage("ID: ${feature.id}\n${feature.geometry.numGeometries} 个多边形 · ${feature.geometry.numPoints} 个坐标点\n$description")
                .setPositiveButton("定位") { _, _ ->
                    // Reveal the chosen fence so a hidden feature can still be found visually.
                    val next = pack.copy(hiddenIds = pack.hiddenIds - feature.id)
                    execute({ store.save(next); next }, { install(it); onFocus(feature); dialog.dismiss() })
                }
                .setNeutralButton(if (feature.id in pack.hiddenIds) "显示区域" else "隐藏区域") { _, _ ->
                    val next = pack.copy(hiddenIds = if (feature.id in pack.hiddenIds) pack.hiddenIds - feature.id else pack.hiddenIds + feature.id)
                    execute({ store.save(next); next }, { install(it); dialog.dismiss(); showRegions() })
                }.setNegativeButton("返回", null).show()
        }
        refresh("")
        dialog.show()
    }

    private fun chooseImport() = activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
    }, IMPORT)
    private fun chooseExport() = activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
        putExtra(Intent.EXTRA_TITLE, "FlyRegon-geofences.zip")
    }, EXPORT)

    fun onActivityResult(request: Int, result: Int, intent: Intent?) {
        if (request !in setOf(IMPORT, EXPORT) || result != Activity.RESULT_OK) return
        if (busy) { pendingResult = Triple(request, result, intent); return }
        val uri = intent?.data ?: return
        if (request == IMPORT) {
            execute({ activity.contentResolver.openInputStream(uri)!!.use { GeofencePackageCodec.read(it) } }, { candidate ->
                AlertDialog.Builder(activity).setTitle("确认导入")
                    .setMessage("${candidate.name}\n${candidate.version}\n${candidate.source}\n共 ${candidate.dataset.features.size} 个区域\n将整体替换当前区域与显示设置。导入不表示数据已获官方认证。")
                    .setPositiveButton("导入") { _, _ ->
                        execute({ store.save(candidate); candidate }, { install(it); toast("数据导入成功") })
                    }.setNegativeButton("取消", null).show()
            })
        } else {
            val snapshot = active ?: return
            execute({
                activity.contentResolver.openOutputStream(uri, "wt")!!.use { GeofencePackageCodec.write(snapshot, it) }
            }, { toast("迁移包已导出，请传至新设备后导入") })
        }
    }
    private fun install(pack: GeofencePackage) { active = pack; onPack(pack) }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    private fun <T> execute(action: () -> T, success: (T) -> Unit, failure: (() -> Unit)? = null) {
        if (busy) return
        busy = true; button.isEnabled = false
        worker.execute {
            val result = runCatching(action)
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                busy = false; button.isEnabled = true
                result.onSuccess(success).onFailure {
                    failure?.invoke()
                    toast("操作失败：${it.message ?: "请检查文件和存储空间"}")
                }
                if (!busy) pendingResult?.let {
                    pendingResult = null
                    onActivityResult(it.first, it.second, it.third)
                }
            }
        }
    }
    companion object { private const val IMPORT = 810; private const val EXPORT = 811 }
}
