package com.gtav.rpfmanager

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gtav.rpfmanager.astc.AstcDocument
import com.gtav.rpfmanager.astc.AstcStorage
import com.gtav.rpfmanager.data.model.AppState
import com.gtav.rpfmanager.data.model.BatchKind
import com.gtav.rpfmanager.viewmodel.ArchiveViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** User-facing transfers against an isolated SAF provider and a real editable RPF. */
class DualPaneWorkspaceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var vm: ArchiveViewModel
    private lateinit var storage: AstcStorage
    private lateinit var fixture: AstcDocument
    private lateinit var source: AstcDocument
    private lateinit var incoming: AstcDocument
    private lateinit var output: AstcDocument
    private var previousStorageTree: String? = null
    private val longName = "vehicle_material_settings_with_a_long_but_valid_file_name_for_two_column_layout_验收.txt"

    private fun node(tag: String) = ui.onNodeWithTag(tag)
    private fun label(id: Int): String {
        val configuration = Configuration(context.resources.configuration)
        vm.state.value.settings.language.takeUnless { it == "system" }?.let {
            configuration.setLocale(Locale.forLanguageTag(it))
        }
        return context.createConfigurationContext(configuration).getString(id)
    }
    private fun waitFor(tag: String) {
        ui.waitUntil(20_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
    }
    private fun waitState(predicate: (AppState) -> Boolean) {
        ui.waitUntil(20_000) { vm.state.value.busy == null && predicate(vm.state.value) }
        ui.waitForIdle()
    }
    private fun tool(tag: String) = node(tag).performScrollTo().performClick()
    private fun workspaceMenu(tag: String) {
        node("workspace_menu").performClick()
        node(tag).performClick()
    }
    private fun row(name: String, fromStorage: Boolean = false): SemanticsNodeInteraction {
        val tag = (if (fromStorage) "storage_entry_" else "entry_") + name
        node(if (fromStorage) "storage_file_list" else "file_list").performScrollToNode(hasTestTag(tag))
        return node(tag)
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun rotate(orientation: Int) {
        ui.runOnUiThread { ui.activity.requestedOrientation = orientation }
        ui.waitUntil(10_000) {
            val landscape = ui.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            landscape == (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        }
        // Configuration is delivered before the system compositor finishes its rotation animation.
        android.os.SystemClock.sleep(700)
        ui.waitForIdle()
    }
    private fun read(document: AstcDocument) = requireNotNull(context.contentResolver.openInputStream(Uri.parse(document.id)))
        .bufferedReader().use { it.readText() }
    private fun archiveText(path: String) = RpfCore.getFileData(requireNotNull(vm.state.value.location).session.handle, path)
        .toString(Charsets.UTF_8)

    private fun fixtures() {
        previousStorageTree = context.getSharedPreferences("file_workspace", android.content.Context.MODE_PRIVATE)
            .getString("tree", null)
        storage = AstcStorage(context)
        val authority = instrumentation.context.packageName + ".documents"
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${instrumentation.context.packageName}/com.gtav.rpfmanager.GrantTestDocumentsActivity"
        ).use { descriptor -> ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
        val root = storage.root(DocumentsContract.buildTreeDocumentUri(authority, "root").toString())
        fixture = storage.create(root.id, "dual-pane-${UUID.randomUUID()}", true)
        incoming = storage.create(fixture.id, "incoming", true)
        output = storage.create(fixture.id, "exported", true)
        storage.writeText(storage.create(incoming.id, "replace.txt"), "replacement from internal storage")
        storage.writeText(storage.create(incoming.id, longName), "new file from internal storage")
        storage.writeText(storage.create(incoming.id, "unmatched.txt"), "must not be added by replacement")
        val bundle = storage.create(incoming.id, "bundle", true)
        storage.writeText(storage.create(bundle.id, "child.txt"), "recursive child")
        val directory = File(context.cacheDir, "dual-pane-source-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            File(directory, "replace.txt").writeText("original source")
            File(directory, "keep.txt").writeText("untouched file")
            source = storage.create(fixture.id, "source.rpf")
            storage.open(source, true).use { RpfCore.packDirectory(directory.path, it.fd) }
        } finally { directory.deleteRecursively() }
        // Only the system picker is substituted; navigation and transfers use the visible controls.
        ui.runOnUiThread { vm.storageDirectorySelected(Uri.parse(fixture.id)) }
        waitState { it.storage?.directory?.name == fixture.name }
    }

    private fun execute(kind: BatchKind, completed: Int, skipped: Int = 0) {
        waitFor("batch_confirmation")
        assertEquals(kind, vm.state.value.batchConfirmation?.kind)
        node("batch_execute").performClick()
        waitFor("batch_result")
        waitState { it.batchResult != null }
        val result = requireNotNull(vm.state.value.batchResult)
        assertEquals(result.failures.toString(), 0, result.failed)
        assertFalse(result.cancelled)
        assertEquals(completed, result.completed)
        assertEquals(skipped, result.skipped)
        capture("batch-${kind.name.lowercase()}")
        node("batch_done").performClick()
    }

    @After fun cleanUp() {
        if (::vm.isInitialized) {
            // The provider tree contains only this test's synthetic files.
            ui.runOnUiThread {
                vm.dismissBatchResult()
                vm.dismissBatch()
                vm.closeWorkspace()
                if (vm.state.value.confirmDiscard) vm.discardWorkspace()
                if (::source.isInitialized) vm.removeRecent(source.id)
            }
            ui.waitForIdle()
        }
        if (::fixture.isInitialized) {
            storage.delete(fixture)
            val preferences = context.getSharedPreferences("file_workspace", android.content.Context.MODE_PRIVATE)
            if (preferences.getString("tree", null) == fixture.id) {
                preferences.edit().putString("tree", previousStorageTree).apply()
            }
        }
    }

    @Test fun selectTransferReplaceAndExportAcrossPanes() {
        ui.runOnUiThread { vm = ViewModelProvider(ui.activity)[ArchiveViewModel::class.java] }
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        waitFor("library_screen")
        node("settings").performClick()
        node("language_zh").performClick()
        node("theme_dark").performClick()
        node("language_zh").assertIsSelected()
        node("theme_dark").assertIsSelected()
        capture("settings-compact-zh")
        node("settings_back").performClick()
        capture("library-compact-zh")

        node("convert_astc").performScrollTo().performClick()
        waitFor("astc_screen")
        val repair = ui.onNodeWithText(label(R.string.astc_repair_pages))
        val checked = repair.fetchSemanticsNode().config[SemanticsProperties.ToggleableState]
        repair.performScrollTo().performClick()
        if (checked == ToggleableState.On) repair.assertIsOff() else repair.assertIsOn()
        repair.performClick()
        ui.onNodeWithContentDescription(label(R.string.back)).performClick()
        node("open_demo").performScrollTo().performClick()
        waitFor("file_workspace")
        fixtures()
        row("source.rpf", true).performClick()
        tool("open_storage_selection")
        waitState { it.location?.session?.name == "source.rpf" }
        node("storage_pane_tab").performClick()
        if (vm.state.value.storageSelectionMode) node("clear_selection").performClick()
        row("incoming", true).performClick()
        waitState { it.storage?.directory?.name == "incoming" }

        node("archive_pane_tab").performClick()
        row("keep.txt").performTouchInput { longClick() }
        waitState { it.archiveSelection.size == 1 }
        row("keep.txt").assertIsSelected()
        node("select_all").performClick()
        waitState { it.archiveSelection.size == 2 }
        node("invert_selection").performClick()
        waitState { it.archiveSelection.isEmpty() && it.archiveSelectionMode }
        node("clear_selection").performClick()
        waitState { !it.archiveSelectionMode }
        node("begin_selection").assertExists()

        node("storage_pane_tab").performClick()
        row("bundle", true).performTouchInput { longClick() }
        node("select_all").performClick()
        waitState { it.storageSelection.size == 4 }
        node("invert_selection").performClick()
        waitState { it.storageSelection.isEmpty() && it.storageSelectionMode }
        row("bundle", true).performClick()
        row(longName, true).performClick()
        row("replace.txt", true).performClick()
        waitState { it.storageSelection.size == 3 }
        capture("dual-pane-selection-portrait-zh")
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        waitFor("file_workspace")
        assertEquals(3, vm.state.value.storageSelection.size)
        assertEquals("incoming", vm.state.value.storage?.directory?.name)
        capture("dual-pane-selection-landscape-zh")
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        tool("batch_import")
        waitFor("batch_confirmation")
        node("conflict_Skip").assertIsSelected()
        execute(BatchKind.Import, completed = 3, skipped = 1)
        assertEquals("original source", archiveText("replace.txt"))
        assertEquals("new file from internal storage", archiveText(longName))
        assertEquals("recursive child", archiveText("bundle/child.txt"))

        // One undo reverts the whole import, including its folder and child.
        node("archive_pane_tab").performClick()
        tool("undo")
        waitState { it.location?.entries?.none { file -> file.name == "bundle" || file.name == longName } == true }
        tool("redo")
        waitState { it.location?.entries?.any { file -> file.name == longName } == true }

        node("storage_pane_tab").performClick()
        row("replace.txt", true).performClick()
        row("unmatched.txt", true).performClick()
        tool("batch_replace")
        execute(BatchKind.Replace, completed = 1, skipped = 1)
        assertEquals("replacement from internal storage", archiveText("replace.txt"))
        assertTrue(vm.state.value.location?.entries?.none { it.name == "unmatched.txt" } == true)

        node("storage_path_up").performClick()
        waitState { it.storage?.directory?.name == fixture.name }
        row("exported", true).performClick()
        waitState { it.storage?.directory?.name == "exported" }
        node("archive_pane_tab").performClick()
        row("bundle").performTouchInput { longClick() }
        row(longName).performClick()
        row("replace.txt").performClick()
        tool("batch_export")
        execute(BatchKind.Export, completed = 4)
        assertEquals("replacement from internal storage", read(requireNotNull(storage.find(output.id, "replace.txt"))))
        assertEquals("new file from internal storage", read(requireNotNull(storage.find(output.id, longName))))
        val exportedBundle = requireNotNull(storage.find(output.id, "bundle"))
        assertEquals("recursive child", read(requireNotNull(storage.find(exportedBundle.id, "child.txt"))))

        node("storage_pane_tab").performClick()
        tool("storage_new_folder")
        node("storage_edit_name").performTextInput("temporary")
        node("storage_apply_edit").performClick()
        waitState { it.storage?.entries?.any { file -> file.name == "temporary" } == true }
        row("temporary", true).performTouchInput { longClick() }
        tool("rename_selection")
        node("storage_edit_name").performTextReplacement("renamed")
        node("storage_apply_edit").performClick()
        waitState { it.storage?.entries?.any { file -> file.name == "renamed" } == true }
        row("renamed", true).performTouchInput { longClick() }
        row("replace.txt", true).performClick()
        tool("batch_delete")
        execute(BatchKind.DeleteStorage, completed = 2)
        assertNull(storage.find(output.id, "renamed"))
        assertNull(storage.find(output.id, "replace.txt"))

        // Explicit replacement can map different names across the two panes.
        node("archive_pane_tab").performClick()
        row("keep.txt").performTouchInput { longClick() }
        node("storage_pane_tab").performClick()
        row(longName, true).performClick()
        tool("targeted_replace")
        execute(BatchKind.TargetedReplace, completed = 1)
        assertEquals("new file from internal storage", archiveText("keep.txt"))
        node("archive_pane_tab").performClick()
        row(longName).performTouchInput { longClick() }
        row("replace.txt").performClick()
        tool("batch_delete")
        execute(BatchKind.DeleteArchive, completed = 2)
        assertTrue(vm.state.value.location?.entries?.none { it.name == longName || it.name == "replace.txt" } == true)
        tool("undo")
        waitState { it.location?.entries?.any { file -> file.name == longName } == true }
        assertEquals("replacement from internal storage", archiveText("replace.txt"))

        // Raw source stays intact despite workspace edits and transfer operations.
        storage.open(source).use { descriptor ->
            val handle = RpfCore.openArchiveFd(descriptor.fd, source.name)
            try {
                assertEquals("original source", RpfCore.getFileData(handle, "replace.txt").toString(Charsets.UTF_8))
                assertEquals("untouched file", RpfCore.getFileData(handle, "keep.txt").toString(Charsets.UTF_8))
            }
            finally { RpfCore.closeArchive(handle) }
        }
        row("bundle").performClick()
        waitState { it.location?.path == "bundle" }
        node("search").performTextInput("child")
        node("search").performImeAction()
        node("save_archive").performClick()
        waitFor("overwrite_confirmation")
        capture("overwrite-confirmation-zh")
        node("overwrite_execute").performClick()
        waitState { !it.workspaceDirty }
        assertEquals("bundle", vm.state.value.location?.path)
        assertEquals("child", vm.state.value.query)
        capture("saved-directory-context-zh")
        // Confirmed overwrite persists the edited bytes and nested import into the source RPF.
        storage.open(requireNotNull(storage.find(fixture.id, "source.rpf"))).use { descriptor ->
            val handle = RpfCore.openArchiveFd(descriptor.fd, "source.rpf")
            try {
                assertEquals("replacement from internal storage", RpfCore.getFileData(handle, "replace.txt").toString(Charsets.UTF_8))
                assertEquals("new file from internal storage", RpfCore.getFileData(handle, "keep.txt").toString(Charsets.UTF_8))
                assertEquals("recursive child", RpfCore.getFileData(handle, "bundle/child.txt").toString(Charsets.UTF_8))
            } finally { RpfCore.closeArchive(handle) }
        }
        node("archive_path_up").performClick()
        waitState { it.location?.path == "" && it.query == "" }
        workspaceMenu("settings")
        node("language_en").performClick()
        node("theme_light").performClick()
        capture("settings-compact-light-en")
        node("settings_back").performClick()
        row(longName)
        capture("dual-pane-long-names-light-en")
        node("toggle_dual_pane").performClick()
        node("archive_pane").assertExists()
        node("storage_pane").assertDoesNotExist()
        node("storage_pane_tab").performClick()
        node("archive_pane").assertDoesNotExist()
        node("storage_pane").assertExists()
        assertEquals("exported", vm.state.value.storage?.directory?.name)
        node("toggle_dual_pane").performClick()
        node("archive_pane_tab").performClick()
        row("keep.txt").performClick()
        waitFor("resource_inspector")
        node("file_workspace").assertDoesNotExist()
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        node("preview_back").assertExists()
        node("file_list").assertDoesNotExist()
        capture("preview-fullscreen-landscape-en")
        node("preview_back").performClick()
        waitFor("file_workspace")
        assertEquals("exported", vm.state.value.storage?.directory?.name)
        capture("dual-pane-landscape-light-en")
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        workspaceMenu("close_workspace")
        waitFor("library_screen")
        capture("library-recent-light-en")
    }
}
