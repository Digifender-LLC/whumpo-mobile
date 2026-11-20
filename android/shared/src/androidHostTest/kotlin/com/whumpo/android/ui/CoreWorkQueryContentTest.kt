package com.whumpo.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.android.ui.work.*
import com.whumpo.core.api.*
import com.whumpo.core.auth.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreWorkQueryContentTest : RobolectricUiTest() {
    @Test
    fun namedChoicesCountsAndOwnerCompletionClearOnScopeLoss() {
        val task = CoreWorkTask("task-1", "project-1", "Finish checklist", null, null, null, CoreTaskStatus.InProgress, 1)
        val item = CoreWorkQueryItem(task, "workspace-1", null)
        val counts = CoreWorkCounts(52, 0, CoreTaskStatus.entries.associateWith { if (it == CoreTaskStatus.InProgress) 52 else 0 })
        var state by mutableStateOf(CoreWorkQueryState(items = listOf(item),
            page = CoreWorkQueryPage(listOf(item), counts, "2026-11-19T12:00:00Z", "a".repeat(64)),
            choices = mapOf(CoreWorkDirectory.Project to CoreWorkChoiceState(listOf(CoreWorkChoice("project-1", "Named project"))),
                CoreWorkDirectory.Assignee to CoreWorkChoiceState(listOf(CoreWorkChoice("agent-1", "Private specialist")), "b".repeat(64)))))
        var owner by mutableStateOf(true)
        var completions = 0
        var more = 0
        launch(Screen.Compact, dark = true, fontScale = 1f) {
            WhumpoEntryTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CoreWorkQueryContent(state, CoreWorkQueryActions({}, {}, {}, { more++ }, { _, _ -> }, {}, {},
                        if (owner) ({ completions++ }) else null), owner)
                }
            }
        }.use {
            compose.onNodeWithText("52 matching tasks · 0 overdue").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Named project").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Complete task").performScrollTo().performClick()
            assertEquals(1, completions)
            compose.onNodeWithText("More matching tasks").performScrollTo().performClick()
            assertEquals(1, more)
            compose.runOnIdle { owner = false; state = state.copy(choices = emptyMap()) }
            compose.onNodeWithText("Complete task").assertDoesNotExist()
            compose.onNodeWithText("More Specific assignee choices").assertDoesNotExist()
            compose.runOnIdle { state = CoreWorkQueryState() }
            compose.onNodeWithText(task.title).assertDoesNotExist()
            compose.onNodeWithText("Named project").assertDoesNotExist()
            compose.onNodeWithText("52 matching tasks · 0 overdue").assertDoesNotExist()
        }
    }
}
