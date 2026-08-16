package com.budgeter

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
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
    sessionSecret: String = "test-session-secret",
    transactionStore: TransactionRepository = FakeTransactionRepository(),
    transactionCategorizer: TransactionCategorizer = FakeTransactionCategorizer(),
    categorizationRuleStore: CategorizationRuleRepository = FakeCategorizationRuleRepository(),
    categoryStore: CategoryRepository = FakeCategoryRepository(),
    houseDocumentStore: HouseDocumentRepository = FakeHouseDocumentRepository(),
    houseFactStore: HouseFactRepository = FakeHouseFactRepository(),
    documentBlobStore: DocumentBlobStore = FakeDocumentBlobStore(),
    houseFactExtractor: HouseFactExtractor = FakeHouseFactExtractor(),
    houseComponentSummaryStore: HouseComponentSummaryRepository = FakeHouseComponentSummaryRepository(),
    componentSummarizer: ComponentSummarizer = FakeComponentSummarizer(),
    projectStore: ProjectRepository = FakeProjectRepository()
) {
    application {
        module(
            oauthClient = oauthClient,
            oauthRedirectBaseUrl = oauthRedirectBaseUrl,
            sessionSecret = sessionSecret,
            transactionStore = transactionStore,
            transactionCategorizer = transactionCategorizer,
            categorizationRuleStore = categorizationRuleStore,
            categoryStore = categoryStore,
            houseDocumentStore = houseDocumentStore,
            houseFactStore = houseFactStore,
            documentBlobStore = documentBlobStore,
            houseFactExtractor = houseFactExtractor,
            houseComponentSummaryStore = houseComponentSummaryStore,
            componentSummarizer = componentSummarizer,
            projectStore = projectStore
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

// In-memory stand-in for FirestoreTransactionStore, same shape as
// FakeRecipeRepository in foodie - keeps the test suite from ever touching
// real Firestore. Mirrors the real store's fingerprint-as-ID dedup so
// duplicate-import behavior is exercised the same way in tests.
class FakeTransactionRepository : TransactionRepository {
    private val transactions = mutableListOf<Transaction>()

    override suspend fun addAll(ownerId: String, fileHash: String, transactions: List<ParsedTransaction>): TransactionImportResult {
        val existingIds = this.transactions.filter { it.ownerId == ownerId }.map { it.id }.toMutableSet()
        val stored = mutableListOf<Transaction>()
        var duplicateCount = 0
        for (parsed in transactions) {
            val fingerprint = transactionFingerprint(ownerId, fileHash, parsed)
            if (!existingIds.add(fingerprint)) {
                duplicateCount++
                continue
            }
            val transaction = Transaction(fingerprint, ownerId, parsed.accountType, parsed.date, parsed.description, parsed.amount)
            stored += transaction
            this.transactions += transaction
        }
        return TransactionImportResult(stored, duplicateCount)
    }

    override suspend fun all(ownerId: String): List<Transaction> =
        transactions.filter { it.ownerId == ownerId }.sortedByDescending { it.date }

    override suspend fun updateCategories(ownerId: String, categorized: Map<String, String>) {
        for (i in transactions.indices) {
            val transaction = transactions[i]
            val category = categorized[transaction.id] ?: continue
            if (transaction.ownerId == ownerId) transactions[i] = transaction.copy(category = category)
        }
    }

    override suspend fun deleteAll(ownerId: String) {
        transactions.removeAll { it.ownerId == ownerId }
    }
}

// In-memory stand-in for FirestoreCategorizationRuleStore.
class FakeCategorizationRuleRepository : CategorizationRuleRepository {
    private val rules = mutableListOf<CategorizationRule>()
    private var nextId = 0

    override suspend fun all(ownerId: String): List<CategorizationRule> = rules.filter { it.ownerId == ownerId }

    override suspend fun add(ownerId: String, pattern: String, matchType: MatchType, category: String): CategorizationRule {
        val rule = CategorizationRule("rule-${nextId++}", ownerId, pattern, matchType, category)
        rules += rule
        return rule
    }

    override suspend fun update(ownerId: String, id: String, pattern: String, matchType: MatchType, category: String): CategorizationRule? {
        val index = rules.indexOfFirst { it.id == id && it.ownerId == ownerId }
        if (index == -1) return null
        val updated = CategorizationRule(id, ownerId, pattern, matchType, category)
        rules[index] = updated
        return updated
    }

    override suspend fun delete(ownerId: String, id: String) {
        rules.removeAll { it.id == id && it.ownerId == ownerId }
    }
}

// In-memory stand-in for FirestoreCategoryStore - mirrors its lazy
// per-owner seeding (BUILT_IN_CATEGORIES) and slug generation so tests
// exercise the same ids/behavior the real store would.
class FakeCategoryRepository : CategoryRepository {
    private val categories = mutableListOf<Category>()

    override suspend fun all(ownerId: String): List<Category> {
        val existing = categories.filter { it.ownerId == ownerId }
        if (existing.isNotEmpty()) return existing
        val seeded = BUILT_IN_CATEGORIES.map { (id, label) -> Category(id, ownerId, label, active = true) }
        categories += seeded
        return seeded
    }

    override suspend fun add(ownerId: String, label: String): Category {
        val existingIds = all(ownerId).map { it.id }.toSet()
        val category = Category(uniqueSlug(label, existingIds), ownerId, label, active = true)
        categories += category
        return category
    }

    override suspend fun setActive(ownerId: String, id: String, active: Boolean) {
        all(ownerId) // ensures seeding has happened before the lookup below
        val index = categories.indexOfFirst { it.ownerId == ownerId && it.id == id }
        if (index != -1) categories[index] = categories[index].copy(active = active)
    }
}

// In-memory stand-in for FirestoreHouseDocumentStore.
class FakeHouseDocumentRepository : HouseDocumentRepository {
    private val documents = mutableListOf<HouseDocument>()
    private var nextId = 0

    override suspend fun add(ownerId: String, filename: String, storagePath: String, context: String?): HouseDocument {
        val document = HouseDocument(
            id = "house-doc-${nextId++}",
            ownerId = ownerId,
            filename = filename,
            storagePath = storagePath,
            status = HouseDocumentStatus.UPLOADED,
            context = context,
            uploadedAt = java.time.Instant.now()
        )
        documents += document
        return document
    }

    override suspend fun all(ownerId: String): List<HouseDocument> =
        documents.filter { it.ownerId == ownerId }.sortedByDescending { it.uploadedAt }

    override suspend fun get(ownerId: String, id: String): HouseDocument? =
        documents.find { it.ownerId == ownerId && it.id == id }

    override suspend fun updateStatus(ownerId: String, id: String, status: HouseDocumentStatus, error: String?) {
        val index = documents.indexOfFirst { it.ownerId == ownerId && it.id == id }
        if (index != -1) documents[index] = documents[index].copy(status = status, error = error)
    }

    override suspend fun updateDebugNotes(ownerId: String, id: String, debugNotes: String?) {
        val index = documents.indexOfFirst { it.ownerId == ownerId && it.id == id }
        if (index != -1) documents[index] = documents[index].copy(debugNotes = debugNotes)
    }

    override suspend fun delete(ownerId: String, id: String) {
        documents.removeAll { it.ownerId == ownerId && it.id == id }
    }
}

// In-memory stand-in for FirestoreHouseFactStore.
class FakeHouseFactRepository : HouseFactRepository {
    private val facts = mutableListOf<HouseFact>()
    private var nextId = 0

    override suspend fun addAll(ownerId: String, documentId: String, extracted: List<ExtractedFact>): List<HouseFact> {
        val stored = extracted.map {
            HouseFact(
                id = "house-fact-${nextId++}",
                ownerId = ownerId,
                documentId = documentId,
                what = it.what,
                type = it.type,
                component = it.component,
                status = it.status,
                importance = it.importance,
                sourceQuote = it.sourceQuote,
                sourceLocation = it.sourceLocation,
                evidenceType = it.evidenceType,
                needsReview = it.needsReview,
                reviewQuestion = it.reviewQuestion,
                createdAt = java.time.Instant.now()
            )
        }
        facts += stored
        return stored
    }

    override suspend fun all(ownerId: String): List<HouseFact> =
        facts.filter { it.ownerId == ownerId }.sortedByDescending { it.createdAt }

    override suspend fun resolve(ownerId: String, id: String, homeownerContext: String): HouseFact? {
        val index = facts.indexOfFirst { it.ownerId == ownerId && it.id == id }
        if (index == -1) return null
        val updated = facts[index].copy(homeownerContext = homeownerContext, needsReview = false)
        facts[index] = updated
        return updated
    }

    override suspend fun deleteForDocument(ownerId: String, documentId: String) {
        facts.removeAll { it.ownerId == ownerId && it.documentId == documentId }
    }
}

// In-memory stand-in for GcsDocumentBlobStore - keeps the test suite from
// ever touching real GCS.
class FakeDocumentBlobStore : DocumentBlobStore {
    private val blobs = mutableMapOf<String, ByteArray>()

    override suspend fun upload(ownerId: String, documentId: String, filename: String, bytes: ByteArray): String {
        val path = "$ownerId/$documentId/$filename"
        blobs[path] = bytes
        return path
    }

    override suspend fun download(storagePath: String): ByteArray =
        blobs[storagePath] ?: error("House document blob not found: $storagePath")

    override suspend fun delete(storagePath: String) {
        blobs.remove(storagePath)
    }
}

// Stands in for TwoPassHouseFactExtractor - returns a fixed, small set of
// facts (one needing review) so route tests can exercise the extraction ->
// review flow without ever calling Gemini for real.
class FakeHouseFactExtractor(
    private val facts: List<ExtractedFact> = listOf(
        ExtractedFact(
            what = "The house contains steel structural columns",
            type = FactType.SPECIFICATION,
            component = Component.STRUCTURE,
            status = FactStatus.EXISTING,
            importance = Importance.MEDIUM,
            sourceQuote = "steel columns observed",
            sourceLocation = null,
            evidenceType = EvidenceType.OBSERVED,
            needsReview = false,
            reviewQuestion = null
        ),
        ExtractedFact(
            what = "Central floor bulge cause not determined",
            type = FactType.CONDITION,
            component = Component.STRUCTURE,
            status = FactStatus.EXISTING,
            importance = Importance.HIGH,
            sourceQuote = "cause not determined",
            sourceLocation = null,
            evidenceType = EvidenceType.OBSERVED,
            needsReview = true,
            reviewQuestion = "What's your understanding of this?"
        )
    ),
    private val debugNotes: String? = "fake pass 1/pass 2 debug notes"
) : HouseFactExtractor {
    var callCount: Int = 0
        private set
    var lastDocumentContext: String? = null
        private set

    override suspend fun extract(filename: String, pdfBytes: ByteArray, documentContext: String?): ExtractionResult {
        callCount++
        lastDocumentContext = documentContext
        return ExtractionResult(facts = facts, debugNotes = debugNotes)
    }
}

// In-memory stand-in for FirestoreHouseComponentSummaryStore.
class FakeHouseComponentSummaryRepository : HouseComponentSummaryRepository {
    private val summaries = mutableMapOf<Pair<String, Component>, ComponentSummary>()

    override suspend fun get(ownerId: String, component: Component): ComponentSummary? = summaries[ownerId to component]

    override suspend fun save(ownerId: String, component: Component, summary: String, factCount: Int): ComponentSummary {
        val saved = ComponentSummary(ownerId, component, summary, factCount, java.time.Instant.now())
        summaries[ownerId to component] = saved
        return saved
    }
}

// In-memory stand-in for FirestoreProjectStore.
class FakeProjectRepository : ProjectRepository {
    private val projects = mutableListOf<Project>()
    private var nextId = 0

    override suspend fun all(ownerId: String): List<Project> =
        projects.filter { it.ownerId == ownerId }.sortedByDescending { it.createdAt }

    override suspend fun get(ownerId: String, id: String): Project? =
        projects.find { it.ownerId == ownerId && it.id == id }

    override suspend fun add(ownerId: String, name: String, status: ProjectStatus, component: Component, priority: Priority): Project {
        val project = Project("project-${nextId++}", ownerId, name, status, component, priority, java.time.Instant.now())
        projects += project
        return project
    }

    override suspend fun update(ownerId: String, id: String, name: String, status: ProjectStatus, component: Component, priority: Priority): Project? {
        val index = projects.indexOfFirst { it.ownerId == ownerId && it.id == id }
        if (index == -1) return null
        val updated = projects[index].copy(name = name, status = status, component = component, priority = priority)
        projects[index] = updated
        return updated
    }
}

// Stands in for GeminiComponentSummarizer - returns a fixed summary string
// derived from the component/fact count so route tests can assert on it
// without ever calling Gemini for real.
class FakeComponentSummarizer : ComponentSummarizer {
    var callCount: Int = 0
        private set

    override suspend fun summarize(component: Component, facts: List<HouseFact>): String {
        callCount++
        return "Summary of ${facts.size} fact(s) about $component."
    }
}

// Counts how many times categorize() is actually called, so tests can
// assert that already-categorized transactions never get re-sent to
// Gemini - the "don't duplicate analysis" requirement.
class FakeTransactionCategorizer(private val categoryId: String = "OTHER") : TransactionCategorizer {
    var callCount: Int = 0
        private set

    override suspend fun categorize(transactions: List<Transaction>, categories: List<Category>): Map<String, String> {
        callCount++
        return transactions.associate { it.id to categoryId }
    }
}

// Like FakeTransactionCategorizer, but suspends for a bit before returning
// - lets a test observe a categorize job while it's still RUNNING (e.g. the
// /analysis page's job-running panel, or that a second page load while one
// is in flight doesn't start a duplicate job), which a same-tick fake would
// never leave a window to catch.
class SlowTransactionCategorizer(private val categoryId: String = "OTHER", private val delayMs: Long = 200) : TransactionCategorizer {
    var callCount: Int = 0
        private set

    override suspend fun categorize(transactions: List<Transaction>, categories: List<Category>): Map<String, String> {
        callCount++
        delay(delayMs)
        return transactions.associate { it.id to categoryId }
    }
}

// Polls /analysis/categorize/status until the background job started by a
// GET /analysis (categorization now runs automatically on page load - see
// CategorizationJob.kt) leaves RUNNING - tests need this since a GET
// /analysis that starts a job returns immediately while the Gemini call
// happens on an application-scoped coroutine.
private val statusJson = Json { ignoreUnknownKeys = true }

suspend fun HttpClient.waitForCategorizationToFinish(): CategorizationJobStatusResponse {
    repeat(200) {
        val text = get("/analysis/categorize/status").bodyAsText()
        val status = statusJson.decodeFromString<CategorizationJobStatusResponse>(text)
        if (status.status != "RUNNING") return status
        delay(10)
    }
    error("Categorization job did not finish in time")
}

data class CategorizeResult(val message: String?, val error: String?)

private val bannerSuccessRegex = Regex("""banner-success">([^<]*)</p>""")
private val bannerErrorRegex = Regex("""banner-error">([^<]*)</p>""")

// GET /analysis both triggers categorization and, if it finishes fast
// enough, consumes and displays its own result in the very same response
// (see CategorizationJobManager.consumeTerminal) - whether that happens
// depends on coroutine scheduling, not anything a test controls, so a fast
// fake categorizer can race either way. This checks the triggering
// response for an inline banner first, falling back to polling the status
// endpoint (safe only when the triggering response *didn't* already show
// a result, i.e. the job was still RUNNING), so tests get the eventual
// result regardless of which way the race goes.
suspend fun HttpClient.triggerCategorizeAndAwaitResult(url: String = "/analysis"): CategorizeResult {
    val body = get(url) { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
    bannerSuccessRegex.find(body)?.let { return CategorizeResult(it.groupValues[1], null) }
    bannerErrorRegex.find(body)?.let { return CategorizeResult(null, it.groupValues[1]) }
    val status = waitForCategorizationToFinish()
    return CategorizeResult(status.message, status.error)
}

// Polls GET /house/documents/{id}/status until the background extraction
// job started by POST /house/documents/upload leaves EXTRACTING - tests
// need this since the upload now redirects immediately while the Gemini
// call happens on an application-scoped coroutine (see
// HouseFactExtractionJob.kt).
suspend fun HttpClient.waitForExtractionToFinish(documentId: String): ExtractionJobStatusResponse {
    repeat(200) {
        val text = get("/house/documents/$documentId/status").bodyAsText()
        val status = statusJson.decodeFromString<ExtractionJobStatusResponse>(text)
        if (status.status != "EXTRACTING") return status
        delay(10)
    }
    error("Extraction job did not finish in time")
}
