package com.openminis.app.live

import android.content.Context
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import java.util.Locale

/** Picks the provider and voice a Live call uses. */
object LiveSetup {
    private const val PREFS = "live_voice"
    private const val KEY_VOICE = "voice"
    private const val KEY_INSTANCE = "instance_id"

    fun voice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE, null)
            ?.takeIf { it in RealtimeProtocol.VOICES } ?: RealtimeProtocol.DEFAULT_VOICE

    fun setVoice(context: Context, voice: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_VOICE, voice).apply()
    }

    sealed class Resolved {
        data class Ok(val config: LiveSession.Config, val instance: ProviderInstance) : Resolved()
        /** No provider can do Realtime voice; the UI explains what is needed. */
        data object NoProvider : Resolved()
    }

    /**
     * Realtime voice needs an OpenAI-protocol provider with an API key (a ChatGPT /
     * Codex sign-in does not reach the Realtime endpoint). The last one used wins;
     * otherwise the first that qualifies.
     */
    fun resolve(context: Context, repository: ProviderRepository): Resolved {
        val candidates = repository.config.value.instances.filter {
            it.isEnabled && it.credentialType == ProviderCredential.apiKey &&
                (it.providerType == ProviderType.openAI || it.providerType == ProviderType.openAIResponses) &&
                !repository.loadApiKey(it.id).isNullOrBlank()
        }
        if (candidates.isEmpty()) return Resolved.NoProvider
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INSTANCE, null)
        val instance = candidates.firstOrNull { it.id == saved } ?: candidates.first()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_INSTANCE, instance.id).apply()
        return Resolved.Ok(
            LiveSession.Config(
                apiKey = repository.loadApiKey(instance.id)!!.trim(),
                baseUrl = instance.customBaseURL,
                voice = voice(context),
                language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() },
                userAgent = instance.customUserAgent,
            ),
            instance,
        )
    }
}
