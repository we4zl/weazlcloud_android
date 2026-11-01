package io.prendie.weazlcloud

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun demo() {
        java.io.File(ui.activity.filesDir, "accounts/demo").deleteRecursively()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Explore Demo").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Explore Demo").performClick()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Documents").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun privacyPolicyIsAvailableBeforeSignIn() {
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Explore Demo").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Privacy policy").performClick()
        ui.onNode(hasText("WeazlCloud & Subweazl Privacy Policy", substring = true)).assertExists()
        ui.onNode(hasText("bobprendie@gmail.com", substring = true)).assertExists()
        ui.onNodeWithText("Done").performClick()
        ui.onNodeWithText("Explore Demo").assertExists()
    }

    @Test
    fun independentModeContextAndPhotoViewer() {
        demo()
        ui.onNodeWithText("Documents").performClick()
        ui.onNodeWithText("Photos", useUnmergedTree = true).performClick()
        ui.waitUntil(10000) {
            ui.onAllNodesWithContentDescription("Coastal walk 1.jpg")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        ui.onNodeWithContentDescription("Coastal walk 1.jpg").performClick()
        ui.onNodeWithContentDescription("Photo information").assertExists()
        ui.onNodeWithTag("photo-viewer-pager").performTouchInput { swipeLeft() }
        ui.waitUntil(5000) {
            ui.onAllNodes(hasText("2 /", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithContentDescription("Close viewer").performClick()
        ui.onNodeWithText("Files", useUnmergedTree = true).performClick()
        ui.onNodeWithContentDescription("Parent folder").assertExists()
        ui.onNodeWithText("Documents").assertExists()
    }

    @Test
    fun createFolderAndSearchThenOpenUtilities() {
        demo()
        ui.onNodeWithContentDescription("File options").performClick()
        ui.onNodeWithText("New folder").performClick()
        ui.onNodeWithText("Name").performTextInput("Emulator test folder")
        ui.onNodeWithText("Save").performClick()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Emulator test folder").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Search files (* and ? supported)").performTextInput("Emulator*")
        ui.waitForIdle()
        ui.onNodeWithText("Emulator test folder").assertExists()
        ui.onNodeWithText("No transfers waiting").performClick()
        ui.onNodeWithText("0 of 0 on server").assertExists()
        ui.onNodeWithText("Done").performClick()
        ui.onNodeWithText("Backup").performClick()
        ui.waitUntil(5000) {
            ui.onAllNodesWithText("Pixel photo library").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Done").performClick()
        ui.onNodeWithText("Offline").performClick()
        ui.onNodeWithText("Pin a file, folder, or album from its menu.").assertExists()
    }
}
