package com.budgeter

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

/**
 * Shared fakes and helpers for the test suite - grouped here so each test
 * file only needs to import this one.
 */

// Stands in for Google's token + userinfo endpoints (the two network calls
// the real OAuth flow makes) so tests can drive /auth/google/callback
// without ever leaving the process. Bound as `oauthClient` in module(), same
// seam production code uses to talk to Google for real.
fun fakeGoogleOAuthClient(sub: String = "test-sub", email: String = "test@example.com", name: String = "Test User"): HttpClient =
    HttpClient(MockEngine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        engine {
            addHandler { request ->
                val url = request.url.toString()
                when {
                    url.startsWith("https://oauth2.googleapis.com/token") -> respond(
                        """{"access_token":"fake-access-token","token_type":"Bearer"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                    url.startsWith("https://www.googleapis.com/oauth2/v3/userinfo") -> respond(
                        """{"sub":"$sub","email":"$email","name":"$name"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                    else -> error("Unexpected OAuth request to $url")
                }
            }
        }
    }

// Wires module() with an all-fakes default, mirroring module()'s own
// defaulting but pointed at fakes instead of the real OAuth client.
fun ApplicationTestBuilder.testModule(
    oauthClient: HttpClient = fakeGoogleOAuthClient(),
    oauthRedirectBaseUrl: String = "http://localhost:8080",
    sessionSecret: String = "test-session-secret"
) {
    application {
        module(
            oauthClient = oauthClient,
            oauthRedirectBaseUrl = oauthRedirectBaseUrl,
            sessionSecret = sessionSecret
        )
    }
}

// Drives the real /auth/google -> /auth/google/callback round trip (against
// whatever fakeGoogleOAuthClient(...) was wired into this test's module()
// call as oauthClient) so gated-route tests exercise the exact production
// code path that sets the session cookie, rather than hand-constructing one.
suspend fun ApplicationTestBuilder.signInFakeUser(): HttpClient {
    val client = createClient {
        install(HttpCookies)
        followRedirects = false
    }
    val loginResponse = client.get("/auth/google")
    val location = loginResponse.headers[HttpHeaders.Location]
        ?: error("Expected a redirect from /auth/google, got ${loginResponse.status}")
    val state = Url(location).parameters["state"]
        ?: error("Expected a state param in the Google authorize URL: $location")
    client.get("/auth/google/callback?code=fake-code&state=$state")
    return client
}
