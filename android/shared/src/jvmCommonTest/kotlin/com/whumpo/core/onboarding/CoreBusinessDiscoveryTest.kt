package com.whumpo.core.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreBusinessDiscoveryTest {
    private fun CoreBusinessDiscovery.give(question: CoreDiscoveryQuestion, value: String) =
        requireNotNull(answer(question, CoreDiscoveryAnswer.Declared(value)))

    @Test
    fun countryChangeReopensLocalQuestionsAndRemovesEinWithoutDiscardingWebsite() {
        val us = CoreBusinessDiscovery()
            .give(CoreDiscoveryQuestion.Country, "United States")
            .give(CoreDiscoveryQuestion.Region, "Illinois")
            .give(CoreDiscoveryQuestion.Registration, "Not registered")
            .give(CoreDiscoveryQuestion.Formation, "Considering it")
            .give(CoreDiscoveryQuestion.Ein, "Would like guidance")
            .give(CoreDiscoveryQuestion.Website, "Have a site")
            .give(CoreDiscoveryQuestion.WebsiteUrl, "https://example.com")
        val actions = us.starterActions()
        assertEquals(setOf(CoreStarterKind.Registration), actions.first { it.kind == CoreStarterKind.EinGuidance }.requires)
        assertTrue(actions.indexOfFirst { it.kind == CoreStarterKind.Registration } < actions.indexOfFirst { it.kind == CoreStarterKind.EinGuidance })
        val nonUs = us.give(CoreDiscoveryQuestion.Country, "Another country")
            .give(CoreDiscoveryQuestion.CountryName, "Canada")
        assertFalse(CoreDiscoveryQuestion.Ein in nonUs.questions())
        assertNull(nonUs.answer(CoreDiscoveryQuestion.Ein, CoreDiscoveryAnswer.Unknown))
        assertNull(nonUs.declared(CoreDiscoveryQuestion.Region))
        assertNull(nonUs.declared(CoreDiscoveryQuestion.Registration))
        assertEquals("https://example.com", nonUs.declared(CoreDiscoveryQuestion.WebsiteUrl))
        assertFalse(nonUs.starterActions().any { it.kind == CoreStarterKind.EinGuidance })
    }

    @Test
    fun connectionDependsOnAccessAndMigrationWhenReusingAnExistingSite() {
        val site = CoreBusinessDiscovery()
            .give(CoreDiscoveryQuestion.Website, "Have a site")
            .give(CoreDiscoveryQuestion.WebsiteUrl, "https://example.com/store")
            .give(CoreDiscoveryQuestion.WebsiteIntent, "Improve it")
            .give(CoreDiscoveryQuestion.Domain, "Someone else manages it")
            .give(CoreDiscoveryQuestion.Hostname, "example.com")
            .give(CoreDiscoveryQuestion.DomainHelp, "Plan a connection")
        fun requirements(discovery: CoreBusinessDiscovery) =
            discovery.starterActions().first { it.kind == CoreStarterKind.DomainConnection }.requires
        assertEquals(setOf(CoreStarterKind.DomainAccess, CoreStarterKind.DomainMigration), requirements(site))
        val known = site.give(CoreDiscoveryQuestion.DomainAccess, "I can access settings")
        assertEquals(setOf(CoreStarterKind.DomainMigration), requirements(known))
        val changed = known.give(CoreDiscoveryQuestion.Domain, "I need one")
        assertNull(changed.declared(CoreDiscoveryQuestion.Hostname))
        assertNull(changed.declared(CoreDiscoveryQuestion.DomainHelp))
        assertNull(changed.declared(CoreDiscoveryQuestion.DomainAccess))
        assertEquals("https://example.com/store", changed.declared(CoreDiscoveryQuestion.WebsiteUrl))
        val newSite = changed.give(CoreDiscoveryQuestion.Website, "No site")
        assertNull(newSite.declared(CoreDiscoveryQuestion.WebsiteIntent))
        assertNull(newSite.declared(CoreDiscoveryQuestion.WebsiteUrl))
    }

    @Test
    fun uncertaintyAndDeferralRemainDistinctAndAvoidAssumptions() {
        val discovery = requireNotNull(CoreBusinessDiscovery().answer(CoreDiscoveryQuestion.Country, CoreDiscoveryAnswer.Unknown))
            .give(CoreDiscoveryQuestion.Help, "Website and leads")
            .deferRemaining()
        assertEquals(CoreDiscoveryAnswer.Unknown, discovery.answers[CoreDiscoveryQuestion.Country])
        assertEquals(CoreDiscoveryAnswer.Deferred, discovery.answers[CoreDiscoveryQuestion.Website])
        assertFalse(CoreDiscoveryQuestion.Region in discovery.questions())
        assertNull(discovery.next)
        assertEquals(CoreStarterKind.ReviewSite, discovery.starterActions().first().kind)
        assertFalse(discovery.starterActions().any { it.kind in setOf(CoreStarterKind.Registration, CoreStarterKind.EinGuidance) })
    }

    @Test
    fun boundedPublicAddressesExcludeCredentialsAndPrivateNumbers() {
        val discovery = CoreBusinessDiscovery()
            .give(CoreDiscoveryQuestion.Website, "Have a site")
            .give(CoreDiscoveryQuestion.Domain, "I manage one")
            .give(CoreDiscoveryQuestion.Country, "United States")
            .give(CoreDiscoveryQuestion.Registration, "Already registered")
        for (address in listOf("javascript:alert()", "https://person:secret@example.com",
            "https://example.com/?session=private", "https://example.com/#private", "https://local")) {
            assertNull(discovery.answer(CoreDiscoveryQuestion.WebsiteUrl, CoreDiscoveryAnswer.Declared(address)))
        }
        for (hostname in listOf("https://example.com", "-example.com", "example.com/path", "123456789.com")) {
            assertNull(discovery.answer(CoreDiscoveryQuestion.Hostname, CoreDiscoveryAnswer.Declared(hostname)))
        }
        assertNull(discovery.answer(CoreDiscoveryQuestion.Ein, CoreDiscoveryAnswer.Declared("12-3456789")))
        assertNull(discovery.answer(CoreDiscoveryQuestion.Customers, CoreDiscoveryAnswer.Declared("a".repeat(161))))
        assertEquals("https://example.com/hello", discovery.give(CoreDiscoveryQuestion.WebsiteUrl, " https://example.com/hello ").declared(CoreDiscoveryQuestion.WebsiteUrl))
    }
}
