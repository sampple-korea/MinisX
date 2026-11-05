package com.minisx.app.live

import android.content.Context
import com.minisx.app.data.model.ProviderCredential
import com.minisx.app.data.model.ProviderInstance
import com.minisx.app.data.model.ProviderType
import com.minisx.app.data.repository.ProviderRepository
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
     * The provider Live signs in with. Like Codex, a ChatGPT sign-in works as well as an API key: Codex
     * hands its ChatGPT access token to the same Realtime client it uses for keys (openai/codex,
     * codex-api realtime endpoint, auth mode `Chatgpt`). An API-key provider wins when there is one; the
     * last one used is preferred; otherwise the first ChatGPT sign-in is used, with its account id header.
     * Whether the Realtime endpoint accepts that token depends on the account, so a refusal is shown to the
     * user as it comes (see [LiveSession]).
     */
    suspend fun resolve(context: Context, repository: ProviderRepository): Resolved {
        val openAi = repository.config.value.instances.filter {
            it.isEnabled && (it.providerType == ProviderType.openAI || it.providerType == ProviderType.openAIResponses)
        }
        val keyed = openAi.filter { it.credentialType == ProviderCredential.apiKey && !repository.loadApiKey(it.id).isNullOrBlank() }
        val chatgpt = openAi.filter {
            it.credentialType == ProviderCredential.oauth && com.minisx.app.auth.OAuthManager.hasStoredCredential(context, it.id)
        }
        val candidates = keyed + chatgpt
        if (candidates.isEmpty()) return Resolved.NoProvider
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INSTANCE, null)
        val instance = candidates.firstOrNull { it.id == saved } ?: candidates.first()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_INSTANCE, instance.id).apply()
        val language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() }
        if (instance.credentialType == ProviderCredential.oauth) {
            val manager = com.minisx.app.auth.OpenAIOAuthManager(context, instance.id)
            val token = manager.validAccessToken() ?: return Resolved.NoProvider
            return Resolved.Ok(
                LiveSession.Config(
                    apiKey = token,
                    baseUrl = instance.customBaseURL,
                    voice = voice(context),
                    language = language,
                    userAgent = instance.customUserAgent,
                    extraHeaders = buildMap { manager.accountId?.let { put("ChatGPT-Account-Id", it) } },
                ),
                instance,
            )
        }
        return Resolved.Ok(
            LiveSession.Config(
                apiKey = repository.loadApiKey(instance.id)!!.trim(),
                baseUrl = instance.customBaseURL,
                voice = voice(context),
                language = language,
                userAgent = instance.customUserAgent,
            ),
            instance,
        )
    }
}
