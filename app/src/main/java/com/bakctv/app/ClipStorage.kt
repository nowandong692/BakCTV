package com.bakctv.app

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

/**
 * 녹화 파일 저장 위치 관리.
 * - 안드로이드 9 이하(갤럭시 S8): 공용 "Movies/박CTV" 폴더 (저장 권한 필요, 없으면 앱 전용 폴더)
 * - 안드로이드 10 이상: MediaStore 로 "Movies/박CTV" 에 저장 (권한 불필요)
 * 저장된 영상은 갤러리의 "박CTV" 앨범에 보인다.
 */
object ClipStorage {

    private const val FOLDER = "박CTV"
    private const val CAP_BYTES = 2L * 1024 * 1024 * 1024   // 2GB 넘으면 오래된 것부터 삭제

    class Target(val muxer: MediaMuxer, val name: String, val finish: (ok: Boolean) -> Unit)

    fun open(ctx: Context, name: String): Target? = try {
        if (Build.VERSION.SDK_INT >= 29) openMediaStore(ctx, name) else openFile(ctx, name)
    } catch (_: Exception) { null }

    private fun legacyDir(ctx: Context): File {
        val canWrite = ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val base = if (canWrite) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                   else ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        return File(base, FOLDER).apply { mkdirs() }
    }

    private fun openFile(ctx: Context, name: String): Target {
        val file = File(legacyDir(ctx), name)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return Target(muxer, name) { ok ->
            if (ok) MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
            else file.delete()
        }
    }

    private fun openMediaStore(ctx: Context, name: String): Target {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("저장 위치를 만들 수 없음")
        val pfd: ParcelFileDescriptor = cr.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("파일을 열 수 없음")
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return Target(muxer, name) { ok ->
            try { pfd.close() } catch (_: Exception) { }
            if (ok) {
                cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } else {
                cr.delete(uri, null, null)
            }
        }
    }

    /** 전체 용량이 2GB 를 넘으면 오래된 영상부터 삭제 */
    fun enforceCap(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 29) capMediaStore(ctx) else capFiles(ctx)
        } catch (_: Exception) { }
    }

    private fun capFiles(ctx: Context) {
        val files = listOfNotNull(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER),
            ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let { File(it, FOLDER) },
        ).flatMap { it.listFiles { f -> f.name.endsWith(".mp4") }?.toList() ?: emptyList() }
            .sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CAP_BYTES) break
            total -= f.length()
            if (f.delete()) MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null)
        }
    }

    private fun capMediaStore(ctx: Context) {
        val cr = ctx.contentResolver
        val items = mutableListOf<Pair<Long, Long>>()   // id, size
        cr.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE),
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_MOVIES}/$FOLDER%"),
            "${MediaStore.Video.Media.DATE_ADDED} ASC"
        )?.use { c ->
            while (c.moveToNext()) items += c.getLong(0) to c.getLong(1)
        }
        var total = items.sumOf { it.second }
        for ((id, size) in items) {
            if (total <= CAP_BYTES) break
            total -= size
            cr.delete(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), null, null)
        }
    }
}
