package com.budgeter

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

private val firestoreClient: Firestore by lazy {
    val databaseId = System.getenv("FIRESTORE_DATABASE_ID") ?: "home-os"
    FirestoreOptions.newBuilder().setDatabaseId(databaseId).build().service
}

// Separate client from oauthHttpClient - different upstream, no reason to
// couple their lifecycles even though the setup (CIO + JSON content
// negotiation) looks the same. encodeDefaults = true matters here in a way
// it doesn't for oauthHttpClient: GeminiCategorizer.kt's request DTOs (the
// response schema's "type" fields, "responseMimeType") rely on Kotlin
// default values, and kotlinx.serialization omits any field left at its
// default unless told otherwise - Gemini's schema validator requires those
// "type" fields to be present, so without this the request silently goes
// out missing them and gets rejected with a 400 (see CLAUDE.md's Gemini
// categorization gotchas).
private val geminiHttpClient: HttpClient by lazy {
    HttpClient(CIO) {
        engine {
            // CIO's engine has its own built-in request timeout (default
            // 15s) that applies independently of the HttpTimeout plugin
            // (which isn't installed here) - a well-known Ktor gotcha. Hit
            // in production on a real house-document upload: Gemini's
            // response for a large/complex PDF took longer than 15s and the
            // call was aborted with "Request timeout has expired
            // [...,request_timeout=unknown ms]" - the "unknown" is the
            // giveaway this is CIO's internal timeout firing rather than a
            // HttpTimeout-plugin-configured one (which would show the real
            // configured value in the message). Both Gemini calls this
            // client makes (categorization, house fact extraction) can
            // legitimately run past 15s, so this raises it well above what
            // either should ever need.
            requestTimeout = 300_000
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
    }
}

private val geminiCategorizer: TransactionCategorizer by lazy {
    GeminiTransactionCategorizer(geminiHttpClient, System.getenv("GEMINI_API_KEY") ?: "")
}

private val geminiHouseFactCandidateExtractor: HouseFactCandidateExtractor by lazy {
    GeminiHouseFactCandidateExtractor(geminiHttpClient, System.getenv("GEMINI_API_KEY") ?: "")
}

private val geminiHouseFactNormalizer: HouseFactNormalizer by lazy {
    GeminiHouseFactNormalizer(geminiHttpClient, System.getenv("GEMINI_API_KEY") ?: "")
}

// Two-pass pipeline (see HouseFactExtractor.kt): pass 1 reads the PDF and
// extracts recall-favoring candidates, pass 2 reconciles/dedupes them into
// the final fact list. Both stages share the API key/HTTP client but are
// separate Gemini calls.
private val geminiHouseFactExtractor: HouseFactExtractor by lazy {
    TwoPassHouseFactExtractor(geminiHouseFactCandidateExtractor, geminiHouseFactNormalizer)
}

private val geminiComponentSummarizer: ComponentSummarizer by lazy {
    GeminiComponentSummarizer(geminiHttpClient, System.getenv("GEMINI_API_KEY") ?: "")
}

// The bucket is provisioned manually in the GCP console, same as the
// Firestore database and Cloud Run env vars - see CLAUDE.md's deploy
// pipeline section. Falls back to "" like GEMINI_API_KEY does above; the
// missing-config error only surfaces when a document is actually uploaded,
// not at startup, so local dev without house-document work configured
// still boots fine.
private val documentStorageClient: Storage by lazy { StorageOptions.getDefaultInstance().service }

fun Application.module(
    oauthClient: HttpClient = oauthHttpClient,
    oauthRedirectBaseUrl: String = System.getenv("OAUTH_REDIRECT_BASE_URL") ?: "http://localhost:8080",
    sessionSecret: String = System.getenv("SESSION_SECRET") ?: "dev-only-insecure-session-secret-change-me",
    transactionStore: TransactionRepository = FirestoreTransactionStore(firestoreClient),
    transactionCategorizer: TransactionCategorizer = geminiCategorizer,
    categorizationRuleStore: CategorizationRuleRepository = FirestoreCategorizationRuleStore(firestoreClient),
    categoryStore: CategoryRepository = FirestoreCategoryStore(firestoreClient),
    categorizationJobManager: CategorizationJobManager = CategorizationJobManager(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        transactionStore,
        categorizationRuleStore,
        categoryStore,
        transactionCategorizer
    ),
    houseDocumentStore: HouseDocumentRepository = FirestoreHouseDocumentStore(firestoreClient),
    houseFactStore: HouseFactRepository = FirestoreHouseFactStore(firestoreClient),
    documentBlobStore: DocumentBlobStore = GcsDocumentBlobStore(documentStorageClient, System.getenv("HOUSE_DOCUMENTS_BUCKET") ?: ""),
    houseFactExtractor: HouseFactExtractor = geminiHouseFactExtractor,
    houseFactExtractionJobManager: HouseFactExtractionJobManager = HouseFactExtractionJobManager(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        houseDocumentStore,
        houseFactStore,
        houseFactExtractor
    ),
    houseComponentSummaryStore: HouseComponentSummaryRepository = FirestoreHouseComponentSummaryStore(firestoreClient),
    componentSummarizer: ComponentSummarizer = geminiComponentSummarizer
) {
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
        setOutputFormat(HTMLOutputFormat.INSTANCE)
        autoEscapingPolicy = Configuration.ENABLE_IF_DEFAULT_AUTO_ESCAPING_POLICY
    }

    installGoogleAuth(oauthClient, oauthRedirectBaseUrl, sessionSecret)

    // Background categorization jobs are tied to this scope, not to any one
    // request's - see CategorizationJob.kt. Cancelled on shutdown so a
    // stopped test/dev instance doesn't leave orphaned coroutines running.
    monitor.subscribe(ApplicationStopping) {
        categorizationJobManager.cancel()
        houseFactExtractionJobManager.cancel()
    }

    routing {
        staticResources("/", "static")

        authRoutes(oauthClient)

        authenticate(USER_SESSION_PROVIDER_NAME) {
            dashboardRoutes(transactionStore, categoryStore)

            transactionRoutes(transactionStore)
            analysisRoutes(transactionStore, categorizationRuleStore, categoryStore, categorizationJobManager)
            categoryRoutes(categoryStore, categorizationRuleStore, transactionStore)
            houseRoutes(
                houseDocumentStore,
                houseFactStore,
                documentBlobStore,
                houseFactExtractionJobManager,
                houseComponentSummaryStore,
                componentSummarizer
            )
        }
    }
}
