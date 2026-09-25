package com.mhirex.editor.utils

import java.util.Locale

/**
 * Classification for a media overlay input.
 *
 * The resolver MIME type is authoritative when it is a meaningful media type. A
 * cached file name is only a fallback because content providers can return a
 * generic MIME type (or no MIME type at all) for a valid document URI.
 */
enum class OverlayMediaType {
    IMAGE,
    GIF,
    VIDEO,
    OTHER
}

object MediaTypeResolver {

    private val videoExtensions = setOf("mp4", "mkv", "mov", "3gp", "webm", "avi", "m4v")
    private val gifExtensions = setOf("gif")
    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "heic", "heif", "bmp")

    fun classifyOverlay(mimeType: String?, path: String?): OverlayMediaType {
        val normalizedMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)

        // A concrete image/video/audio MIME type wins over a misleading cached
        // suffix. In particular, image/png must never become a GIF or video just
        // because the provider's temporary file happened to be named .gif/.mp4.
        when {
            normalizedMime == "image/gif" -> return OverlayMediaType.GIF
            normalizedMime?.startsWith("video/") == true -> return OverlayMediaType.VIDEO
            normalizedMime?.startsWith("image/") == true -> return OverlayMediaType.IMAGE
            normalizedMime?.startsWith("audio/") == true -> return OverlayMediaType.OTHER
        }

        val extension = path
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return when {
            extension in gifExtensions -> OverlayMediaType.GIF
            extension in videoExtensions -> OverlayMediaType.VIDEO
            extension in imageExtensions -> OverlayMediaType.IMAGE
            else -> OverlayMediaType.OTHER
        }
    }
}
