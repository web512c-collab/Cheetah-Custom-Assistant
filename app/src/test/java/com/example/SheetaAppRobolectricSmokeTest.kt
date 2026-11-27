package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.AppLinks
import com.example.core.model.AssistantSettings
import com.example.core.model.PermissionHealthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SheetaAppRobolectricSmokeTest {

    @Test
    fun `sheeta branding links and safe offline defaults are intact`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Sheeta Custom Assistant", context.getString(R.string.app_name))

        assertEquals("Mohammad Joseph", AppLinks.DEVELOPER_NAME)
        assertEquals("https://github.com/web512c-collab", AppLinks.GITHUB_PROFILE_URL)
        assertEquals("https://github.com/web512c-collab/sheeta-custom-assistant", AppLinks.GITHUB_APP_REPO_URL)
        assertEquals("https://github.com/web512c-collab/sheeta-operations-research", AppLinks.GITHUB_OR_REPO_URL)

        val defaultSettings = AssistantSettings()
        assertFalse(defaultSettings.internetSearchEnabled)
        assertEquals("شيتا", defaultSettings.assistantName)

        val fullPerms = PermissionHealthState(
            hasMicPermission = true,
            hasNotificationPermission = true,
            isDefaultAssistant = true,
            isAccessibilityEnabled = true,
            isBatteryOptimizationIgnored = true
        )
        assertEquals(100, fullPerms.readinessScore)
        assertTrue(fullPerms.isBatteryOptimizationIgnored)
    }
}
