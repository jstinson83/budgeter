package com.budgeter

import freemarker.cache.ClassTemplateLoader
import freemarker.core.HTMLOutputFormat
import freemarker.template.Configuration
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.freemarker.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

private val oauthHttpClient: HttpClient by lazy {
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}

fun Application.module(
    oauthClient: HttpClient = oauthHttpClient,
    oauthRedirectBaseUrl: String = System.getenv("OAUTH_REDIRECT_BASE_URL") ?: "http://localhost:8080",
    sessionSecret: String = System.getenv("SESSION_SECRET") ?: "dev-only-insecure-session-secret-change-me"
) {
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
        setOutputFormat(HTMLOutputFormat.INSTANCE)
        autoEscapingPolicy = Configuration.ENABLE_IF_DEFAULT_AUTO_ESCAPING_POLICY
    }

    installGoogleAuth(oauthClient, oauthRedirectBaseUrl, sessionSecret)

    routing {
        staticResources("/", "static")

        authRoutes(oauthClient)

        authenticate(USER_SESSION_PROVIDER_NAME) {
            get("/") {
                call.respond(FreeMarkerContent("home.ftl", call.currentUserModel()))
            }
        }
    }
}
