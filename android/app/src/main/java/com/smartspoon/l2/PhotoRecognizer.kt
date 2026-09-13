package com.smartspoon.l2

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.io.File

/**
 * 拍照 / 选图 / 上传识别。
 *
 * 这套流程原来是 `MainActivity` 的私有段落（`takePhoto` / `pickImage` / `onActivityResult`
 * / `uploadForRecognition` / `showRecognizeResult`）。加菜页拆成独立 Activity 之后
 * 两边都要用它，于是收成这个小助手：**谁的界面里有「拍照识别菜品」，谁就持有一份**。
 *
 * 照片与识别的结果都只写全局 [State]，所以两个宿主共用同一份行为，不会各写一半。
 */
class PhotoRecognizer(private val activity: ComponentActivity) {

    private var pendingCameraUri: Uri? = null

    /**
     * 相机权限改用 Activity Result API：
     * `ComponentActivity` 上的 `onRequestPermissionsResult` 已不能这样覆盖了。
     */
    private val cameraPermission = activity.registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) takePhoto() else AppCore.toast("未授予摄像头权限，可改用「选择图片」")
    }

    /** 识别弹窗的「📷 拍照」。 */
    fun takePhoto() {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        val file = File(activity.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        pendingCameraUri = ShotProvider.uriFor(file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            activity.startActivityForResult(intent, REQ_CAMERA)
        } catch (e: ActivityNotFoundException) {
            AppCore.toast("没有可用的相机应用")
        }
    }

    /** 识别弹窗的「选择图片」。 */
    fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            activity.startActivityForResult(Intent.createChooser(intent, "选择图片"), REQ_PICK_IMAGE)
        } catch (e: ActivityNotFoundException) {
            AppCore.toast("没有可用的图库应用")
        }
    }

    /**
     * 宿主在 `onActivityResult` 里转发过来。
     * 返回 true 表示这次结果属于拍照 / 选图，已经处理掉了。
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_CAMERA && requestCode != REQ_PICK_IMAGE) return false
        if (resultCode != Activity.RESULT_OK) return true
        val uri: Uri = when (requestCode) {
            REQ_CAMERA -> pendingCameraUri
            else -> data?.data
        } ?: return true
        val bytes = readBytes(uri) ?: run {
            AppCore.toast("读取图片失败")
            return true
        }
        uploadForRecognition(bytes)
        return true
    }

    private fun readBytes(uri: Uri): ByteArray? = try {
        if (uri.scheme == "file") {
            File(uri.path!!).readBytes()
        } else {
            activity.contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap == null) null else {
                    val out = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                    out.toByteArray()
                }
            }
        }
    } catch (e: Exception) {
        Log.w("SmartSpoon", "read image failed", e)
        null
    }

    private fun uploadForRecognition(bytes: ByteArray) {
        State.dialog = AppDialog.Recognizing
        Thread {
            val json = Api.recognize(State.server, bytes, "camera.jpg")
            activity.runOnUiThread { showRecognizeResult(json) }
        }.start()
    }

    /** 把识别返回的 JSON 解析成弹窗要的数据；失败与「没有 data」的分支与旧版一致。 */
    private fun showRecognizeResult(json: JSONObject?) {
        if (json == null || !json.optBoolean("ok")) {
            val message = json?.optString("error") ?: "识别失败：无法连接服务器"
            State.dialog = AppDialog.RecognizeResult(message, 0, emptyList())
            return
        }
        // 旧版这里是 `?: return`：ok 但没有 data 时什么都不弹（保留「正在识别…」）
        val data = json.optJSONObject("data") ?: return
        val results = data.optJSONArray("results")
        val items = mutableListOf<RecognizeItem>()
        if (results != null) {
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                items.add(
                    RecognizeItem(
                        name = item.optString("name"),
                        percent = item.optString("percent", "—"),
                        calorie = item.optDouble("calorie", 0.0),
                    )
                )
            }
        }
        State.dialog = AppDialog.RecognizeResult(null, data.optInt("count"), items)
    }

    private companion object {
        const val REQ_CAMERA = 1001
        const val REQ_PICK_IMAGE = 1002
    }
}
