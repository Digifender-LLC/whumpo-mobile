package com.whumpo.android.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.whumpo.android.ui.work.WorkAttachmentContent
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreWorkAttachmentController
import com.whumpo.core.auth.CoreWorkAttachmentFile
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CoreWorkAttachmentPreview(
    sessions: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    task: CoreWorkTask,
    close: () -> Unit,
) {
    val controller = remember(sessions, active, task) { CoreWorkAttachmentController(sessions, active, task) }
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    var picking by remember(controller) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (picking && uri != null) {
            scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) { readAttachment(resolver, uri) }
                    controller.selected(file)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    controller.invalidSelection()
                } finally {
                    picking = false
                }
            }
        } else picking = false
    }
    DisposableEffect(controller) { onDispose { controller.retire() } }
    val leave = {
        controller.retire()
        close()
    }
    BackHandler(onBack = leave)
    WorkAttachmentContent(
        task.title, state, picking,
        onChoose = {
            if (!picking && sessions.commitIfCurrent(active.epoch) {}) {
                picking = true
                picker.launch(CoreWorkAttachmentFile.allowedMediaTypes.toTypedArray())
            }
        },
        onConfirm = { scope.launch { controller.confirm() } },
        onRetry = { scope.launch { controller.retry() } },
        onClose = leave,
    )
}

private fun readAttachment(resolver: ContentResolver, uri: Uri): CoreWorkAttachmentFile {
    require(uri.scheme == ContentResolver.SCHEME_CONTENT)
    val mediaType = requireNotNull(resolver.getType(uri))
    require(mediaType in CoreWorkAttachmentFile.allowedMediaTypes)
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            require(cursor.moveToFirst())
            val size = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (size >= 0 && !cursor.isNull(size)) {
                require(cursor.getLong(size) in 1..CoreWorkAttachmentFile.MaximumBytes.toLong())
            }
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    val bytes = requireNotNull(resolver.openInputStream(uri)).use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(count > 0 && count <= CoreWorkAttachmentFile.MaximumBytes - output.size())
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    return CoreWorkAttachmentFile(requireNotNull(name), mediaType, bytes)
}
