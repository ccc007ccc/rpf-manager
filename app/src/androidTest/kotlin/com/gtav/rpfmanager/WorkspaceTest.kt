package com.gtav.rpfmanager

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises real Rust resources and Vulkan on a device, including recreation. */
class WorkspaceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun node(tag: String) = ui.onNodeWithTag(tag)

    private fun workspaceMenu(tag: String) {
        node("workspace_menu").performClick()
        node(tag).performClick()
    }

    private fun tool(tag: String) = node(tag).performScrollTo().performClick()

    private fun waitFor(tag: String) {
        ui.waitUntil(20_000) {
            runCatching { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        ui.waitForIdle()
    }

    private fun openEntry(name: String) {
        val tag = "entry_$name"
        waitFor("file_list")
        node("file_list").performScrollToNode(hasTestTag(tag))
        node(tag).performClick()
    }

    private fun capture(name: String): Bitmap {
        ui.waitForIdle()
        Thread.sleep(500) // SurfaceView presents outside the Compose frame clock.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        val dir =
            File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review").apply {
                mkdirs()
            }
        File(dir, "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return image
    }

    private fun assertMeshVisible() {
        waitFor("mesh_canvas")
        val image = capture("current-mesh")
        val bounds = node("mesh_canvas").fetchSemanticsNode().boundsInWindow
        var foreground = 0
        val x0 = (bounds.left + bounds.width * .25f).toInt().coerceAtLeast(0)
        val x1 = (bounds.right - bounds.width * .25f).toInt().coerceAtMost(image.width - 1)
        val y0 = (bounds.top + bounds.height * .3f).toInt().coerceAtLeast(0)
        val y1 = (bounds.bottom - bounds.height * .2f).toInt().coerceAtMost(image.height - 1)
        for (y in y0 until y1 step 4) for (x in x0 until x1 step 4) {
            val pixel = image.getPixel(x, y)
            if (((pixel shr 16) and 255) > 85 || ((pixel shr 8) and 255) > 85) foreground++
        }
        assertTrue("Vulkan must draw geometry in the center of the viewport", foreground > 30)
    }

    private fun assertOrbitChangesPixels(before: Bitmap, after: Bitmap) {
        val bounds = node("mesh_canvas").fetchSemanticsNode().boundsInWindow
        var changed = 0
        for (y in
            (bounds.top + bounds.height * .25f).toInt() until
                (bounds.bottom - bounds.height * .2f).toInt() step
                4) {
            for (x in
                (bounds.left + bounds.width * .15f).toInt() until
                    (bounds.right - bounds.width * .15f).toInt() step
                    4) {
                if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
            }
        }
        assertTrue(
            "Orbit must update the rendered scene instead of freezing at frame one",
            changed > 100,
        )
    }

    @Test
    fun browsePreviewAndChangeLanguage() {
        ui.runOnUiThread {
            ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        ui.waitUntil(10_000) {
            runCatching {
                ui.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            }.getOrDefault(false)
        }
        waitFor("library_screen")
        node("settings").performClick()
        node("language_zh").performClick()
        node("settings_back").performClick()
        capture("library-zh")
        node("open_demo").performScrollTo().performClick()
        waitFor("entry_notes")
        capture("browser-zh")
        node("search").performTextInput("rove")
        waitFor("entry_rover.ydr")
        node("entry_rover.ydr").assertExists()
        node("entry_materials.ytd").assertDoesNotExist()
        node("search").performTextReplacement("mat")
        waitFor("entry_materials.ytd")
        node("entry_materials.ytd").assertExists()
        node("search").performTextClearance()
        ui.activityRule.scenario.onActivity { activity ->
            val imm =
                activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.window.decorView.clearFocus()
        }
        openEntry("rover.ydr")
        assertMeshVisible()
        val initial = capture("model-zh")
        node("model_texture_0").performClick()
        ui.waitUntil(20_000) {
            ui.onAllNodesWithTag("model_texture_0").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(700)
        assertOrbitChangesPixels(initial, capture("model-textured-zh"))
        node("geometry_color").performClick()
        node("mesh_canvas").performTouchInput { swipe(centerLeft, centerRight, 400) }
        assertOrbitChangesPixels(initial, capture("model-orbit-zh"))
        node("details_tab").performClick()
        node("preview_tab").performClick()
        assertMeshVisible()
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertMeshVisible()
        node("wireframe").performClick()
        capture("wireframe-zh")
        node("shaded").performClick()
        node("fit_view").performClick()
        ui.runOnUiThread {
            ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        waitFor("preview_back")
        node("file_list").assertDoesNotExist()
        assertMeshVisible()
        capture("landscape-zh")
        ui.runOnUiThread {
            ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        waitFor("preview_back")
        node("preview_back").performClick()
        openEntry("materials.ytd")
        waitFor("texture_canvas")
        capture("textures-zh")
        node("texture_1").performClick()
        waitFor("texture_canvas")
        node("alpha").performClick()
        capture("alpha-zh")
        node("preview_back").performClick()
        openEntry("blockout.ymap")
        assertMeshVisible()
        capture("map-zh")
        node("preview_back").performClick()
        openEntry("empty.ymap")
        waitFor("empty_map")
        ui.onNodeWithText("没有可显示的实体").assertExists()
        node("mesh_canvas").assertDoesNotExist()
        capture("empty-map-zh")
        node("details_tab").performClick()
        node("preview_tab").performClick()
        node("empty_map").assertExists()
        node("preview_back").performClick()
        openEntry("chassis.ybn")
        assertMeshVisible()
        capture("collision-zh")
        node("preview_back").performClick()
        openEntry("nested.rpf")
        waitFor("entry_readme.txt")
        node("workspace_back").performClick()
        waitFor("entry_notes")
        tool("new_folder")
        node("edit_name").performTextInput("验收目录")
        node("apply_edit").performClick()
        waitFor("entry_验收目录")
        tool("undo")
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("entry_验收目录").fetchSemanticsNodes().isEmpty() }
        tool("redo")
        waitFor("entry_验收目录")
        workspaceMenu("close_workspace")
        node("keep_editing").performClick()
        node("entry_验收目录").assertExists()
        capture("editor-zh")
        tool("undo")
        tool("archive_actions")
        node("scan_archive").performClick()
        waitFor("coverage_report")
        capture("coverage-zh")
        node("coverage_back").performClick()
        node("search").performTextInput("tones")
        openEntry("tones.awc")
        waitFor("audio_play")
        ui.waitUntil(10_000) { runCatching { node("audio_play").assertIsEnabled(); true }.getOrDefault(false) }
        node("audio_play").performClick()
        node("audio_play").assertTextContains("暂停")
        capture("audio-pcm-zh")
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        node("audio_play").assertTextContains("播放")
        node("audio_track_1").performClick()
        waitFor("audio_play")
        ui.waitUntil(10_000) { runCatching { node("audio_play").assertIsEnabled(); true }.getOrDefault(false) }
        capture("audio-adpcm-zh")
        node("preview_back").performClick()
        node("search").performTextClearance()
        workspaceMenu("settings")
        node("language_en").performClick()
        node("settings_back").performClick()
        node("file_list").performScrollToNode(hasTestTag("entry_rover.ydr"))
        node("entry_rover.ydr").assertExists()
        workspaceMenu("close_workspace")
        capture("library-en")
        node("settings").performClick()
        node("theme_light").performClick()
        node("settings_back").performClick()
        capture("library-light")
        node("settings").performClick()
        node("language_zh").performClick()
        node("theme_dark").performClick()
        node("settings_back").performClick()
    }
}
