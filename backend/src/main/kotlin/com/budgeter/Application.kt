package com.budgeter

import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

@Serializable
data class UserSession(val username: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class MeResponse(val username: String)

@Serializable
data class ErrorResponse(val error: String)

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "hash-password") {
        val password = args.getOrNull(1)
            ?: error("Usage: hash-password <password>")
        println(BCrypt.withDefaults().hashToString(12, password.toCharArray()))
        return
    }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    val authUsername = requireEnv("BUDGETER_USERNAME")
    val authPasswordHash = requireEnv(
        "BUDGETER_PASSWORD_HASH",
        hint = "generate one with: ./gradlew run --args=\"hash-password <password>\""
    )
    val sessionSecret = requireEnv(
        "BUDGETER_SESSION_SECRET",
        hint = "any random string, e.g. output of: openssl rand -hex 32"
    )
    val frontendDist = System.getenv("BUDGETER_FRONTEND_DIST") ?: "../frontend/dist"

    install(ContentNegotiation) {
        json()
    }

    install(CallLogging)

    install(Sessions) {
        val secretDigest = MessageDigest.getInstance("SHA-256").digest(sessionSecret.toByteArray())
        val encryptKey = secretDigest.copyOfRange(0, 16)
        val signKey = secretDigest

        cookie<UserSession>("budgeter_session") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = 60 * 60 * 24 * 7
            cookie.extensions["SameSite"] = "Strict"
            transform(SessionTransportTransformerEncrypt(encryptKey, signKey))
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
        }
    }

    routing {
        route("/api") {
            post("/login") {
                val request = call.receive<LoginRequest>()
                val verified = request.username == authUsername &&
                    BCrypt.verifyer().verify(request.password.toCharArray(), authPasswordHash).verified

                if (!verified) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_credentials"))
                    return@post
                }

                call.sessions.set(UserSession(username = request.username))
                call.respond(MeResponse(username = request.username))
            }

            post("/logout") {
                call.sessions.clear<UserSession>()
                call.respond(HttpStatusCode.NoContent)
            }

            get("/me") {
                val session = call.sessions.get<UserSession>()
                if (session == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("not_authenticated"))
                } else {
                    call.respond(MeResponse(username = session.username))
                }
            }
        }

        staticFiles("/", File(frontendDist))
    }
}

private fun requireEnv(name: String, hint: String? = null): String {
    return System.getenv(name) ?: error(
        "Missing required environment variable $name" + (hint?.let { " ($it)" } ?: "")
    )
}
