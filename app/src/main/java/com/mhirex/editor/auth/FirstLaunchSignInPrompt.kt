package com.mhirex.editor.auth

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mhirex.editor.R
import com.mhirex.editor.databinding.ActivitySettingsProfileBinding
import kotlinx.coroutines.CoroutineScope

/**
 * Shows the optional "add your profile" prompt once, on first launch only.
 *
 * **Optional by design.** Mhirex is a local video editor; no part of it needs an account, and Google
 * Play requires core functionality to stay available without one. Blocking the editor behind
 * sign-in would be both a policy problem and a real usability loss for users without a Google
 * account, so the dialog always offers a dismiss path and "Not now" is the visually equal option.
 *
 * The prompt is shown **at most once**, tracked by [KEY_PROMPT_SHOWN]. A dismissed prompt must never
 * reappear: a nag dialog on every launch is the fastest way to get an app uninstalled, and it would
 * also make the first-launch experiment untestable.
 */
object FirstLaunchSignInPrompt {

    private const val TAG = "FirstLaunchSignInPrompt"
    private const val KEY_PROMPT_SHOWN = "signin_prompt_shown"

    /** Test seam: forces the prompt to appear again. Not wired to any UI. */
    internal fun reset(context: Context) {
        context.getSharedPreferences(com.mhirex.editor.Branding.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_PROMPT_SHOWN).apply()
    }

    fun maybeShow(activity: AppCompatActivity, scope: CoroutineScope) {
        val prefs = activity.getSharedPreferences(
            com.mhirex.editor.Branding.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        if (prefs.getBoolean(KEY_PROMPT_SHOWN, false)) return

        // Mark as shown *before* building the dialog, not in its dismiss handler. If the Activity is
        // destroyed while the dialog is up, the mark still stands; otherwise a rotation mid-prompt
        // would re-show it forever.
        prefs.edit().putBoolean(KEY_PROMPT_SHOWN, true).apply()

        // The dialog hosts a full profile card, so it inflates the same layout the Settings tab uses.
        // One layout means the prompt and the settings entry cannot disagree about copy or behaviour.
        val binding = ActivitySettingsProfileBinding.inflate(activity.layoutInflater)
        val binder = ProfileBinder(activity, binding)
        binder.attach(scope)

        // The card inside a dialog already offers sign-in, so the dialog needs no extra buttons: the
        // user either signs in, or dismisses by tapping outside / back. That is the whole decision.
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.str_signin_optional_title)
            .setView(binding.root)
            .setMessage(R.string.str_signin_optional_body)
            .setNegativeButton(R.string.str_signin_not_now) { dialog, _ ->
                Log.i(TAG, "User declined the optional sign-in prompt")
                dialog.dismiss()
            }
            .setOnCancelListener { Log.i(TAG, "Sign-in prompt dismissed without a choice") }
            .show()
    }
}
