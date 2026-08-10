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
    sessionSecret: String = "test-session-secret",
    transactionStore: TransactionRepository = FakeTransactionRepository(),
    transactionCategorizer: TransactionCategorizer = FakeTransactionCategorizer(),
    categorizationRuleStore: CategorizationRuleRepository = FakeCategorizationRuleRepository(),
    categoryStore: CategoryRepository = FakeCategoryRepository()
) {
    application {
        module(
            oauthClient = oauthClient,
            oauthRedirectBaseUrl = oauthRedirectBaseUrl,
            sessionSecret = sessionSecret,
            transactionStore = transactionStore,
            transactionCategorizer = transactionCategorizer,
            categorizationRuleStore = categorizationRuleStore,
            categoryStore = categoryStore
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
            val transaction = Transaction(fingerprint, ownerId, parsed.accountType, parsed.date, parsed.description, parsed.amount, balance = parsed.balance)
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
