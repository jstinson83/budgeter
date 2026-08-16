package com.budgeter

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

private fun pdfFormData(filename: String = "inspection.pdf") = formData {
    append("file", "%PDF-1.4 fake".toByteArray(), Headers.build {
        append(HttpHeaders.ContentType, "application/pdf")
        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
    })
}

private suspend fun HttpClient.createProject(name: String, component: String = "ROOF"): String {
    val response = post("/projects") {
        contentType(ContentType.Application.FormUrlEncoded)
        setBody("name=$name&status=PLANNED&component=$component&priority=MEDIUM")
    }
    return response.headers[HttpHeaders.Location]!!.substringAfter("/projects/").substringBefore("?")
}

private suspend fun seedHouseFact(store: HouseFactRepository, ownerId: String, component: Component, what: String = "Roof shingles are original and worn") {
    store.addAll(
        ownerId, "doc-1",
        listOf(
            ExtractedFact(
                what = what,
                type = FactType.CONDITION,
                component = component,
                status = FactStatus.EXISTING,
                importance = Importance.HIGH,
                sourceQuote = null,
                sourceLocation = null,
                evidenceType = EvidenceType.OBSERVED,
                needsReview = false,
                reviewQuestion = null
            )
        )
    )
}

class ProjectRoutesTest {
    @Test
    fun testProjectsPageRequiresSignIn() = testApplication {
        testModule()

        val response = client.get("/projects")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testEmptyStateBeforeAnyProjectCreated() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.get("/projects") { header(HttpHeaders.Accept, "text/html") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("No projects yet"))
    }

    @Test
    fun testCreatingAProjectRedirectsToItsDetailPageAndListsItUnderItsStatus() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/projects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Replace roof&status=PLANNED&component=ROOF&priority=HIGH")
        }
        assertEquals(HttpStatusCode.Found, response.status)
        val redirect = response.headers[HttpHeaders.Location]!!
        assertTrue(redirect.startsWith("/projects/"))
        assertEquals("Created Replace roof", Url(redirect).parameters["message"])

        val listPage = client.get("/projects") { header(HttpHeaders.Accept, "text/html") }
        val body = listPage.bodyAsText()
        assertTrue(body.contains("Replace roof"))
        assertTrue(body.contains("Planned"))
    }

    @Test
    fun testCreatingAProjectWithMissingFieldsIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/projects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=&status=PLANNED&component=ROOF&priority=HIGH")
        }
        val redirect = response.headers[HttpHeaders.Location]!!
        assertEquals("/projects", Url(redirect).encodedPath)
        assertEquals("Please fill in all fields", Url(redirect).parameters["error"])
    }

    @Test
    fun testViewingAnUnknownProjectIs404() = testApplication {
        testModule()
        val client = signInFakeUser()

        assertEquals(HttpStatusCode.NotFound, client.get("/projects/not-real").status)
    }

    @Test
    fun testEditingAProjectUpdatesItsFieldsAndMovesItBetweenStatusGroups() = testApplication {
        testModule()
        val client = signInFakeUser()

        val createResponse = client.post("/projects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Replace roof&status=PLANNED&component=ROOF&priority=MEDIUM")
        }
        val projectId = createResponse.headers[HttpHeaders.Location]!!.substringAfter("/projects/").substringBefore("?")

        val editResponse = client.post("/projects/$projectId") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Replace roof (2026)&status=ACTIVE&component=ROOF&priority=HIGH")
        }
        assertEquals("Updated", Url(editResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val detailPage = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(detailPage.bodyAsText().contains("Replace roof (2026)"))

        val listPage = client.get("/projects") { header(HttpHeaders.Accept, "text/html") }
        val body = listPage.bodyAsText()
        assertTrue(body.contains("<h2>Active</h2>"))
        // The status has no PLANNED projects left, so its group heading
        // shouldn't render - "Planned" as plain text still appears in the
        // "Add project" form's option, so check for the group heading
        // specifically rather than the word anywhere on the page.
        assertFalse(body.contains("<h2>Planned</h2>"))
    }

    @Test
    fun testFilteringByComponentOnlyShowsMatchingProjects() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.post("/projects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Replace roof&status=PLANNED&component=ROOF&priority=MEDIUM")
        }
        client.post("/projects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Rewire panel&status=PLANNED&component=ELECTRICAL&priority=MEDIUM")
        }

        val filtered = client.get("/projects?component=ROOF") { header(HttpHeaders.Accept, "text/html") }
        val body = filtered.bodyAsText()
        assertTrue(body.contains("Replace roof"))
        assertFalse(body.contains("Rewire panel"))
    }

    @Test
    fun testLinkingAFactMovesItFromAvailableToLinkedAndDetachMovesItBack() = testApplication {
        testModule()
        val client = signInFakeUser()
        val projectId = client.createProject("Replace roof")

        val uploadResponse = client.submitFormWithBinaryData(url = "/house/documents/upload", formData = pdfFormData())
        val documentId = uploadResponse.headers[HttpHeaders.Location]!!.substringAfterLast("/")
        client.waitForExtractionToFinish(documentId)

        val beforePage = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(beforePage.contains("No facts linked yet"))
        val factId = Regex("""<option value="(house-fact-\d+)">""").find(beforePage)!!.groupValues[1]

        val attachResponse = client.post("/projects/$projectId/facts") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("factId=$factId")
        }
        assertEquals("Linked fact", Url(attachResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterAttach = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertFalse(afterAttach.contains("No facts linked yet"))
        assertTrue(afterAttach.contains("steel structural columns"))

        val detachResponse = client.post("/projects/$projectId/facts/$factId/detach")
        assertEquals("Removed fact", Url(detachResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterDetach = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(afterDetach.contains("No facts linked yet"))
    }

    @Test
    fun testAttachingAFactThatDoesNotBelongToTheOwnerIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()
        val projectId = client.createProject("Replace roof")

        val response = client.post("/projects/$projectId/facts") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("factId=not-real")
        }
        assertEquals("Fact not found", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testLinkingADocumentMovesItFromAvailableToLinkedAndDetachMovesItBack() = testApplication {
        testModule()
        val client = signInFakeUser()
        val projectId = client.createProject("Replace roof")

        val uploadResponse = client.submitFormWithBinaryData(url = "/house/documents/upload", formData = pdfFormData("inspection.pdf"))
        val documentId = uploadResponse.headers[HttpHeaders.Location]!!.substringAfterLast("/")
        client.waitForExtractionToFinish(documentId)

        val beforePage = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(beforePage.contains("No documents linked yet"))

        val attachResponse = client.post("/projects/$projectId/documents") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("documentId=$documentId")
        }
        assertEquals("Linked document", Url(attachResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterAttach = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertFalse(afterAttach.contains("No documents linked yet"))
        assertTrue(afterAttach.contains("inspection.pdf"))

        val detachResponse = client.post("/projects/$projectId/documents/$documentId/detach")
        assertEquals("Removed document", Url(detachResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterDetach = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(afterDetach.contains("No documents linked yet"))
    }

    @Test
    fun testAddingPlainTextIsDetectedAsANoteAndCanBeRemoved() = testApplication {
        testModule()
        val client = signInFakeUser()
        val projectId = client.createProject("Replace roof")

        val addResponse = client.submitFormWithBinaryData(
            url = "/projects/$projectId/entries",
            formData = formData { append("text", "Contractor recommended architectural shingles") }
        )
        assertEquals("Added", Url(addResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val page = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertFalse(page.contains("Nothing added yet"))
        assertTrue(page.contains("Contractor recommended architectural shingles"))
        assertTrue(page.contains(">Note<"))
        val entryId = Regex("""/projects/$projectId/entries/([^"/]+)/delete""").find(page)!!.groupValues[1]

        val deleteResponse = client.post("/projects/$projectId/entries/$entryId/delete")
        assertEquals("Removed", Url(deleteResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterDelete = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(afterDelete.contains("Nothing added yet"))
    }

    @Test
    fun testAddingAnEmptyEntryIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()
        val projectId = client.createProject("Replace roof")

        val response = client.submitFormWithBinaryData(url = "/projects/$projectId/entries", formData = formData {})
        assertEquals("Please add some text or a file", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testTextContainingAUrlIsDetectedAsALinkAndRendersItClickably() = testApplication {
        testModule()
        val client = signInFakeUser()
        val projectId = client.createProject("Replace roof")

        val addResponse = client.submitFormWithBinaryData(
            url = "/projects/$projectId/entries",
            formData = formData { append("text", "Found this one: https://example.com/roofing-quote looks good") }
        )
        assertEquals("Added", Url(addResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val page = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains(">Link<"))
        assertTrue(page.contains("""href="https://example.com/roofing-quote""""))
        // The full typed text (not just the bare URL) is preserved too.
        assertTrue(page.contains("Found this one:"))
    }

    @Test
    fun testEveryUrlInTheTextGetsLinkifiedNotJustTheFirst() = testApplication {
        testModule()
        val client = signInFakeUser()
        val projectId = client.createProject("Replace roof")

        client.submitFormWithBinaryData(
            url = "/projects/$projectId/entries",
            formData = formData {
                append("text", "Comparing https://example.com/quote-a and https://example.com/quote-b")
            }
        )

        val page = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("""href="https://example.com/quote-a""""))
        assertTrue(page.contains("""href="https://example.com/quote-b""""))
        assertTrue(page.contains("Comparing"))
        assertTrue(page.contains(" and "))
    }

    @Test
    fun testTrailingSentencePunctuationIsNotSweptIntoTheLinkHref() = testApplication {
        testModule()
        val client = signInFakeUser()
        val projectId = client.createProject("Replace roof")

        client.submitFormWithBinaryData(
            url = "/projects/$projectId/entries",
            formData = formData {
                append("text", "Comparing https://example.com/quote-a, https://example.com/quote-b, and https://example.com/quote-c.")
            }
        )

        val page = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        // Exact hrefs, no trailing comma/period swept in - a broken link
        // would 404 on a URL that doesn't actually exist.
        assertTrue(page.contains("""href="https://example.com/quote-a""""))
        assertTrue(page.contains("""href="https://example.com/quote-b""""))
        assertTrue(page.contains("""href="https://example.com/quote-c""""))
        assertFalse(page.contains("""href="https://example.com/quote-a,""""))
        assertFalse(page.contains("""href="https://example.com/quote-c.""""))
    }

    @Test
    fun testAttachingAnImageIsDetectedAsAPhotoAndAttachingOtherFilesIsDetectedAsAQuote() = testApplication {
        testModule()
        val client = signInFakeUser()
        val projectId = client.createProject("Replace roof")

        client.submitFormWithBinaryData(
            url = "/projects/$projectId/entries",
            formData = formData {
                append("text", "Before/after shot")
                append("file", "fake image bytes".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"roof.jpg\"")
                })
            }
        )
        client.submitFormWithBinaryData(
            url = "/projects/$projectId/entries",
            formData = formData {
                append("text", "Roofing Co. estimate")
                append("file", "fake quote bytes".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "application/pdf")
                    append(HttpHeaders.ContentDisposition, "filename=\"quote.pdf\"")
                })
            }
        )

        val page = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains(">Photo<"))
        assertTrue(page.contains("Attached: roof.jpg"))
        assertTrue(page.contains(">Quote<"))
        assertTrue(page.contains("Attached: quote.pdf"))
    }

    @Test
    fun testCreatingAProjectWithAParentIdMakesItASubproject() = testApplication {
        testModule()
        val client = signInFakeUser()
        val parentId = client.createProject("Kitchen renovation")

        val response = client.post("/projects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Electrical rough-in&status=PLANNED&component=ELECTRICAL&priority=MEDIUM&parentId=$parentId")
        }
        val redirect = response.headers[HttpHeaders.Location]!!
        val childId = redirect.substringAfter("/projects/").substringBefore("?")

        val childPage = client.get("/projects/$childId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(childPage.contains("Subproject of"))
        assertTrue(childPage.contains("Kitchen renovation"))

        val parentPage = client.get("/projects/$parentId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(parentPage.contains("Electrical rough-in"))

        val listPage = client.get("/projects") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(listPage.contains("Kitchen renovation"))
    }

    @Test
    fun testAttachingAnExistingProjectAsASubprojectAndDetachingItAgain() = testApplication {
        testModule()
        val client = signInFakeUser()
        val parentId = client.createProject("Kitchen renovation")
        val childId = client.createProject("Cabinets")

        val attachResponse = client.post("/projects/$parentId/subprojects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("childId=$childId")
        }
        assertEquals("Added Cabinets as a subproject", Url(attachResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val parentPage = client.get("/projects/$parentId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(parentPage.contains("Cabinets"))

        val detachResponse = client.post("/projects/$parentId/subprojects/$childId/detach")
        assertEquals("Removed Cabinets from this project", Url(detachResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val childPage = client.get("/projects/$childId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertFalse(childPage.contains("Subproject of"))
    }

    @Test
    fun testASubprojectCannotItselfBeGivenSubprojects() = testApplication {
        testModule()
        val client = signInFakeUser()
        val parentId = client.createProject("Kitchen renovation")
        val childId = client.createProject("Cabinets")
        val grandchildId = client.createProject("Cabinet hardware")

        client.post("/projects/$parentId/subprojects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("childId=$childId")
        }

        val response = client.post("/projects/$childId/subprojects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("childId=$grandchildId")
        }
        val redirect = response.headers[HttpHeaders.Location]!!
        assertEquals("/projects/$childId", Url(redirect).encodedPath)
        assertEquals("A subproject can't itself have subprojects", Url(redirect).parameters["error"])
    }

    @Test
    fun testAProjectAlreadyHavingSubprojectsCannotBecomeASubprojectItself() = testApplication {
        testModule()
        val client = signInFakeUser()
        val parentId = client.createProject("Kitchen renovation")
        val childId = client.createProject("Cabinets")
        val otherParentId = client.createProject("Bathroom renovation")

        client.post("/projects/$parentId/subprojects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("childId=$childId")
        }

        val response = client.post("/projects/$otherParentId/subprojects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("childId=$parentId")
        }
        val redirect = response.headers[HttpHeaders.Location]!!
        assertEquals("Kitchen renovation already has subprojects of its own", Url(redirect).parameters["error"])
    }

    @Test
    fun testGeneratingRecommendationsRequiresSignIn() = testApplication {
        testModule()

        assertEquals(HttpStatusCode.Unauthorized, client.post("/projects/recommendations/generate").status)
    }

    @Test
    fun testGeneratingRecommendationsPersistsThemAndReportsASummaryMessage() = testApplication {
        val houseFactStore = FakeHouseFactRepository()
        val recommendationStore = FakeRecommendationRepository()
        val generator = FakeRecommendationGenerator()
        testModule(houseFactStore = houseFactStore, recommendationStore = recommendationStore, recommendationGenerator = generator)
        val client = signInFakeUser()
        seedHouseFact(houseFactStore, "test-sub", Component.ROOF)

        client.post("/projects/recommendations/generate")
        val status = client.waitForRecommendationGenerationToFinish()
        assertEquals("Generated 1 recommendation(s) across 1 component(s)", status.message)
        assertEquals(1, generator.callCount)

        val stored = recommendationStore.all("test-sub")
        assertEquals(1, stored.size)
        assertEquals(Component.ROOF, stored[0].component)
        assertEquals(RecommendationStatus.PENDING, stored[0].status)
        assertEquals(1, stored[0].supportingFactIds.size)

        // Also shows up as the page's own one-shot banner (consumeTerminal).
        val page = client.get("/projects") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("Generated 1 recommendation(s) across 1 component(s)"))
    }

    @Test
    fun testRegeneratingWithNoNewFactsSkipsTheStaleComponentAndDoesNotCallGeminiAgain() = testApplication {
        val houseFactStore = FakeHouseFactRepository()
        val generator = FakeRecommendationGenerator()
        testModule(houseFactStore = houseFactStore, recommendationGenerator = generator)
        val client = signInFakeUser()
        seedHouseFact(houseFactStore, "test-sub", Component.ROOF)

        client.post("/projects/recommendations/generate")
        client.waitForRecommendationGenerationToFinish()
        assertEquals(1, generator.callCount)

        client.post("/projects/recommendations/generate")
        val secondStatus = client.waitForRecommendationGenerationToFinish()
        assertEquals("Nothing new to recommend", secondStatus.message)
        assertEquals(1, generator.callCount)
    }

    @Test
    fun testGeneratingWithNoFactsAtAllReportsNothingNewToRecommend() = testApplication {
        val generator = FakeRecommendationGenerator()
        testModule(recommendationGenerator = generator)
        val client = signInFakeUser()

        client.post("/projects/recommendations/generate")
        val status = client.waitForRecommendationGenerationToFinish()
        assertEquals("Nothing new to recommend", status.message)
        assertEquals(0, generator.callCount)
    }

    @Test
    fun testANewFactAfterGenerationMakesTheComponentStaleAgain() = testApplication {
        val houseFactStore = FakeHouseFactRepository()
        val generator = FakeRecommendationGenerator()
        testModule(houseFactStore = houseFactStore, recommendationGenerator = generator)
        val client = signInFakeUser()
        seedHouseFact(houseFactStore, "test-sub", Component.ROOF, "First roof fact")

        client.post("/projects/recommendations/generate")
        client.waitForRecommendationGenerationToFinish()
        assertEquals(1, generator.callCount)

        seedHouseFact(houseFactStore, "test-sub", Component.ROOF, "Second roof fact")
        client.post("/projects/recommendations/generate")
        val status = client.waitForRecommendationGenerationToFinish()
        assertEquals("Generated 1 recommendation(s) across 1 component(s)", status.message)
        assertEquals(2, generator.callCount)
    }

    @Test
    fun testGenerationJobRunningStateIsVisibleWhilePolling() = testApplication {
        val houseFactStore = FakeHouseFactRepository()
        testModule(houseFactStore = houseFactStore, recommendationGenerator = SlowRecommendationGenerator())
        val client = signInFakeUser()
        seedHouseFact(houseFactStore, "test-sub", Component.ROOF)

        client.post("/projects/recommendations/generate")
        val runningStatus = client.get("/projects/recommendations/status").bodyAsText()
        assertTrue(runningStatus.contains("RUNNING"))

        val finalStatus = client.waitForRecommendationGenerationToFinish()
        assertEquals(RecommendationJobStatus.DONE.name, finalStatus.status)
    }

    @Test
    fun testPendingRecommendationRendersOnTheProjectsPageWithItsSupportingFact() = testApplication {
        val houseFactStore = FakeHouseFactRepository()
        testModule(houseFactStore = houseFactStore, recommendationGenerator = FakeRecommendationGenerator())
        val client = signInFakeUser()
        seedHouseFact(houseFactStore, "test-sub", Component.ROOF, "Roof shingles are original and worn")

        client.post("/projects/recommendations/generate")
        client.waitForRecommendationGenerationToFinish()

        val page = client.get("/projects") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("Recommended project for ROOF"))
        assertTrue(page.contains("Roof shingles are original and worn"))
        assertTrue(page.contains("/projects/recommendations/") && page.contains("/accept"))
        assertTrue(page.contains("/projects/recommendations/") && page.contains("/reject"))
    }

    @Test
    fun testAcceptingARecommendationCreatesAPlannedProjectWithSeededFieldsAndAttachedFact() = testApplication {
        val houseFactStore = FakeHouseFactRepository()
        val recommendationStore = FakeRecommendationRepository()
        testModule(houseFactStore = houseFactStore, recommendationStore = recommendationStore, recommendationGenerator = FakeRecommendationGenerator())
        val client = signInFakeUser()
        seedHouseFact(houseFactStore, "test-sub", Component.ROOF)

        client.post("/projects/recommendations/generate")
        client.waitForRecommendationGenerationToFinish()
        val recommendationId = recommendationStore.all("test-sub").single().id

        val acceptResponse = client.post("/projects/recommendations/$recommendationId/accept")
        val redirect = acceptResponse.headers[HttpHeaders.Location]!!
        assertTrue(redirect.startsWith("/projects/"))
        assertEquals("Created Recommended project for ROOF", Url(redirect).parameters["message"])

        val projectPage = client.get(redirect) { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(projectPage.contains("Recommended project for ROOF"))
        assertTrue(projectPage.contains(">Planned<"))
        assertTrue(projectPage.contains(">Roof<"))
        // The recommendation's supporting fact was attached automatically.
        assertFalse(projectPage.contains("No facts linked yet"))

        val recommendation = recommendationStore.all("test-sub").single()
        assertEquals(RecommendationStatus.ACCEPTED, recommendation.status)

        // Accepted recommendations drop off the pending list.
        val projectsPage = client.get("/projects") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertFalse(projectsPage.contains("<h2>Recommendations</h2>"))
    }

    @Test
    fun testRejectingARecommendationRemovesItFromThePendingListWithoutCreatingAProject() = testApplication {
        val houseFactStore = FakeHouseFactRepository()
        val recommendationStore = FakeRecommendationRepository()
        val projectStore = FakeProjectRepository()
        testModule(houseFactStore = houseFactStore, recommendationStore = recommendationStore, projectStore = projectStore, recommendationGenerator = FakeRecommendationGenerator())
        val client = signInFakeUser()
        seedHouseFact(houseFactStore, "test-sub", Component.ROOF)

        client.post("/projects/recommendations/generate")
        client.waitForRecommendationGenerationToFinish()
        val recommendationId = recommendationStore.all("test-sub").single().id

        val rejectResponse = client.post("/projects/recommendations/$recommendationId/reject")
        assertEquals(
            "Dismissed Recommended project for ROOF",
            Url(rejectResponse.headers[HttpHeaders.Location]!!).parameters["message"]
        )

        assertEquals(RecommendationStatus.REJECTED, recommendationStore.all("test-sub").single().status)
        assertEquals(emptyList(), projectStore.all("test-sub"))

        val projectsPage = client.get("/projects") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertFalse(projectsPage.contains("<h2>Recommendations</h2>"))
    }

    @Test
    fun testAcceptingAnUnknownRecommendationRedirectsWithAnError() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/projects/recommendations/not-real/accept")
        assertEquals("Recommendation not found", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testRejectingAnUnknownRecommendationIs404() = testApplication {
        testModule()
        val client = signInFakeUser()

        assertEquals(HttpStatusCode.NotFound, client.post("/projects/recommendations/not-real/reject").status)
    }
}
