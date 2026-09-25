package com.mivio.editor.utils

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable

/**
 * Typed access to a [Parcelable] extra, on every supported API level.
 *
 * `Intent.getParcelableExtra(String)` is deprecated as of API 33 because the untyped return cannot
 * tell you what you actually got back, and the framework's own migration note is that the old
 * behaviour can hand you an object of the wrong type. On API 33+ the typed overload
 * `getParcelableExtra(String, Class<T>)` exists, but it does not compile against `minSdk 26`
 * without a version guard, so every call site ends up with its own inline `if (SDK_INT >= 33)`.
 *
 * That repetition is where mistakes live, so it is centralised here. `reified T` means the compiler
 * checks the cast against the declared type, which is the entire point of the typed overload — a
 * plain `as? Uri` on the legacy path gives the same guarantee.
 */
inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name) as? T
    }

/** [parcelableExtraCompat] for the `Uri` extras used throughout the editor. */
fun Intent.uriExtraCompat(name: String): Uri? = parcelableExtraCompat<Uri>(name)
