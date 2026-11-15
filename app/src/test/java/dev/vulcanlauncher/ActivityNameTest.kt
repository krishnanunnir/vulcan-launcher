package dev.vulcanlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityNameTest {
    @Test fun activityName() {
        assertEquals("Face enroll confirmation", activityName("com.google.android.settings.biometrics.face.FaceEnrollConfirmation"))
        assertEquals("Notification history", activityName(".notification.history.NotificationHistoryActivity"))
        assertEquals("VPN settings", activityName("x.VPNSettingsActivity"))
        assertEquals("Activity", activityName("x.Activity"))
        assertEquals("Wifi2 picker", activityName("x.Outer\$Wifi2Picker"))
    }
}
