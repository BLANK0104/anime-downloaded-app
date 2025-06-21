package com.blank.anime.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.blank.anime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages AniList OAuth authentication
 */
class AniListAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "AniListAuthManager"
        private const val ANILIST_AUTH_ENDPOINT = "https://anilist.co/api/v2/oauth/authorize"
        private const val ANILIST_TOKEN_ENDPOINT = "https://anilist.co/api/v2/oauth/token"
        private const val PREF_NAME = "AniListAuth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"

        // Your actual AniList client secret
        private const val CLIENT_SECRET = "tlWptDHxeH7bOZJvMEbNATwcL3HtNcVryvRcnwRa"

        @Volatile
        private var INSTANCE: AniListAuthManager? = null

        fun getInstance(context: Context): AniListAuthManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AniListAuthManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val clientId: String by lazy { context.getString(R.string.anilist_client_id) }
    private val redirectUri: String by lazy { context.getString(R.string.anilist_redirect_uri) }

    // Check if the user is currently logged in
    fun isLoggedIn(): Boolean {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)

        // If token is expired but we have a refresh token, try to refresh silently
        if (accessToken != null && System.currentTimeMillis() >= expiresAt && refreshToken != null) {
            Log.d(TAG, "Token expired but refresh token exists, attempting silent refresh")
            // We don't block here, but initiate a refresh in the background
            refreshTokenSilently(refreshToken)
            // We'll still return false for now, but next check should succeed if refresh works
            return false
        }

        val isValid = accessToken != null && System.currentTimeMillis() < expiresAt
        Log.d(TAG, "isLoggedIn check: accessToken exists = ${accessToken != null}, " +
                "expires at = ${java.util.Date(expiresAt)}, isValid = $isValid")
        return isValid
    }

    // Try to refresh the token silently in the background
    private fun refreshTokenSilently(refreshToken: String) {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = refreshAccessToken(refreshToken)
                if (response.first != null) {
                    Log.d(TAG, "Silent token refresh successful")
                } else {
                    Log.e(TAG, "Silent token refresh failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during silent token refresh: ${e.message}")
            }
        }
    }

    // Check if token will expire soon (within 1 hour)
    fun tokenWillExpireSoon(): Boolean {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        val oneHourFromNow = System.currentTimeMillis() + (60 * 60 * 1000)
        return expiresAt > 0 && expiresAt < oneHourFromNow
    }

    // Get the current access token (if any), refreshing if needed
    fun getAccessToken(): String? {
        if (!isLoggedIn()) {
            Log.d(TAG, "getAccessToken: Not logged in")
            return null
        }

        // Check if token will expire soon, refresh if needed
        if (tokenWillExpireSoon()) {
            Log.d(TAG, "Token will expire soon, attempting to refresh")
            val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
            if (refreshToken != null) {
                try {
                    // Use runBlocking to call the suspend function from a synchronous context
                    val response = kotlinx.coroutines.runBlocking {
                        refreshAccessToken(refreshToken)
                    }
                    if (response.first != null) {
                        Log.d(TAG, "Token refreshed successfully")
                        // Return the new token
                        return response.first
                    } else {
                        Log.e(TAG, "Token refresh failed")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error refreshing token: ${e.message}")
                }
            }
        }

        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        Log.d(TAG, "getAccessToken: token exists = ${token != null}")
        return token
    }

    // Get the current user ID (if any)
    fun getUserId(): Int {
        val id = prefs.getInt(KEY_USER_ID, 0)
        Log.d(TAG, "getUserId: id = $id")
        return id
    }

    // Get the current username (if any)
    fun getUsername(): String? {
        val username = prefs.getString(KEY_USERNAME, null)
        Log.d(TAG, "getUsername: username = $username")
        return username
    }

    // Create the auth request
    fun createAuthRequest(): AuthorizationRequest {
        Log.d(TAG, "Creating auth request with clientId = $clientId, redirectUri = $redirectUri")

        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(ANILIST_AUTH_ENDPOINT),
            Uri.parse(ANILIST_TOKEN_ENDPOINT)
        )

        return AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(redirectUri)
        ).build()
    }

    // Extract the authorization code directly from the intent data
    fun extractAuthCode(intent: Intent): String? {
        val uri = intent.data ?: return null
        return uri.getQueryParameter("code")
    }

    // Launch the login flow using Chrome Custom Tabs
    fun launchAuthFlow(context: Context) {
        val authRequest = createAuthRequest()
        val authService = AuthorizationService(context)
        val customTabsIntent = CustomTabsIntent.Builder().build()

        Log.d(TAG, "Launching auth flow with redirect URI: $redirectUri")
        Log.d(TAG, "Full auth request: $authRequest")
        Log.d(TAG, "Auth endpoint: ${authRequest.configuration.authorizationEndpoint}")
        Log.d(TAG, "Client ID: ${authRequest.clientId}")

        val authIntent = authService.getAuthorizationRequestIntent(authRequest, customTabsIntent)

        // Add FLAG_ACTIVITY_NEW_TASK if we're not being called from an Activity
        if (context !is android.app.Activity) {
            authIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(authIntent)
    }

    // Handle the OAuth redirect and exchange the code for tokens
    suspend fun handleAuthResponse(intent: Intent): Boolean {
        Log.d(TAG, "Handling auth response: ${intent.data}")
        Log.d(TAG, "Intent data details - scheme: ${intent.data?.scheme}, host: ${intent.data?.host}, path: ${intent.data?.path}")
        Log.d(TAG, "Intent data query parameters: ${intent.data?.query}")

        val authResponse = AuthorizationResponse.fromIntent(intent)
        val authException = AuthorizationException.fromIntent(intent)

        if (authException != null) {
            Log.e(TAG, "Authorization failed with error type: ${authException.type}, code: ${authException.code}")
            Log.e(TAG, "Authorization error details: ${authException.message}", authException)
            return false
        }

        if (authResponse == null) {
            Log.e(TAG, "Authorization response is null. Intent data: ${intent.data}")

            // Try to manually extract the auth code if AppAuth couldn't parse it
            val authCode = extractAuthCode(intent)
            if (authCode != null) {
                Log.d(TAG, "Manually extracted auth code from URI")
                return exchangeAuthCodeManually(authCode)
            }

            if (intent.data == null) {
                Log.e(TAG, "Intent data is null - check the redirect URI and intent filter")
                Log.e(TAG, "Expected redirect URI: $redirectUri")
                Log.e(TAG, "Check AndroidManifest.xml intent-filter for MainActivity")
            } else {
                Log.e(TAG, "Intent data present but could not extract auth response. Scheme: ${intent.data?.scheme}, Host: ${intent.data?.host}")
                Log.e(TAG, "Full URI: ${intent.data}")
            }
            return false
        }

        Log.d(TAG, "Authorization successful, code received: ${authResponse.authorizationCode != null}")
        return exchangeAuthCodeForToken(authResponse)
    }

    // Manually exchange the authorization code for tokens when AppAuth can't parse the response
    private suspend fun exchangeAuthCodeManually(authCode: String): Boolean {
        Log.d(TAG, "Manually exchanging auth code for token")

        return withContext(Dispatchers.IO) {
            try {
                val url = ANILIST_TOKEN_ENDPOINT
                val client = okhttp3.OkHttpClient.Builder().build()

                // Create request body with required OAuth parameters
                val requestBody = okhttp3.FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("client_id", clientId)
                    .add("client_secret", CLIENT_SECRET) // Include client secret
                    .add("redirect_uri", redirectUri)
                    .add("code", authCode)
                    .build()

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                Log.d(TAG, "Sending manual token request to: $url")

                val response = client.newCall(request).execute()
                val responseCode = response.code
                val responseBody = response.body?.string()

                Log.d(TAG, "Token response code: $responseCode")

                if (!response.isSuccessful || responseBody == null) {
                    Log.e(TAG, "Failed to exchange auth code: code=$responseCode")
                    Log.e(TAG, "Response body: $responseBody")
                    return@withContext false
                }

                Log.d(TAG, "Token response received: $responseBody")

                // Parse the JSON response
                val jsonObject = JSONObject(responseBody)
                val accessToken = jsonObject.optString("access_token", null)
                val refreshToken = jsonObject.optString("refresh_token", null)
                val expiresIn = jsonObject.optLong("expires_in", 0)

                if (accessToken != null) {
                    // Save tokens to SharedPreferences
                    val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)

                    prefs.edit().apply {
                        putString(KEY_ACCESS_TOKEN, accessToken)
                        if (refreshToken != null) {
                            putString(KEY_REFRESH_TOKEN, refreshToken)
                        }
                        putLong(KEY_EXPIRES_AT, expiresAt)
                        apply()
                    }

                    // Fetch and save user info
                    val userInfoResult = fetchAndSaveUserInfo()
                    Log.d(TAG, "User info fetch result: $userInfoResult")

                    return@withContext userInfoResult
                } else {
                    Log.e(TAG, "Access token is null in response")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in manual token exchange", e)
                Log.e(TAG, "Exception details: ${e.javaClass.name}: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    // Exchange the authorization code for access and refresh tokens
    private suspend fun exchangeAuthCodeForToken(authResponse: AuthorizationResponse): Boolean {
        Log.d(TAG, "Exchanging auth code for token")

        return withContext(Dispatchers.IO) {
            try {
                val authService = AuthorizationService(context)
                val tokenRequest = authResponse.createTokenExchangeRequest()

                Log.d(TAG, "Token request details: endpoint=${tokenRequest.configuration.tokenEndpoint}, " +
                        "clientId=${tokenRequest.clientId}, " +
                        "redirectUri=${tokenRequest.redirectUri}")

                val tokenResponse = AtomicReference<TokenResponse>()
                val authException = AtomicReference<AuthorizationException>()
                val latch = java.util.concurrent.CountDownLatch(1)

                authService.performTokenRequest(tokenRequest) { response, exception ->
                    tokenResponse.set(response)
                    authException.set(exception)
                    latch.countDown()

                    if (exception != null) {
                        Log.e(TAG, "Token request failed: type=${exception.type}, code=${exception.code}, message=${exception.message}")
                    } else if (response != null) {
                        Log.d(TAG, "Token response received: access token exists=${response.accessToken != null}, " +
                                "refresh token exists=${response.refreshToken != null}, " +
                                "expires in=${response.accessTokenExpirationTime}")
                    }
                }

                latch.await()

                if (authException.get() != null) {
                    Log.e(TAG, "Token exchange failed: ${authException.get()?.message}")
                    Log.e(TAG, "Token exchange error details: type=${authException.get()?.type}, code=${authException.get()?.code}")
                    return@withContext false
                }

                if (tokenResponse.get() == null) {
                    Log.e(TAG, "Token response is null")
                    return@withContext false
                }

                // Save tokens to preferences
                saveTokens(tokenResponse.get())

                // Fetch and save user info
                val userInfoResult = fetchAndSaveUserInfo()
                Log.d(TAG, "User info fetch result: $userInfoResult")

                return@withContext userInfoResult
            } catch (e: Exception) {
                Log.e(TAG, "Error exchanging code for token", e)
                Log.e(TAG, "Exception details: ${e.javaClass.name}: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    // Refresh an access token using a refresh token
    suspend fun refreshAccessToken(refreshToken: String): Pair<String?, String?> {
        Log.d(TAG, "Refreshing access token with refresh token")

        return withContext(Dispatchers.IO) {
            try {
                val url = ANILIST_TOKEN_ENDPOINT
                val client = okhttp3.OkHttpClient.Builder().build()

                // Create request body with required OAuth parameters for refresh
                val requestBody = okhttp3.FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", clientId)
                    .add("client_secret", CLIENT_SECRET)
                    .add("refresh_token", refreshToken)
                    .build()

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                Log.d(TAG, "Sending refresh token request to: $url")

                val response = client.newCall(request).execute()
                val responseCode = response.code
                val responseBody = response.body?.string()

                Log.d(TAG, "Refresh token response code: $responseCode")

                if (!response.isSuccessful || responseBody == null) {
                    Log.e(TAG, "Failed to refresh token: code=$responseCode")
                    Log.e(TAG, "Response body: $responseBody")
                    return@withContext Pair(null, null)
                }

                Log.d(TAG, "Refresh token response received")

                // Parse the JSON response
                val jsonObject = JSONObject(responseBody)
                val accessToken = jsonObject.optString("access_token", null)
                val newRefreshToken = jsonObject.optString("refresh_token", null)
                val expiresIn = jsonObject.optLong("expires_in", 0)

                if (accessToken != null) {
                    // Save tokens to SharedPreferences
                    val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)

                    prefs.edit().apply {
                        putString(KEY_ACCESS_TOKEN, accessToken)
                        if (!newRefreshToken.isNullOrEmpty()) {
                            putString(KEY_REFRESH_TOKEN, newRefreshToken)
                        }
                        putLong(KEY_EXPIRES_AT, expiresAt)
                        apply()
                    }

                    Log.d(TAG, "Token refreshed successfully, expires at: ${java.util.Date(expiresAt)}")
                    return@withContext Pair(accessToken, newRefreshToken ?: refreshToken)
                } else {
                    Log.e(TAG, "Access token is null in refresh response")
                    return@withContext Pair(null, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing token", e)
                Log.e(TAG, "Exception details: ${e.javaClass.name}: ${e.message}")
                e.printStackTrace()
                return@withContext Pair(null, null)
            }
        }
    }

    // Save tokens to SharedPreferences
    private fun saveTokens(tokenResponse: TokenResponse) {
        val expiresAt = System.currentTimeMillis() + (tokenResponse.accessTokenExpirationTime ?: 0)
        Log.d(TAG, "Saving tokens - access token length: ${tokenResponse.accessToken?.length ?: 0}, " +
                "refresh token exists: ${tokenResponse.refreshToken != null}, " +
                "expires at: ${java.util.Date(expiresAt)}")

        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, tokenResponse.accessToken)
            putString(KEY_REFRESH_TOKEN, tokenResponse.refreshToken)
            putLong(KEY_EXPIRES_AT, expiresAt)
            apply()
        }

        // Verify tokens were saved correctly
        val savedToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        Log.d(TAG, "Token saved successfully: ${savedToken != null} (length: ${savedToken?.length ?: 0})")
    }

    // Fetch user info after authentication and save it
    private suspend fun fetchAndSaveUserInfo(): Boolean {
        Log.d(TAG, "Fetching user info from AniList API")

        return withContext(Dispatchers.IO) {
            try {
                val accessToken = getAccessToken()
                if (accessToken == null) {
                    Log.e(TAG, "Failed to get access token for user info request")
                    return@withContext false
                }

                val url = "https://graphql.anilist.co"
                val body = """
                    {
                        "query": "query { Viewer { id name } }"
                    }
                """.trimIndent()

                Log.d(TAG, "Making GraphQL request to AniList API")

                val client = okhttp3.OkHttpClient.Builder()
                    .build()
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = body.toRequestBody(mediaType)

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .build()

                Log.d(TAG, "Sending request to: $url with Authorization header")

                val response = client.newCall(request).execute()
                val responseCode = response.code
                val responseBody = response.body?.string()

                Log.d(TAG, "User info response code: $responseCode")

                if (!response.isSuccessful || responseBody == null) {
                    Log.e(TAG, "Failed to fetch user info: code=$responseCode")
                    Log.e(TAG, "Response body: $responseBody")
                    return@withContext false
                }

                Log.d(TAG, "User info response received: $responseBody")

                val jsonObject = JSONObject(responseBody)
                val dataObject = jsonObject.optJSONObject("data")
                val viewerObject = dataObject?.optJSONObject("Viewer")

                if (viewerObject != null) {
                    val userId = viewerObject.getInt("id")
                    val username = viewerObject.getString("name")

                    Log.d(TAG, "User info parsed successfully: id=$userId, name=$username")

                    prefs.edit().apply {
                        putInt(KEY_USER_ID, userId)
                        putString(KEY_USERNAME, username)
                        apply()
                    }

                    // Verify the user data was saved
                    val savedUsername = prefs.getString(KEY_USERNAME, null)
                    Log.d(TAG, "User data saved: username=$savedUsername, userId=${prefs.getInt(KEY_USER_ID, 0)}")

                    return@withContext true
                } else {
                    Log.e(TAG, "Viewer object missing in API response")
                    if (dataObject != null) {
                        Log.e(TAG, "Data object exists but Viewer is null")
                    } else {
                        Log.e(TAG, "Data object is null")
                    }
                    if (jsonObject.has("errors")) {
                        Log.e(TAG, "API returned errors: ${jsonObject.optJSONArray("errors")}")
                    }
                }

                return@withContext false
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching user info", e)
                Log.e(TAG, "Exception details: ${e.javaClass.name}: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    // Log out the current user
    fun logout() {
        Log.d(TAG, "Logging out user: ${getUsername()}")
        prefs.edit().clear().apply()
        Log.d(TAG, "Logout complete, is logged in: ${isLoggedIn()}")
    }
}
