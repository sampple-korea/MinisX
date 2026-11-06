package com.minisx.app.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/**
 * The service Android binds to when MinisX is chosen as the default digital assistant (Settings >
 * Apps > Default apps > Digital assistant app). Power-button long press, the home-corner swipe and a
 * headset button then reach [AssistantSession].
 */
class AssistantInteractionService : VoiceInteractionService() {
    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}
