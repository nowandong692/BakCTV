package com.bakctv.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 앱 업데이트: GitHub Releases 의 최신 버전을 확인하고, 새 버전이면 내려받아 설치 화면을 띄운다.
 * (APK 버전 번호 = GitHub Actions 빌드 번호, 릴리스 이름 = v1.<빌드 번호>)
 */
class AppUpdater(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private var pendingApk: File? = null

    val currentVersion: Long
        get() = PackageInfoCompat.getLongVersionCode(activity.packageManager.getPackageInfo(activity.packageName, 0))

    /** quiet=true: 새 버전이 있을 때만 알림 (앱 시작 시 자동 확인용) */
    fun check(quiet: Boolean) {
        Thread {
            val latest = try { fetchLatest() } catch (_: Exception) { null }
            main.post {
                if (activity.isFinishing) return@post
                when {
                    latest == null -> if (!quiet) toast("업데이트 정보를 가져오지 못했어요. 인터넷 연결을 확인해 주세요.")
                    latest.first > currentVersion -> askUpdate(latest.first, latest.second)
                    else -> if (!quiet) toast("최신 버전이에요 (1.$currentVersion)")
                }
            }
        }.start()
    }

    /** 설치 권한 설정 화면에서 돌아왔을 때 이어서 설치 */
    fun resumeIfPending() {
        val apk = pendingApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) return
        pendingApk = null
        install(apk)
    }

    private fun fetchLatest(): Pair<Long, String>? {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "BakCTV-App")
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name")                        // 예: v1.23
            val code = tag.substringAfterLast('.').toLongOrNull() ?: return null
            val assets = json.optJSONArray("assets")
            var url = APK_URL
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url", APK_URL); break }
            }
            return code to url
        } finally { conn.disconnect() }
    }

    private fun askUpdate(code: Long, url: String) {
        val recording = StreamService.latestStatus.running
        AlertDialog.Builder(activity)
            .setTitle("새 버전이 있어요")
            .setMessage(
                "박CTV 1.$code 으로 업데이트할까요? (지금 1.$currentVersion)" +
                    if (recording) "\n\n⚠ 설치하는 동안 촬영이 멈춰요. 설치 후 앱을 열어 다시 촬영을 시작해 주세요." else ""
            )
            .setPositiveButton("업데이트") { _, _ -> download(url) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun download(url: String) {
        val progress = AlertDialog.Builder(activity)
            .setTitle("내려받는 중…")
            .setMessage("0%")
            .setCancelable(false)
            .show()
        Thread {
            val file = File(File(activity.cacheDir, "updates").apply { mkdirs() }, "BakCTV.apk")
            val ok = try {
                var conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "BakCTV-App")
                conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                // 다른 주소로 넘겨주는 경우 직접 따라감
                var hops = 0
                while (conn.responseCode in 300..399 && hops < 5) {
                    val next = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    conn = URL(next).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "BakCTV-App")
                    hops++
                }
                if (conn.responseCode != 200) false else {
                    val total = conn.contentLengthLong
                    var done = 0L
                    var lastPct = -1
                    conn.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; main.post { progress.setMessage("$pct%") } }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    true
                }
            } catch (_: Exception) { false }
            main.post {
                try { progress.dismiss() } catch (_: Exception) { }
                if (ok) install(file) else toast("내려받기에 실패했어요. 잠시 후 다시 시도해 주세요.")
            }
        }.start()
    }

    private fun install(apk: File) {
        // 안드로이드 8 이상: '이 출처의 앱 설치 허용'이 필요
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            pendingApk = apk
            AlertDialog.Builder(activity)
                .setTitle("설치 허용이 필요해요")
                .setMessage("다음 화면에서 '이 출처 허용'을 켠 뒤 뒤로 돌아오면 설치가 이어집니다.")
                .setPositiveButton("설정 열기") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val API_URL = "https://api.github.com/repos/nowandong692/BakCTV/releases/latest"
        private const val APK_URL = "https://github.com/nowandong692/BakCTV/releases/latest/download/BakCTV.apk"
    }
}
