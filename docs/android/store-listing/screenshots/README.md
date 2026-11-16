# Store listing screenshots

Roborazzi makes these reference screenshots. The `storeListing` tests in
`ScreensScreenshotTest` and `ChatScreenshotTest` use `captureStore` in
`RobolectricSupport.kt`. The tests use the same synthetic data as the other
reference screenshots. The examples are Example Detailing, Corner Bakery orders,
and Garden club newsletter. The email addresses use `example.test`.

Local host tests compare these reference screenshots. The original import did
not install CI. If you intend to record the reference screenshots again, enter
`./gradlew :shared:testAndroidHostTest -Pscreenshots.record=true --rerun-tasks`
from `android/`. Examine the changed images before you commit. Publication in
Play requires separate evidence.

| Folder | Pixels | Use in Play Console |
| --- | --- | --- |
| `phone/` | 1080 x 1920 | Phone screenshots |
| `tablet7/` | 1200 x 1920 | 7-inch tablet screenshots |
| `tablet10/` | 1600 x 2560 | 10-inch tablet screenshots |

Each name ends in `-light` or `-dark`. Phone screenshots are `1-chat`,
`2-projects`, `3-project-detail`, and `4-activity`. Tablet screenshots are
`1-chat` and `2-project-detail`. At widths of less than 840 dp, the short
single-column projects list and Activity leave most of a tablet frame empty.

Upload 2 to 8 screenshots for each device type. You can use light screenshots,
dark screenshots, or both. The frames show only the app content. They omit the
device bezel, status bar, and navigation bar. Tablet frames use portrait
orientation.

The stateless content composables omit the bottom navigation bar. Marketing
frames with captions require separate design work. The Activity screenshots
omit the "A change is ready for your review" banner. Its **Dismiss** label has
low contrast in dark mode, which requires a separate UI fix.
