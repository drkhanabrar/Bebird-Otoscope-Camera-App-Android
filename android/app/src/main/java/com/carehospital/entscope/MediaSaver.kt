package com.carehospital.entscope

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes captures into the phone's shared media library so they show up in the
 * gallery, under Pictures/CARE ENT Scope and Movies/CARE ENT Scope.
 */
object MediaSaver {

    private const val ALBUM = "CARE ENT Scope"

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    // --------------------------------------------------------------- images
    fun saveJpeg(context: Context, bitmap: Bitmap, quality: Int = 95): String? {
        val name = "CARE_ENT_Scope_${stamp()}.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            return try {
                resolver.openOutputStream(uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
                } ?: return null
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                name
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        }

        // Android 9 and older
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ALBUM
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
            }
            scan(context, file)
            name
        } catch (e: Exception) {
            null
        }
    }

    // --------------------------------------------------------------- videos
    /** Moves a finished recording out of the cache into the media library. */
    fun publishVideo(context: Context, source: File): String? {
        val name = "CARE_ENT_Scope_${stamp()}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$ALBUM"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            return try {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(source).use { input -> input.copyTo(out) }
                } ?: return null
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                source.delete()
                name
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        }

        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                ALBUM
            )
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, name)
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            source.delete()
            scan(context, target)
            name
        } catch (e: Exception) {
            null
        }
    }

    private fun scan(context: Context, file: File) {
        try {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), null, null
            )
        } catch (_: Exception) { }
    }
}
