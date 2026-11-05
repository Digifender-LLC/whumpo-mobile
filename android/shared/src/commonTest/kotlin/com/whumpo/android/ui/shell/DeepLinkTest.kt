package com.whumpo.android.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinkTest {
    private val id = "11111111-1111-4111-8111-000000000001"

    @Test
    fun projectAndActivityLinksParse() {
        assertEquals(DeepLink.Project(id), DeepLink.parse("https://whumpo.com/app/projects/$id"))
        assertEquals(DeepLink.ActivityEvent(id), DeepLink.parse("https://whumpo.com/app/activity/$id?utm=x"))
        assertEquals(TopDestination.Projects, DeepLink.Project(id).destination)
        assertEquals(TopDestination.Activity, DeepLink.ActivityEvent(id).destination)
    }

    @Test
    fun everythingElseIsRefused() {
        listOf(
            "http://whumpo.com/app/projects/$id",
            "https://evil.example/app/projects/$id",
            "https://whumpo.com.evil.example/app/projects/$id",
            "https://whumpo.com:8443/app/projects/$id",
            "https://whumpo.com/app/projects/$id/extra",
            "https://whumpo.com/app/projects/$id#frag",
            "https://whumpo.com/app/projects/",
            "https://whumpo.com/app/projects/not-a-uuid",
            "https://whumpo.com/app/projects/AAAAAAAA-AAAA-4AAA-8AAA-000000000001",
            "https://whumpo.com/app/settings/$id",
            "https://whumpo.com/app/return?code=abc&state=def",
            "https://whumpo.com/projects/$id",
        ).forEach { assertNull(DeepLink.parse(it), it) }
    }
}
