# Project source history

In the connected local Debug workspace, open **Project source history** from Organization. Choose a workspace and project. The list shows the recorded version, confirmation date, and save type.

**Older versions** reads one more page from the same accepted head. If the server reports that history changed, the app clears the previous page and offers **Latest versions**. Leaving the project or ending the session clears the list. The screen also clears its selection when the app returns from the background.

This view uses the authenticated history list only. It has no file details, comparisons, restore commands, or provider settings. The app keeps the displayed metadata in memory.

Focus the synthetic local tests from `android/` with:

```sh
./gradlew :shared:jvmTest --tests '*CoreSourceHistoryTest'
./gradlew :shared:testAndroidHostTest --tests '*CoreSourceHistoryContentTest'
```
