package com.damhee.trigger

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub에 올라온 최신 버전을 확인하고, 받아서 설치 화면까지 띄워 줌 */
object Updater {
    private const val LATEST = "https://github.com/yyundam/trigger/releases/latest"
    private const val APK = "https://github.com/yyundam/trigger/releases/latest/download/trigger.apk"
    private const val MIME = "application/vnd.android.package-archive"

    private var lastCheck = 0L
    var latest: Int = 0
        private set

    fun current(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    }

    /** 30분에 한 번만 확인. 새 버전이 있으면 그 번호를 onNew로 알려줌 (메인 스레드 아님) */
    fun check(ctx: Context, onNew: (Int) -> Unit) {
        val now = System.currentTimeMillis()
        if (latest > current(ctx)) {
            onNew(latest)
            return
        }
        if (now - lastCheck < 30 * 60_000L) return
        lastCheck = now
        val cur = current(ctx)
        Thread {
            try {
                val c = URL(LATEST).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.requestMethod = "HEAD"
                val loc = c.getHeaderField("Location") ?: ""
                c.disconnect()
                val v = Regex("/tag/v(\\d+)").find(loc)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                latest = v
                if (v > cur) onNew(v)
            } catch (_: Exception) {
                lastCheck = 0L // 인터넷이 없었으면 다음에 다시 확인
            }
        }.start()
    }

    fun startDownload(ctx: Context, version: Int): Long {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        dir?.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }
        val name = "trigger-v$version.apk"
        File(dir, name).delete()
        val req = DownloadManager.Request(Uri.parse(APK))
            .setTitle("트리거 v$version")
            .setMimeType(MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name)
        return ctx.getSystemService(DownloadManager::class.java).enqueue(req)
    }

    /** 0~100 진행률, 완료면 101, 실패면 -1 */
    fun progress(ctx: Context, id: Long): Int {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return -1
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL) return 101
            if (status == DownloadManager.STATUS_FAILED) return -1
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        }
        return -1
    }

    fun install(ctx: Context, id: Long): Boolean {
        val uri = ctx.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(id) ?: return false
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 안 되면 브라우저로 받기 */
    fun openInBrowser(ctx: Context) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
