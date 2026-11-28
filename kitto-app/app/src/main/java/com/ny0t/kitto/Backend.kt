package com.ny0t.kitto

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.http.Interceptor
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kitto's link to its backend (M6 B6). The Claude API key lives only in the Cloud Function `claude`;
 * the app proves who is asking instead:
 *  - `Authorization: Bearer <Firebase ID token>`: which person (Google sign-in).
 *  - `X-Firebase-AppCheck: <token>`: that it's really the Kitto app.
 * The function then checks the allowlist and the daily limits and streams Claude's reply back unchanged,
 * so SdkConversation works exactly as before.
 */
object Backend {

    /** The SDK client, pointed at the function. No API key: the interceptor adds fresh tokens to every call. */
    fun client(): AnthropicClient =
        AnthropicOkHttpClient.builder()
            .baseUrl(BuildConfig.BACKEND_URL)
            .addInterceptor(tokens)
            .build()

    /**
     * Runs on the SDK's calling thread (Kitto streams on Dispatchers.IO), before every request and retry.
     * getIdToken(false) / getAppCheckToken(false) return the cached token and refresh it only when it's
     * about to expire, so this is quick after the first call.
     */
    private val tokens = Interceptor.syncOnly { next, request, options ->
        val user = FirebaseAuth.getInstance().currentUser ?: throw NotSignedInException()
        val idToken = try {
            Tasks.await(user.getIdToken(false)).token
        } catch (e: Exception) {
            throw NotSignedInException(e)
        } ?: throw NotSignedInException()
        val appCheckToken = try {
            Tasks.await(FirebaseAppCheck.getInstance().getAppCheckToken(false)).token
        } catch (e: Exception) {
            throw AppCheckException(e)
        }
        next.execute(
            request.toBuilder()
                .replaceHeaders("Authorization", "Bearer $idToken")
                .replaceHeaders("X-Firebase-AppCheck", appCheckToken)
                .build(),
            options,
        )
    }

    fun isSignedIn(): Boolean = FirebaseAuth.getInstance().currentUser != null

    /**
     * Google sign-in with Credential Manager, then into Firebase. Needs an Activity (it shows Google's
     * account picker). Throws if the person cancels or sign-in fails.
     */
    suspend fun signIn(activity: Activity) {
        val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(activity).getCredential(activity, request)
        val google = GoogleIdTokenCredential.createFrom(result.credential.data)
        val credential = GoogleAuthProvider.getCredential(google.idToken, null)
        withContext(Dispatchers.IO) { Tasks.await(FirebaseAuth.getInstance().signInWithCredential(credential)) }
    }
}

class NotSignedInException(cause: Throwable? = null) : Exception("Not signed in", cause)

class AppCheckException(cause: Throwable) : Exception("App Check token unavailable", cause)
