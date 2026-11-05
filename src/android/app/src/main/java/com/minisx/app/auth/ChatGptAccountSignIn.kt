package com.minisx.app.auth

import android.content.Context
import com.minisx.app.R
import com.minisx.app.data.repository.ProviderRepository
import com.minisx.app.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * [T-codex-account-pool] Signs another ChatGPT account in under an existing OpenAI (OAuth)
 * provider. Runs in an app-wide scope, not the settings screen's: the sign-in goes through the
 * browser, and the screen can be recreated or left while the user is there. A sign-in tied to the
 * screen's scope was cancelled with it - and a cancelled sign-in is thrown away - so the account
 * silently never appeared.
 */
object ChatGptAccountSignIn {

    data class State(
        val adding: Boolean = false,
        /** Why the last attempt did not add an account, in the user's language. */
        val error: String? = null,
        /** The account that was just added (its e-mail, or "" when the token carries none). */
        val added: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /**
     * [activity] is used to open the browser tab (it must be an Activity so the tab sits in the
     * app's task); everything after that uses the application context.
     */
    fun start(activity: Context, repository: ProviderRepository, instanceId: String) {
        if (_state.value.adding) return
        val app = activity.applicationContext
        _state.value = State(adding = true)
        job = scope.launch {
            val newId = UUID.randomUUID().toString()
            OAuthForegroundGate.begin()
            try {
                AppLogger.info(TAG, "adding an account to provider $instanceId (storage=$newId)")
                OpenAIOAuthManager.login(activity, newId, repository, forceAccountPrompt = true)
                val signedIn = OpenAIOAuthManager(app, newId)
                val current = repository.instance(instanceId)
                    ?: throw IllegalStateException("provider $instanceId no longer exists")
                val existing = (listOf(current.id) + current.poolAccountIds).associateWith {
                    OpenAIOAuthManager(app, it).let { m -> OpenAIOAuthManager.AccountKey(m.accountId, m.email) }
                }
                val candidate = OpenAIOAuthManager.AccountKey(signedIn.accountId, signedIn.email)
                if (OpenAIOAuthManager.duplicateAccount(candidate, existing) != null) {
                    AppLogger.warning(TAG, "that account is already added; discarding the new sign-in")
                    discard(app, repository, newId)
                    _state.value = State(error = app.getString(R.string.provider_detail_accounts_duplicate))
                    return@launch
                }
                repository.updateInstance(current.copy(poolAccountIds = current.poolAccountIds + newId))
                AppLogger.info(TAG, "account added (accounts now: ${current.poolAccountIds.size + 2})")
                _state.value = State(added = signedIn.email.orEmpty())
            } catch (e: CancellationException) {
                discard(app, repository, newId)
                _state.value = State()
                throw e
            } catch (e: Exception) {
                AppLogger.warning(TAG, "adding an account failed: ${e.javaClass.simpleName}: ${e.message}")
                discard(app, repository, newId)
                _state.value = State(
                    error = if (e is OAuthNetworkUnreachableException) {
                        app.getString(R.string.add_provider_oauth_network_unreachable)
                    } else {
                        app.getString(R.string.oauth_sign_in_failed, e.message ?: e.javaClass.simpleName)
                    },
                )
            } finally {
                OAuthForegroundGate.end()
            }
        }
    }

    /** Stop waiting for the browser. */
    fun cancel() {
        job?.cancel()
    }

    /** Forget the last result (shown once). */
    fun dismissMessage() {
        if (!_state.value.adding) _state.value = State()
    }

    private fun discard(app: Context, repository: ProviderRepository, id: String) {
        runCatching { OpenAIOAuthManager(app, id).logout() }
        repository.deleteApiKey(id)
    }

    private const val TAG = "ChatGptAccounts"
}
