package com.budgeter

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.freemarker.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable

// What we keep in the session cookie once someone signs in. googleSub is
// Google's stable per-account id (the OAuth "sub" claim), not email, in case
// this ever needs to key stored data - email can change, sub doesn't.
data class UserSession(val googleSub: String, val email: String, val name: String)

// Ktor 3's default session serializer is kotlinx.serialization-based (unlike
// Ktor 2's reflection-based one), so this needs @Serializable; kept as a flat,
// nullable shape rather than a nested UserSession to keep that serialization
// straightforward.
@Serializable
data class SessionData(
    val googleSub: String? = null,
    val email: String? = null,
    val name: String? = null
) {
    fun toUserSession(): UserSession? =
        if (googleSub != null && email != null && name != null) UserSession(googleSub, email, name) else null
}

// Shape of https://www.googleapis.com/oauth2/v3/userinfo, trimmed to the
// fields we actually use.
@Serializable
data class GoogleUserInfo(val sub: String, val email: String, val name: String)

const val GOOGLE_OAUTH_PROVIDER_NAME = "auth-google"
const val USER_SESSION_PROVIDER_NAME = "user-session"

fun Application.installGoogleAuth(oauthHttpClient: HttpClient, redirectBaseUrl: String, sessionSecret: String) {
    install(Sessions) {
        cookie<SessionData>("budgeter_session") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.extensions["SameSite"] = "Lax"
            cookie.maxAgeInSeconds = 60 * 60 * 24 * 30
            // Without this the cookie is just URL-encoded plaintext - anyone
            // could hand-edit their browser cookie to claim any identity.
            transform(SessionTransportTransformerMessageAuthentication(sessionSecret.toByteArray()))
        }
    }

    install(Authentication) {
        oauth(GOOGLE_OAUTH_PROVIDER_NAME) {
            urlProvider = { "$redirectBaseUrl/auth/google/callback" }
            providerLookup = {
                OAuthServerSettings.OAuth2ServerSettings(
                    name = "google",
                    authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
                    accessTokenUrl = "https://oauth2.googleapis.com/token",
                    requestMethod = HttpMethod.Post,
                    clientId = System.getenv("GOOGLE_CLIENT_ID") ?: "",
                    clientSecret = System.getenv("GOOGLE_CLIENT_SECRET") ?: "",
                    defaultScopes = listOf("openid", "email", "profile")
                )
            }
            client = oauthHttpClient
        }

        // Gates every route wrapped in authenticate(USER_SESSION_PROVIDER_NAME).
        // A present session cookie is trusted as-is (no re-checking against a
        // user store) since it's signed - forging one isn't possible without
        // sessionSecret.
        session<SessionData>(USER_SESSION_PROVIDER_NAME) {
            validate { data -> data.toUserSession() }
            challenge {
                // Browser navigation (a plain GET for a page) sends an Accept
                // header that prefers text/html; this app's own fetch() calls
                // (if any are ever added) never ask for text/html, so this
                // tells the two apart without enumerating "page" vs "API" routes.
                val wantsHtml = call.request.headers[HttpHeaders.Accept]?.contains("text/html") == true
                if (wantsHtml) {
                    call.respondRedirect("/welcome")
                } else {
                    call.respond(HttpStatusCode.Unauthorized)
                }
            }
        }
    }
}

// Merged into every gated page's model at the call site so _nav.ftl can
// render a sign-out link - page model builders themselves stay pure
// functions with no knowledge of the call or session.
fun ApplicationCall.currentUserModel(): Map<String, Any?> = mapOf("currentUser" to principal<UserSession>())

fun Route.authRoutes(oauthHttpClient: HttpClient) {
    get("/welcome") {
        val session = call.sessions.get<SessionData>()?.toUserSession()
        call.respond(FreeMarkerContent("welcome.ftl", mapOf("session" to session)))
    }

    // Both routes sit under the same provider: hitting /auth/google with no
    // principal yet is what makes Ktor's OAuth provider redirect to Google;
    // Google then redirects back to /auth/google/callback (must match
    // urlProvider above) with a code, which the same provider exchanges for
    // a token before this block's handler runs.
    authenticate(GOOGLE_OAUTH_PROVIDER_NAME) {
        get("/auth/google") {
            // Unreached when unauthenticated - the provider redirects to
            // Google before this body runs.
        }

        get("/auth/google/callback") {
            val principal = call.principal<OAuthAccessTokenResponse.OAuth2>()
            if (principal == null) {
                call.respondRedirect("/welcome")
                return@get
            }
            val userInfo: GoogleUserInfo = oauthHttpClient
                .get("https://www.googleapis.com/oauth2/v3/userinfo") {
                    header(HttpHeaders.Authorization, "Bearer ${principal.accessToken}")
                }
                .body()

            call.sessions.set(SessionData(userInfo.sub, userInfo.email, userInfo.name))
            call.respondRedirect("/")
        }
    }

    post("/logout") {
        call.sessions.clear<SessionData>()
        call.respondRedirect("/welcome")
    }
}
