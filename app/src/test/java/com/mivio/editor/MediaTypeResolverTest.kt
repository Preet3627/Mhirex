package com.mivio.editor

import com.mivio.editor.utils.MediaTypeResolver
import com.mivio.editor.utils.OverlayMediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTypeResolverTest {

    @Test
    fun providerMimeTypeWinsOverCachedSuffix() {
        assertEquals(
            OverlayMediaType.IMAGE,
            MediaTypeResolver.classifyOverlay("image/png", "/cache/provider-file.gif"),
        )
        assertEquals(
            OverlayMediaType.GIF,
            MediaTypeResolver.classifyOverlay("image/gif; charset=binary", "/cache/provider-file.mp4"),
        )
        assertEquals(
            OverlayMediaType.VIDEO,
            MediaTypeResolver.classifyOverlay("video/mp4", "/cache/provider-file.png"),
        )
    }

    @Test
    fun suffixIsUsedWhenProviderMimeTypeIsMissingOrGeneric() {
        assertEquals(OverlayMediaType.GIF, MediaTypeResolver.classifyOverlay(null, "/cache/file.GIF"))
        assertEquals(OverlayMediaType.VIDEO, MediaTypeResolver.classifyOverlay(null, "/cache/file.mp4"))
        assertEquals(OverlayMediaType.IMAGE, MediaTypeResolver.classifyOverlay(null, "/cache/file.jpg"))
        assertEquals(
            OverlayMediaType.VIDEO,
            MediaTypeResolver.classifyOverlay("application/octet-stream", "/cache/file.mov"),
        )
    }

    @Test
    fun audioAndUnknownInputsAreNotLoopableOverlayVideo() {
        assertEquals(OverlayMediaType.OTHER, MediaTypeResolver.classifyOverlay("audio/mpeg", "/cache/file.mp4"))
        assertEquals(OverlayMediaType.OTHER, MediaTypeResolver.classifyOverlay(null, "/cache/file.bin"))
    }
}
