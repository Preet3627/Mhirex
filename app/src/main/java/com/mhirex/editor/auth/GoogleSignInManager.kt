package com.mhirex.editor.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.mhirex.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Outcome of a sign-in attempt.
 *
 * Modelled as a sealed hierarchy rather than a nullable profile so the UI can tell three genuinely
 * different situations apart: the user backed out ([Cancelled], which must not be shown as an error),
 * sign-in is impossible because the app is misconfigured ([NotConfigured], a developer problem), and
 * the attempt genuinely failed ([Error]). Collapsing these into a single failure path is what produces
 * the common bug of an "error" dialog shown every time the user presses Back.
 */
sealed interface SignInResult {
    data class Success(val profile: UserProfile) : SignInResult
    data object Cancelled : SignInResult
    data object NotConfigured : SignInResult
    data class Error(val message: String, val cause: Throwable? = null) : SignInResult
}

/**
 * Wraps Credential Manager for "Sign in with Google".
 *
 * Sign-in is **optional** in Mhirex. Nothing in the editor is gated behind an account: the app is a
 * local video editor, and Google Play requires core functionality to remain available to users who do
 * not have a Google account. [NotConfigured] is therefore a surfaced state, not a hard failure.
 *
 * Requests only the `email` and `profile` scopes, which is the minimum for a display name and avatar.
 * No Drive, Contacts, or Photos scope is requested -- Mhirex has no use for them, and asking for
 * access the app does not need is both a trust problem and a review problem.
 */
class GoogleSignInManager(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)

    /**
     * True when a web client ID is present.
     *
     * Read from the generated resource rather than hardcoded, so a build without Firebase credentials
     * degrades to "not configured" instead of throwing at the point of use.
     */
    private fun isConfigured(): Boolean =
        runCatching { context.getString(R.string.default_web_client_id) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it.endsWith(APP_ID_SUFFIX) }
            ?.isNotEmpty() == true

    /**
     * Launches the account chooser and stores the resulting profile.
     *
     * Must be called from an Activity context so Credential Manager can host its selection UI.
     * [onResult] is delivered on the main thread.
     */
    fun signIn(activity: Activity, scope: CoroutineScope, onResult: (SignInResult) -> Unit) {
        if (!isConfigured()) {
            Log.w(TAG, "Sign-in requested but no web client ID is configured")
            onResult(SignInResult.NotConfigured)
            return
        }

        val clientId = context.getString(R.string.default_web_client_id)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(clientId)
                    // No nonce: the ID token is never forwarded to a backend, so replay protection
                    // is not Mhirex's responsibility here. A nonce would be required if it were sent
                    // anywhere, and adding one unused would be misleading.
                    .setAutoSelectEnabled(false)
                    .build()
            )
            .build()

        scope.launch {
            val result = try {
                // getCredential is itself a suspending call that presents UI, so it must not be
                // wrapped in withContext(IO) -- that would move Activity work off the main thread.
                val response = credentialManager.getCredential(context = activity, request = request)
                handleCredential(response)
            } catch (e: GetCredentialCancellationException) {
                // The user dismissed the chooser. Not an error and must not surface as one.
                Log.i(TAG, "Sign-in cancelled by user")
                SignInResult.Cancelled
            } catch (e: NoCredentialException) {
                Log.w(TAG, "No Google account available on this device", e)
                SignInResult.Error("No Google account is available on this device.", e)
            } catch (e: GetCredentialProviderConfigurationException) {
                // No provider (e.g. Play Services missing or outdated). Common on de-Googled devices.
                Log.e(TAG, "No credential provider available", e)
                SignInResult.Error("Google Sign-In is not available on this device.", e)
            } catch (e: GetCredentialInterruptedException) {
                Log.w(TAG, "Credential request interrupted", e)
                SignInResult.Error("Sign-in was interrupted. Please try again.", e)
            } catch (e: GetCredentialUnsupportedException) {
                Log.e(TAG, "Requested credential type unsupported", e)
                SignInResult.Error("Google Sign-In is not supported here.", e)
            } catch (e: GetCredentialUnknownException) {
                Log.e(TAG, "Unknown credential error", e)
                SignInResult.Error("Sign-in failed. Please try again.", e)
            } catch (e: GetCredentialException) {
                Log.e(TAG, "GetCredential failed", e)
                SignInResult.Error("Sign-in failed. Please try again.", e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected failure during sign-in", e)
                SignInResult.Error("Sign-in failed. Please try again.", e)
            }

            onResult(result)
        }
    }

    /**
     * Turns a credential response into a stored [UserProfile].
     *
     * A non-Google credential type is rejected explicitly rather than cast blindly: a
     * [ClassCastException] here would surface to the user as an unexplained crash.
     */
    private suspend fun handleCredential(response: androidx.credentials.GetCredentialResponse): SignInResult {
        val credential = response.credential
        if (credential !is GoogleIdTokenCredential) {
            Log.w(TAG, "Unexpected credential type ${credential::class.java.simpleName}")
            return SignInResult.Error("Sign-in returned an unexpected credential type.")
        }

        // Property names verified against googleid-1.1.1 via javap: the email is exposed as `id`
        // and the avatar as `profilePictureUri`. There is no `email`/`photoUrl` property -- the
        // readable names live on the GoogleIdTokenCredential's own class, not the credential types.
        //
        // `id` is the account email, not an opaque subject id: for the `email` scope Google returns
        // the address as the credential's id field.
        val profile = UserProfile(
            displayName = credential.displayName,
            email = credential.id,
            photoUrl = credential.profilePictureUri?.toString()
        )
        Log.i(TAG, "Sign-in succeeded for ${profile.email ?: "unknown email"}")

        // Download the avatar off the main thread, then persist. A failed download still yields a
        // usable profile, since the UI falls back to initials.
        val cached = profile.photoUrl?.let { AvatarCache.download(context, it) }
        val withAvatar = profile.copy(cachedPhoto = cached)
        withContext(Dispatchers.IO) { UserProfileStore.save(context, withAvatar) }
        return SignInResult.Success(withAvatar)
    }

    /**
     * Clears the stored profile and deletes the cached avatar.
     *
     * The cleanup runs on [Dispatchers.IO], but [onComplete] is delivered on the main thread. The
     * callback updates the profile card, so invoking it on the IO thread would touch Views off the
     * main thread and can crash the app during sign-out.
     */
    fun signOut(scope: CoroutineScope, onComplete: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            UserProfileStore.clear(context)
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    companion object {
        private const val TAG = "GoogleSignInManager"
        private const val APP_ID_SUFFIX = "apps.googleusercontent.com"
    }
}
