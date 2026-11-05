package com.whumpo.android.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import com.whumpo.android.ui.activity.ActivityContent
import com.whumpo.android.ui.activity.ActivityList
import com.whumpo.android.ui.activity.ActivityRow
import com.whumpo.android.ui.activity.EventNotice
import com.whumpo.android.ui.common.AppLinks
import com.whumpo.android.ui.common.StatusChip
import com.whumpo.android.ui.common.TimeLabel
import com.whumpo.android.ui.common.Tone
import com.whumpo.android.ui.projects.PhotoRow
import com.whumpo.android.ui.projects.PhotosState
import com.whumpo.android.ui.projects.ProjectDetail
import com.whumpo.android.ui.projects.ProjectList
import com.whumpo.android.ui.projects.ProjectRow
import com.whumpo.android.ui.projects.ProjectsActions
import com.whumpo.android.ui.projects.ProjectsContent
import com.whumpo.android.ui.projects.UploadPhase
import com.whumpo.android.ui.projects.UploadRow
import com.whumpo.android.ui.projects.VersionRow
import com.whumpo.android.ui.settings.SettingsContent
import com.whumpo.android.ui.settings.SettingsUi
import com.whumpo.android.ui.shell.WidthClass
import com.whumpo.android.ui.state.UiState
import com.whumpo.android.ui.theme.ThemePreference
import com.whumpo.core.environment.EnvironmentKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for Projects (list, detail, empty, error), Activity and Settings at 360 and 1000 dp, light and dark.
 * The screens are the stateless `*Content` composables with fixed state, so the images are deterministic.
 * Record with `-Pscreenshots.record`; CI verifies.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreensScreenshotTest : RobolectricUiTest() {
    private val links = AppLinks("https://whumpo.com")
    private val widths = listOf(Screen.Compact, Screen.Expanded)

    private fun time(text: String, absolute: String) = TimeLabel(text, "$text, $absolute")

    private val detailling = ProjectRow(
        id = "11111111-1111-4111-8111-000000000001",
        name = "Example Detailing",
        purpose = "Mobile detailing in a synthetic city",
        status = StatusChip("Live", Tone.Ready),
        website = StatusChip("Live", Tone.Ready),
        app = StatusChip("Not live yet", Tone.Neutral),
        updated = time("Updated 5 min ago", "4 Oct 2026, 12:00 UTC"),
    )
    private val bakery = ProjectRow(
        id = "11111111-1111-4111-8111-000000000002",
        name = "Corner Bakery orders",
        purpose = "Pre-orders for the weekend rush",
        status = StatusChip("Draft", Tone.Neutral),
        website = StatusChip("Not live yet", Tone.Neutral),
        app = StatusChip("Not live yet", Tone.Neutral),
        updated = time("Updated 2 h ago", "4 Oct 2026, 10:05 UTC"),
    )
    private val garden = ProjectRow(
        id = "11111111-1111-4111-8111-000000000003",
        name = "Garden club newsletter",
        purpose = "Monthly sign-ups",
        status = StatusChip("Needs review", Tone.Progress),
        website = StatusChip("Not live yet", Tone.Neutral),
        app = StatusChip("Not live yet", Tone.Neutral),
        updated = time("Updated 3 days ago", "1 Oct 2026, 09:30 UTC"),
    )
    private val projects = ProjectList(listOf(detailling, bakery, garden), nextCursor = "next")

    private val detail = ProjectDetail(
        detailling,
        listOf(
            VersionRow("77777777-7777-4777-8777-000000000002", "business-landing@1", time("Saved 5 min ago", "4 Oct 2026, 12:00 UTC")),
            VersionRow("77777777-7777-4777-8777-000000000001", "business-landing@1", time("Saved 2 days ago", "2 Oct 2026, 08:15 UTC")),
        ),
    )
    private val photos = PhotosState(
        photos = UiState.Content(
            listOf(
                PhotoRow("99999999-9999-4999-8999-000000000001", "van.jpg", "1200 × 800, 200 KB", StatusChip("Ready", Tone.Ready)),
                PhotoRow("99999999-9999-4999-8999-000000000002", "shopfront.jpg", "1600 × 900, 640 KB", StatusChip("Checking", Tone.Progress)),
                PhotoRow("99999999-9999-4999-8999-000000000003", "receipt.jpg", "900 × 1200, 310 KB", StatusChip("Not approved", Tone.Attention)),
            ),
        ),
        uploads = listOf(UploadRow("aaaaaaaa-aaaa-4aaa-8aaa-000000000001", UploadPhase.Uploading)),
    )

    private val activity = ActivityList(
        listOf(
            ActivityRow("e1", "Saved a new version of Example Detailing", "Site saved", detailling.id, time("5 min ago", "4 Oct 2026, 12:00 UTC")),
            ActivityRow("e2", "Created project Corner Bakery orders", "Project created", bakery.id, time("2 h ago", "4 Oct 2026, 10:05 UTC")),
            ActivityRow("e3", "Added van.jpg to Example Detailing", "Photo added", detailling.id, time("1 day ago", "3 Oct 2026, 09:00 UTC")),
            ActivityRow("e4", "Created project Garden club newsletter", "Project created", garden.id, time("on 1 Oct 2026", "1 Oct 2026, 09:30 UTC")),
        ),
        nextCursor = "older",
    )

    private val settings = SettingsUi(
        signedIn = true,
        accountLabel = "o***@example.test",
        accountUnavailable = false,
        theme = ThemePreference.System,
        pushAvailable = false,
        notificationsOn = false,
        notificationsError = false,
        signingOut = false,
        environmentKind = EnvironmentKind.TEST,
        origin = "https://whumpo.com",
        versionName = "0.1.0",
    )

    @Composable
    private fun Sized(content: @Composable (WidthClass) -> Unit) =
        BoxWithConstraints { content(WidthClass.of(maxWidth)) }

    private val scenes: List<Pair<String, @Composable () -> Unit>> = listOf(
        "projects-list" to {
            Sized { w ->
                ProjectsContent(UiState.Content(projects), null, UiState.Loading, PhotosState(), w, links, ProjectsActions())
            }
        },
        "project-detail" to {
            Sized { w ->
                ProjectsContent(
                    UiState.Content(projects), detailling.id, UiState.Content(detail), photos, w, links,
                    ProjectsActions(onPickPhoto = {}),
                )
            }
        },
        "projects-empty" to {
            Sized { w -> ProjectsContent(UiState.Empty, null, UiState.Loading, PhotosState(), w, links, ProjectsActions()) }
        },
        "projects-error" to {
            Sized { w ->
                ProjectsContent(
                    UiState.Error("Couldn't reach Whumpo to load your projects.", "Nothing was changed."),
                    null, UiState.Loading, PhotosState(), w, links, ProjectsActions(),
                )
            }
        },
        "activity" to {
            Sized { w ->
                ActivityContent(
                    UiState.Content(activity), EventNotice("A change is ready for your review.", available = true), w, links,
                    onRefresh = {}, onLoadMore = {}, onDismissNotice = {}, onGoToChat = {},
                )
            }
        },
        "settings" to {
            Sized { w ->
                SettingsContent(settings, w, links, onTheme = {}, onNotifications = {}, onRetryAccount = {}, onSignOut = {})
            }
        },
    )

    @Test
    fun screensAt360And1000InLightAndDark() {
        for ((name, scene) in scenes) for (screen in widths) for (dark in listOf(false, true)) {
            capture(name, screen, dark, content = scene)
        }
    }

    @Test
    fun projectDetailAtLargeText() {
        capture("project-detail", Screen.Compact, dark = false, fontScale = 2f, content = scenes.first { it.first == "project-detail" }.second)
    }

    /**
     * Play listing images: synthetic content only, light and dark. No "show more" links and no notice banner, so the
     * frames read as finished screens. Tablets show only chat and the project detail: the list and Activity are short
     * single columns below 840dp and would leave most of the frame empty.
     */
    @Test
    fun storeListing() {
        val list: @Composable () -> Unit = {
            Sized { w ->
                ProjectsContent(UiState.Content(projects.copy(nextCursor = null)), null, UiState.Loading, PhotosState(), w, links, ProjectsActions())
            }
        }
        val activityScene: @Composable () -> Unit = {
            Sized { w ->
                ActivityContent(
                    UiState.Content(activity.copy(nextCursor = null)), null, w, links,
                    onRefresh = {}, onLoadMore = {}, onDismissNotice = {}, onGoToChat = {},
                )
            }
        }
        val detailScene = scenes.first { it.first == "project-detail" }.second
        for (dark in listOf(false, true)) {
            captureStore("2-projects", StoreDevice.Phone, dark, list)
            captureStore("3-project-detail", StoreDevice.Phone, dark, detailScene)
            captureStore("4-activity", StoreDevice.Phone, dark, activityScene)
            for (device in listOf(StoreDevice.Tablet7, StoreDevice.Tablet10)) {
                captureStore("2-project-detail", device, dark, detailScene)
            }
        }
    }
}
