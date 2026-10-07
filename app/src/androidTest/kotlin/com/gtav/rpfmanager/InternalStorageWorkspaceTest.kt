package com.gtav.rpfmanager

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.gtav.rpfmanager.data.model.AppState
import com.gtav.rpfmanager.data.model.BatchKind
import com.gtav.rpfmanager.viewmodel.ArchiveViewModel
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses this application's normal permission settings page, restoring its original access afterward. */
@SdkSuppress(minSdkVersion = 30)
class InternalStorageWorkspaceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var vm: ArchiveViewModel

    @Suppress("DEPRECATION")
    private fun permissionSwitch(): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            nodes += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        try {
            if (root.packageName?.toString() != "com.android.settings") return null
            if (nodes.none { it.text?.toString() == context.getString(R.string.app_name) }) return null
            val switches = nodes.filter { it.isCheckable && it.className?.toString()?.contains("Switch") == true }
            // Only an app-specific page naming this application and exposing exactly one switch is accepted.
            return switches.singleOrNull()?.let(AccessibilityNodeInfo::obtain)
        } finally { nodes.forEach { it.recycle() } }
    }
    @Suppress("DEPRECATION")
    private fun changePermissionInSettings(granted: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var sawSettings = false
        var lastAction = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            val toggle = permissionSwitch()
            if (toggle != null) {
                sawSettings = true
                try {
                    if (Environment.isExternalStorageManager() == granted) return
                    if (toggle.refresh() && toggle.isEnabled && toggle.isChecked != granted &&
                        SystemClock.uptimeMillis() - lastAction >= 400) {
                        lastAction = SystemClock.uptimeMillis()
                        val clicked = toggle.isClickable && toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (!clicked) {
                            // Some Settings versions handle the preference row, not its switch widget.
                            // A false action can still change access, so check before attempting its parent.
                            SystemClock.sleep(100)
                            if (Environment.isExternalStorageManager() != granted &&
                                toggle.refresh() && toggle.isChecked != granted) {
                                var parent = toggle.parent
                                var depth = 0
                                while (parent != null && depth++ < 4) {
                                    val current = parent
                                    parent = null
                                    try {
                                        if (current.packageName?.toString() != "com.android.settings") break
                                        if (current.isEnabled && current.isClickable && current.isVisibleToUser) {
                                            if (Environment.isExternalStorageManager() != granted)
                                                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                            break
                                        }
                                        parent = current.parent
                                    } finally { current.recycle() }
                                }
                                parent?.recycle()
                            }
                        }
                    }
                } finally { toggle.recycle() }
                if (Environment.isExternalStorageManager() == granted) return
            }
            SystemClock.sleep(100)
        }
        check(sawSettings) { "Expected this RPF Manager permission page with one checkable switch" }
        assertEquals("System settings did not apply the requested access", granted, Environment.isExternalStorageManager())
    }
    private fun backFromSettings() {
        val now = SystemClock.uptimeMillis()
        check(instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true))
        check(instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true))
    }
    private fun node(tag: String) = ui.onNodeWithTag(tag)
    private fun waitState(predicate: (AppState) -> Boolean) {
        ui.waitUntil(20_000) {
            val state = vm.state.value
            assertNull("Unexpected file operation error", state.error)
            state.busy == null && !state.storageSearching && !state.archiveSearching && predicate(state)
        }
        ui.waitForIdle()
    }
    private fun row(name: String, local: Boolean = true): SemanticsNodeInteraction {
        val tag = (if (local) "storage_entry_" else "entry_") + name
        node(if (local) "storage_file_list" else "file_list").performScrollToNode(hasTestTag(tag))
        return node(tag)
    }
    private fun tool(tag: String) = node(tag).performScrollTo().performClick()
    private fun capture(name: String) {
        ui.waitForIdle()
        android.os.SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun execute(kind: BatchKind, count: Int) {
        waitState { it.batchConfirmation != null }
        assertEquals(kind, vm.state.value.batchConfirmation?.kind)
        node("batch_execute").performClick()
        waitState { it.batchResult != null }
        val result = requireNotNull(vm.state.value.batchResult)
        assertEquals(result.failures.toString(), 0, result.failed)
        assertEquals(count, result.completed)
        node("batch_done").performClick()
    }

    @Test fun internalRootAndRecursiveSearchWithoutOpeningAnArchiveFirst() {
        ui.runOnUiThread {
            vm = ViewModelProvider(ui.activity)[ArchiveViewModel::class.java]
            ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val packageName = context.packageName
        check(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        val originallyGranted = Environment.isExternalStorageManager()
        val restoreDenied = !originallyGranted ||
            InstrumentationRegistry.getArguments().getString("restoreDenied")?.toBooleanStrictOrNull() == true
        val preferences = context.getSharedPreferences("file_workspace", Context.MODE_PRIVATE)
        val oldTree = preferences.getString("tree", null)
        val fixture = File(context.getExternalFilesDir(null), "internal-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(fixture, "source.rpf")
        var originalFailure: Throwable? = null
        try {
            if (!originallyGranted) {
                ui.waitUntil(10_000) {
                    runCatching {
                        ui.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT &&
                            ui.onAllNodesWithTag("browse_internal_storage").fetchSemanticsNodes().isNotEmpty()
                    }.getOrDefault(false)
                }
                node("browse_internal_storage").performScrollTo().performClick()
                changePermissionInSettings(true)
                backFromSettings()
            } else ui.runOnUiThread { vm.browseInternalStorage() }
            waitState { it.storage?.directory?.uri == Uri.fromFile(Environment.getExternalStorageDirectory()).toString() }
            assertNull(vm.state.value.location)
            node("file_workspace").assertExists()
            node("archive_pane").assertExists()
            node("storage_pane").assertExists()
            capture("internal-storage-root-without-rpf")

            val input = File(fixture, "input").apply { mkdirs() }
            File(input, "deep/needle-one.txt").apply { parentFile!!.mkdirs(); writeText("first recursive result") }
            File(input, "needle-two.txt").writeText("second recursive result")
            File(input, "unrelated.txt").writeText("outside the search selection")
            val packed = File(fixture, "packed").apply { mkdirs() }
            File(packed, "archive-deep/match.txt").apply { parentFile!!.mkdirs(); writeText("archive subtree result") }
            File(packed, "root.txt").writeText("unchanged root")
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                RpfCore.packDirectory(packed.path, it.fd)
            }
            packed.deleteRecursively()
            // Jump only to this application's private synthetic fixture; descendants are browsed via UI.
            ui.runOnUiThread { vm.storageDirectorySelected(Uri.fromFile(fixture)) }
            waitState { it.storage?.directory?.name == fixture.name }
            row("source.rpf").performClick()
            tool("open_storage_selection")
            waitState { it.location?.session?.name == "source.rpf" }
            node("storage_pane_tab").performClick()
            if (vm.state.value.storageSelectionMode) node("clear_selection").performClick()
            row("input").performClick()
            waitState { it.storage?.directory?.name == "input" }
            node("storage_search").performTextInput("needle")
            node("storage_search").performImeAction()
            waitState { it.visibleStorageEntries.size == 2 }
            assertTrue(vm.state.value.visibleStorageEntries.any { Uri.parse(it.uri).path?.endsWith("deep/needle-one.txt") == true })
            row("needle-one.txt").performTouchInput { longClick() }
            node("select_all").performClick()
            waitState { it.storageSelection.size == 2 }
            capture("internal-storage-recursive-selection")
            tool("batch_import")
            execute(BatchKind.Import, 2)
            val handle = requireNotNull(vm.state.value.location).session.handle
            assertEquals("first recursive result", RpfCore.getFileData(handle, "needle-one.txt").toString(Charsets.UTF_8))
            assertEquals("second recursive result", RpfCore.getFileData(handle, "needle-two.txt").toString(Charsets.UTF_8))
            assertTrue(vm.state.value.location?.entries?.none { it.name == "unrelated.txt" } == true)

            node("archive_pane_tab").performClick()
            node("search").performTextInput("match")
            node("search").performImeAction()
            waitState { it.visibleEntries.size == 1 }
            assertEquals("archive-deep/match.txt", vm.state.value.visibleEntries.single().path)
            row("match.txt", false).performClick()
            waitState { it.selected?.path == "archive-deep/match.txt" }
            node("resource_inspector").assertExists()
            node("preview_back").performClick()
            node("search").performTextClearance()
            waitState { it.query.isEmpty() }
            node("storage_pane_tab").performClick()
            node("storage_search").performTextClearance()
            waitState { it.storageQuery.isEmpty() }
            node("storage_path_up").performClick()
            waitState { it.storage?.directory?.name == fixture.name }
            node("archive_pane_tab").performClick()
            node("save_archive").performClick()
            waitState { it.overwriteTarget != null }
            node("overwrite_execute").performClick()
            waitState { !it.workspaceDirty }
            val reopened = RpfCore.openArchive(source.path, source.name)
            try { assertEquals("first recursive result", RpfCore.getFileData(reopened, "needle-one.txt").toString(Charsets.UTF_8)) }
            finally { RpfCore.closeArchive(reopened) }
            capture("internal-storage-local-overwrite")
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) { cleanupFailures += failure }
            }
            cleanup {
                ui.runOnUiThread {
                    vm.dismissBatch()
                    vm.dismissBatchResult()
                    vm.closeWorkspace()
                    if (vm.state.value.confirmDiscard) vm.discardWorkspace()
                    vm.removeRecent(Uri.fromFile(source).toString())
                }
                ui.waitForIdle()
            }
            cleanup {
                fixture.deleteRecursively()
                preferences.edit().putString("tree", oldTree).apply()
            }
            cleanup {
                if (restoreDenied) {
                    val settings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", packageName, null))
                    ui.runOnUiThread { ui.activity.startActivity(settings) }
                    changePermissionInSettings(false)
                    backFromSettings()
                }
            }
            if (originalFailure != null) cleanupFailures.forEach { originalFailure.addSuppressed(it) }
            else cleanupFailures.firstOrNull()?.let { first ->
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }
}
