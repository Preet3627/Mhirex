package com.mhirex.editor.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import android.content.MutableContextWrapper
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.mhirex.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * not have a Google account. [SignInResult.NotConfigured] is therefore a surfaced state, not a hard
 * failure.
 *
 * Requests only the `email` and `profile` scopes, which is the minimum for a display name and avatar.
 * No Drive, Contacts, or Photos scope is requested -- Mhirex has no use for them, and asking for
 * access the app does not need is both a trust problem and a review problem.
 *
 * ## Why this runs two flows
 *
 * Google documents two distinct flows, and they are not interchangeable:
 *
 *  - The **bottom sheet** ([GetGoogleIdOption]) is Credential Manager's built-in account sheet. It only
 *    offers accounts that have *already* granted this app access, and per Google's own troubleshooting
 *    table it "does not appear if Sign-in prompts are disabled for any account on the device"
 *    (Google Account Settings > Sign in with Google). When it cannot offer anything it throws
 *    [NoCredentialException] and the user sees nothing at all.
 *  - The **button flow** ([GetSignInWithGoogleOption]) always presents the full account chooser, and
 *    Google states explicitly that the disabled-prompts setting "does not impact the button flow". It
 *    is the documented flow for exactly the cases that break the bottom sheet: no accounts on the
 *    device, accounts needing reauthentication, and the user dismissing the sheet.
 *
 * An earlier version of this class used [GetGoogleIdOption] with `setFilterByAuthorizedAccounts(false)`
 * for a button-triggered sign-in. That is the wrong flow for the entry point: `false` asks for
 * *unauthorised* accounts, but they are still rendered in the bottom sheet, so the sheet can still
 * refuse to appear and the user just saw "No Google account is available on this device" with no
 * chooser to pick from. Sign-in is now a button, so it uses the button flow.
 *
 * The bottom sheet is still tried first so a returning, already-authorised user gets the seamless
 * one-tap path; it is escalated to the button flow on the two outcomes Google says should escalate.
 */
class GoogleSignInManager(private val context: Context) {

    /**
     * Created with the *application* context on purpose.
     *
     * [ProfileBinder] is constructed with the Activity so Credential Manager can host its chooser, and
     * holding that Activity in a field for the lifetime of the binder leaks it across configuration
     * changes. Only [getCredential] needs an Activity-derived context, and that is supplied per call
     * via a [android.view.ContextWrapper] below.
     */
    private val credentialManager = CredentialManager.create(context.applicationContext)

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
        scope.launch {
            // Stage 1: seamless bottom sheet for an already-authorised account. Stage 2, the button
            // flow, runs only when stage 1 could not offer an account or the user dismissed it.
            val result = runBottomSheetFlow(activity, clientId)
                ?: runButtonFlow(activity, clientId)
                ?: SignInResult.Error(
                    context.getString(R.string.str_signin_error_no_account),
                )

            onResult(result)
        }
    }

    /**
     * The bottom-sheet stage.
     *
     * Returns `null` to mean "no account could be offered, escalate to the button flow" -- that is, on
     * [NoCredentialException] (nothing authorised on the device) or [GetCredentialCancellationException]
     * (the user dismissed the sheet, which Google lists as a reason to switch to the button flow).
     * Any other failure is returned as a terminal [SignInResult] because retrying with a different
     * flow would not help.
     */
    private suspend fun runBottomSheetFlow(
        activity: Activity,
        clientId: String
    ): SignInResult? {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    // Only accounts that have already granted this app access, so a returning user is
                    // not asked to authorise twice.
                    .setFilterByAuthorizedAccounts(true)
                    .setServerClientId(clientId)
                    .setAutoSelectEnabled(true)
                    // No nonce: the ID token is never forwarded to a backend, so replay protection is
                    // not Mhirex's responsibility here. A nonce would be required if it were sent
                    // anywhere, and adding one unused would be misleading.
                    .build()
            )
            .build()

        return try {
            handleCredential(getCredential(activity, request))
        } catch (e: NoCredentialException) {
            Log.i(TAG, "No authorised account for the bottom sheet; escalating to the button flow", e)
            null
        } catch (e: GetCredentialCancellationException) {
            Log.i(TAG, "Bottom sheet dismissed; escalating to the button flow", e)
            null
        } catch (e: GetCredentialException) {
            terminalError(e)
        }
    }

    /**
     * The button stage: the full-screen account chooser.
     *
     * This is the flow Google's guide prescribes for a button, and the one that works when the bottom
     * sheet declines to render. It ignores the "Sign in with Google" prompt setting.
     */
    private suspend fun runButtonFlow(
        activity: Activity,
        clientId: String
    ): SignInResult? {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(serverClientId = clientId)
                    .build()
            )
            .build()

        return try {
            handleCredential(getCredential(activity, request))
        } catch (e: NoCredentialException) {
            // The device genuinely has no usable Google account: nothing added, or every added
            // account needs to reauthenticate. Retrying cannot fix that, so it is reported to the user
            // with an actionable message rather than swallowed.
            Log.w(TAG, "The account chooser found no usable Google account", e)
            null
        } catch (e: GetCredentialCancellationException) {
            // This is ambiguous and the log says so, because getting it wrong costs hours.
            //
            // Genuine case: the user backed out of the chooser, which is a normal outcome.
            //
            // Observed case: Google Play Services closed its own sign-in Activity with a hard error and
            // Credential Manager reports that as a cancellation. Seen in the wild as
            //   Auth.Api.Credentials: [8] Unknown error [status=UNREGISTERED_ON_API_CONSOLE]
            //   Auth.Api.Credentials: [16] Account reauth failed
            // which happens when the app's *signing certificate* is not registered against the OAuth
            // client in the Google Cloud console. A debug build signed with the local debug keystore is
            // the usual cause, because only the release certificate gets registered.
            //
            // The app deliberately shows nothing here: if a user with a working account dismisses the
            // chooser, an error dialog would be both wrong and annoying, and Credential Manager gives
            // no way to tell the two cases apart. So the failure is silent to the user by design and
            // has to be diagnosed from this log. To confirm, run:
            //   adb logcat -s Auth.Api.Credentials
            // and look for UNREGISTERED_ON_API_CONSOLE or DEVELOPER_ERROR.
            Log.w(
                TAG,
                "Button flow returned cancellation. Either the user dismissed the chooser, or Google " +
                    "Play Services failed and closed its own UI -- check `adb logcat -s " +
                    "Auth.Api.Credentials` for UNREGISTERED_ON_API_CONSOLE or DEVELOPER_ERROR, which " +
                    "both mean this build's signing certificate is not registered on the OAuth client."
            )
            SignInResult.Cancelled
        } catch (e: GetCredentialException) {
            terminalError(e)
        }
    }

    /**
     * Runs [request], wrapping the Activity so a configuration change cannot leak it.
     *
     * [MutableContextWrapper] specifically, not a plain [ContextWrapper]: Google's implementation
     * guide prescribes it because Credential Manager updates the wrapper's base context when the host
     * Activity is reconstructed, which is what keeps the system chooser from launching against a dead
     * Activity. A fresh wrapper is created per call, so there is no state to carry between attempts.
     */
    private suspend fun getCredential(
        activity: Activity,
        request: GetCredentialRequest
    ) = credentialManager.getCredential(
        context = MutableContextWrapper(activity),
        request = request
    )

    /** Maps the non-escalating [GetCredentialException] subtypes onto user-facing results. */
    private fun terminalError(e: GetCredentialException): SignInResult.Error = when (e) {
        // No provider (e.g. Play Services missing or outdated). Common on de-Googled devices.
        is GetCredentialProviderConfigurationException -> {
            Log.e(TAG, "No credential provider available", e)
            SignInResult.Error(context.getString(R.string.str_signin_error_unavailable), e)
        }
        is GetCredentialInterruptedException -> {
            Log.w(TAG, "Credential request interrupted", e)
            SignInResult.Error(context.getString(R.string.str_signin_error_interrupted), e)
        }
        is GetCredentialUnsupportedException -> {
            Log.e(TAG, "Requested credential type unsupported", e)
            SignInResult.Error(context.getString(R.string.str_signin_error_unavailable), e)
        }
        is GetCredentialUnknownException -> {
            Log.e(TAG, "Unknown credential error", e)
            SignInResult.Error(context.getString(R.string.str_signin_error_generic), e)
        }
        else -> {
            Log.e(TAG, "getCredential failed", e)
            SignInResult.Error(context.getString(R.string.str_signin_error_generic), e)
        }
    }

    /**
     * Turns a credential response into a stored [UserProfile].
     *
     * `getCredential` returns a [CustomCredential]; the typed [GoogleIdTokenCredential] has to be
     * constructed from its data bundle. An earlier version of this method tested
     * `response.credential is GoogleIdTokenCredential`, which is **never** true for a
     * [CustomCredential], so every successful sign-in would have been reported as an unexpected
     * credential type. That latent bug was hidden behind the "no account" failure above.
     *
     * Both credential subtypes are accepted: the bottom sheet yields
     * [GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL] and the button flow yields
     * [GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL].
     */
    private suspend fun handleCredential(
        response: androidx.credentials.GetCredentialResponse
    ): SignInResult {
        val credential = response.credential
        if (credential !is CustomCredential) {
            Log.w(TAG, "Unexpected credential class ${credential::class.java.simpleName}")
            return SignInResult.Error(context.getString(R.string.str_signin_error_unexpected_credential))
        }
        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL &&
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
        ) {
            Log.w(TAG, "Unexpected credential type ${credential.type}")
            return SignInResult.Error(context.getString(R.string.str_signin_error_unexpected_credential))
        }

        val parsed = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "Could not parse the Google ID token response", e)
            return SignInResult.Error(context.getString(R.string.str_signin_error_generic), e)
        }

        // Property names verified against googleid-1.1.1 via javap: the email is exposed as `id`
        // and the avatar as `profilePictureUri`. There is no `email`/`photoUrl` property -- the
        // readable names live on the GoogleIdTokenCredential's own class, not the credential types.
        //
        // `id` is the account email, not an opaque subject id: for the `email` scope Google returns
        // the address as the credential's id field.
        val profile = UserProfile(
            displayName = parsed.displayName,
            email = parsed.id,
            photoUrl = parsed.profilePictureUri?.toString()
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
     * Clears the stored profile, deletes the cached avatar, and clears the credential provider state.
     *
     * `clearCredentialState()` is the part that is easy to miss: without it a credential provider can
     * keep an active session and constrain the next sign-in to the same account, so a user who signed
     * out to switch accounts is not offered the others. Google documents this call for exactly this
     * case. It is best-effort -- local cleanup must still complete if the provider refuses -- and it
     * does not revoke the scopes the user previously granted.
     */
    fun signOut(scope: CoroutineScope, onComplete: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                // credentials:1.3.0 has no no-argument overload, so the request is explicit.
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear credential provider state; local sign-out continues", e)
            }
            UserProfileStore.clear(context)
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    companion object {
        private const val TAG = "GoogleSignInManager"
        private const val APP_ID_SUFFIX = "apps.googleusercontent.com"
    }
}
