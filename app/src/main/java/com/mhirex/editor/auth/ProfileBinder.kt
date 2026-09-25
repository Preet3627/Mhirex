package com.mhirex.editor.auth

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.mhirex.editor.R
import com.mhirex.editor.databinding.ActivitySettingsProfileBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the Settings profile card from stored state.
 *
 * Rendering is a pure function of ([UserProfileStore] content + in-flight state) so the card cannot
 * disagree with itself: [render] is the single place that decides which of the mutually exclusive
 * views is visible. The alternative -- toggling visibility at each call site -- is what produces the
 * classic bug where a sign-out leaves the avatar visible next to a "Not signed in" label.
 *
 * The avatar is decoded on a background dispatcher by [AvatarCache.load]; a 256px decode is cheap but
 * touching a bitmap's pixels on the main thread during a layout pass is a real source of jank.
 */
class ProfileBinder(
    private val context: Context,
    private val binding: ActivitySettingsProfileBinding
) {

    private val signInManager = GoogleSignInManager(context)

    private var scope: CoroutineScope? = null

    /** Attached so the card can trigger sign-in and observe the result. */
    fun attach(scope: CoroutineScope) {
        this.scope = scope
        binding.btnSignInWithGoogle.setOnClickListener { startSignIn() }
        binding.btnSignOut.setOnClickListener { signOut() }
        render()
    }

    /**
     * Renders the card from stored state.
     *
     * Reads the profile fresh each time rather than caching it, so the card is correct after a
     * sign-out elsewhere in the app without needing an invalidation hook.
     */
    fun render() {
        val profile = UserProfileStore.load(context)
        if (profile == null) renderSignedOut() else renderSignedIn(profile)
    }

    private fun renderSignedOut() {
        binding.tvProfileName.setText(R.string.str_profile_signed_out)
        binding.tvProfileEmail.setText(R.string.str_profile_signed_out_desc)
        binding.tvProfileInitials.text = ""
        binding.tvProfileInitials.visibility = View.GONE
        binding.ivProfileIconSignedOut.visibility = View.VISIBLE
        binding.ivProfilePhoto.visibility = View.GONE
        binding.tvProfileLocalOnly.visibility = View.GONE
        binding.btnSignInWithGoogle.visibility = View.VISIBLE
        binding.btnSignOut.visibility = View.GONE
    }

    private fun renderSignedIn(profile: UserProfile) {
        val name = profile.displayNameOrEmail
        binding.tvProfileName.text = name ?: context.getString(R.string.str_profile_signed_out)
        binding.tvProfileEmail.text = profile.email.orEmpty()

        // Initials are the fallback for a missing or failed avatar download. A Google account with no
        // profile name falls back to the email's first two characters rather than showing a blank face.
        val initials = profile.initials
        if (initials != null) {
            binding.tvProfileInitials.text = initials
            binding.tvProfileInitials.contentDescription =
                context.getString(R.string.str_profile_avatar_placeholder_desc)
            binding.tvProfileInitials.visibility = View.VISIBLE
        } else {
            binding.tvProfileInitials.text = ""
            binding.tvProfileInitials.visibility = View.GONE
        }
        binding.ivProfileIconSignedOut.visibility = View.GONE

        binding.tvProfileLocalOnly.visibility = View.VISIBLE
        binding.btnSignInWithGoogle.visibility = View.GONE
        binding.btnSignOut.visibility = View.VISIBLE

        // The photo is only set when one is actually cached, so a failed download leaves the
        // initials visible instead of an empty frame.
        binding.ivProfilePhoto.visibility = if (profile.cachedPhoto != null) View.VISIBLE else View.GONE
        if (profile.cachedPhoto != null) {
            binding.ivProfilePhoto.contentDescription = name?.let {
                context.getString(R.string.str_profile_photo_desc, it)
            }
            loadAvatarAsync()
        }
    }

    private fun loadAvatarAsync() {
        val scope = this.scope ?: return
        scope.launch(Dispatchers.IO) {
            val bitmap = AvatarCache.load(context)
            withContext(Dispatchers.Main) {
                // Re-check sign-in state: the user may have signed out while the decode was in
                // flight, and re-showing a photo after sign-out would resurrect a personal image.
                if (bitmap != null && UserProfileStore.isSignedIn(context)) {
                    binding.ivProfilePhoto.setImageBitmap(bitmap)
                    binding.ivProfilePhoto.visibility = View.VISIBLE
                    binding.tvProfileInitials.visibility = View.GONE
                }
            }
        }
    }

    private fun startSignIn() {
        val scope = this.scope ?: return
        setBusy(true)
        signInManager.signIn(requireActivity(), scope) { result ->
            setBusy(false)
            when (result) {
                is SignInResult.Success -> {
                    renderSignedIn(result.profile)
                    profileShown?.invoke(result.profile)
                }
                // Dismissing the chooser is a normal outcome, not a failure: show nothing, keep the
                // card exactly as it was. Reporting it as an error is what makes people distrust the
                // button.
                is SignInResult.Cancelled -> Log.i("ProfileBinder", "Sign-in dismissed by user")
                is SignInResult.NotConfigured -> {
                    renderSignedOut()
                    showError(context.getString(R.string.str_signin_not_configured))
                }
                is SignInResult.Error -> {
                    renderSignedOut()
                    showError(result.message)
                }
            }
        }
    }

    private fun signOut() {
        val scope = this.scope ?: return
        setBusy(true)
        signInManager.signOut(scope) {
            binding.ivProfilePhoto.setImageDrawable(null)
            renderSignedOut()
            setBusy(false)
            profileCleared?.invoke()
        }
    }

    /**
     * Disables both buttons and shows the spinner.
     *
     * Without this, a double tap launches two concurrent Credential Manager requests, which is the
     * usual cause of a "sign-in already in progress" error.
     */
    private fun setBusy(busy: Boolean) {
        binding.progressSignIn.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnSignInWithGoogle.isEnabled = !busy
        binding.btnSignOut.isEnabled = !busy
    }

    private fun showError(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Credential Manager requires an Activity context to host its selection UI; a plain Context
     * throws. Throwing here rather than passing the application context down keeps the requirement
     * visible at the call site.
     */
    private fun requireActivity(): android.app.Activity =
        context as? android.app.Activity
            ?: error("ProfileBinder must be constructed with an Activity context to host sign-in")

    /** Notified after a successful sign-in so the host can refresh other surfaces. */
    var profileShown: ((UserProfile) -> Unit)? = null

    /** Notified after sign-out so the host can refresh other surfaces. */
    var profileCleared: (() -> Unit)? = null
}
