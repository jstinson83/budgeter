package com.budgeter

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class AuthTest {
    @Test
    fun testWelcomePageShowsSignInWhenSignedOut() = testApplication {
        testModule()

        // Deliberately not signed in - /welcome is the one page that must stay
        // reachable without a session.
        val response = client.get("/welcome")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("/auth/google"), "Signed-out splash page should link to the Google sign-in route")
        assertFalse(body.contains("Signed in as"), "Signed-out splash page shouldn't show the signed-in state")
    }

    @Test
    fun testUnauthenticatedPageRequestRedirectsToWelcome() = testApplication {
        testModule()
        val client = createClient { followRedirects = false }

        // Accept: text/html is what a real browser sends for page navigation -
        // this is what the challenge uses to tell a page request apart from a
        // fetch() call (see the challenge block in Auth.kt).
        val response = client.get("/") { header(HttpHeaders.Accept, "text/html") }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/welcome", response.headers[HttpHeaders.Location])
    }

    @Test
    fun testUnauthenticatedNonHtmlRequestReturns401() = testApplication {
        testModule()

        // No Accept header, same as a fetch()-driven endpoint - should get a
        // plain 401 rather than an HTML redirect body it can't parse.
        val response = client.get("/")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testGoogleSignInUnlocksHomePage() = testApplication {
        testModule(oauthClient = fakeGoogleOAuthClient("sub-123", "person@example.com", "Person"))

        val client = signInFakeUser()

        val homeResponse = client.get("/") { header(HttpHeaders.Accept, "text/html") }
        assertEquals(HttpStatusCode.OK, homeResponse.status)
        assertTrue(homeResponse.bodyAsText().contains("Sign out"), "Nav should show sign-out once authenticated")
        assertTrue(homeResponse.bodyAsText().contains("Person"), "Nav should show the signed-in user's name")
    }

    @Test
    fun testLogoutClearsSession() = testApplication {
        testModule()
        val client = signInFakeUser()

        val logoutResponse = client.post("/logout")
        assertEquals(HttpStatusCode.Found, logoutResponse.status)
        assertEquals("/welcome", logoutResponse.headers[HttpHeaders.Location])

        val homeResponse = client.get("/")
        assertEquals(HttpStatusCode.Unauthorized, homeResponse.status)
    }

    @Test
    fun testStaticStylesheetIsServed() = testApplication {
        testModule()

        val response = client.get("/styles.css")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(".container"), "Static stylesheet should be served at /styles.css")
    }
}
