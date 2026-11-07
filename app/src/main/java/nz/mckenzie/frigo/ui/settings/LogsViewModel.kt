package nz.mckenzie.frigo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.LogLevel
import nz.mckenzie.frigo.data.LogLine
import nz.mckenzie.frigo.data.LogService
import nz.mckenzie.frigo.data.NotAnAdminException
import nz.mckenzie.frigo.data.asLogLine
import nz.mckenzie.frigo.data.newestLines
import nz.mckenzie.frigo.ui.friendlyMessage
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext

data class LogsUiState(
    val service: LogService = LogService.Frigate,
    val lines: List<LogLine> = emptyList(),
    /** True until the first read has answered, one way or the other. */
    val loading: Boolean = true,
    val error: String? = null,
    /** Whether new lines pull the list down to the newest. */
    val following: Boolean = true,
    /** The chips to narrow by. Empty is All. */
    val levels: Set<LogLevel> = emptySet(),
    val query: String = "",
    /** The feed has ended or failed and is waiting to be opened again. */
    val feedStopped: Boolean = false,
    /** Some of the oldest lines have gone to keep the page to its limit. */
    val dropped: Boolean = false,
)

/**
 * What frigate, go2rtc and nginx have been saying.
 *
 * The page reads the whole log once, then follows the server's own feed, which
 * pushes each line as it is written. The feed is closed when the page is left,
 * when the service changes, and when it ends, so nothing is held open behind
 * your back.
 *
 * A rolling log can be replaced under an open feed: the feed keeps reading the
 * old file and goes quiet. A feed with nothing on it for a minute is reopened so
 * the new file is picked up.
 */
class LogsViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(LogsUiState())
    val state = _state.asStateFlow()

    private var job: Job? = null

    /** The feed the page is reading, kept so leaving the page can close it. */
    private var feed: Response? = null

    /** When the last line arrived, which is what the quiet minute is measured from. */
    @Volatile private var lastLineAt = 0L

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            if (!readWhole()) return@launch
            while (isActive) {
                when (follow()) {
                    FeedEnd.Refused -> break
                    FeedEnd.Quiet -> Unit
                    FeedEnd.Stopped -> {
                        // A response closed on the way out also ends the read. The
                        // page has gone, or another service is being read, so its
                        // note must not be left on the state the new feed reads.
                        if (!isActive) break
                        _state.update { it.copy(feedStopped = true) }
                        delay(RetryMillis)
                        if (!isActive) break
                        _state.update { it.copy(feedStopped = false) }
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        feed?.close()
        feed = null
    }

    /** Another service chosen: its log starts from the top rather than joining the last one. */
    fun choose(service: LogService) {
        if (service == _state.value.service) return
        stop()
        _state.update {
            it.copy(
                service = service,
                lines = emptyList(),
                loading = true,
                error = null,
                dropped = false,
                feedStopped = false,
            )
        }
        start()
    }

    /** The Try again button, after a read that failed. */
    fun retry() {
        stop()
        _state.update { it.copy(loading = true, error = null, feedStopped = false) }
        start()
    }

    fun setFollowing(on: Boolean) {
        _state.update { it.copy(following = on) }
    }

    /** All, which is no chip ticked. */
    fun showAll() {
        _state.update { it.copy(levels = emptySet()) }
    }

    /** One level on or off the list. Unticking the last one leaves All. */
    fun toggleLevel(level: LogLevel) {
        _state.update { current ->
            current.copy(
                levels = if (level in current.levels) {
                    current.levels - level
                } else {
                    current.levels + level
                },
            )
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun clearFilters() {
        _state.update { it.copy(levels = emptySet(), query = "") }
    }

    /** The whole log, shown before the feed is opened so the page fills at once. */
    private suspend fun readWhole(): Boolean {
        _state.update { it.copy(loading = true, error = null, feedStopped = false) }
        val answer = try {
            repository.logs(_state.value.service.id)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            _state.update {
                it.copy(loading = false, error = failure.friendlyMessage(repository.context))
            }
            return false
        }

        val lines = answer.lines.map { it.asLogLine() }
        val kept = lines.newestLines()
        _state.update {
            it.copy(
                lines = kept,
                loading = false,
                error = null,
                dropped = kept.size < lines.size,
            )
        }
        return true
    }

    /**
     * Reads the feed until it ends, fails, or is closed. The response is closed
     * on the way out, which is what unblocks a read that is waiting for a line.
     */
    private suspend fun follow(): FeedEnd {
        val response = try {
            repository.openLogFeed(_state.value.service.id)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: NotAnAdminException) {
            _state.update { it.copy(error = failure.friendlyMessage(repository.context)) }
            return FeedEnd.Refused
        } catch (failure: Throwable) {
            return FeedEnd.Stopped
        }

        feed = response
        lastLineAt = System.currentTimeMillis()
        var quiet = false

        // No read timeout on this client, so a feed that has gone silent would wait
        // for ever. The watcher closes it after a minute, which ends the read and
        // lets the loop open a fresh feed on whatever file is there now.
        val watcher = viewModelScope.launch {
            while (isActive) {
                delay(QuietMillis)
                if (System.currentTimeMillis() - lastLineAt >= QuietMillis) {
                    quiet = true
                    response.close()
                    return@launch
                }
            }
        }

        try {
            // The feed is a blocking read, so it runs off the main thread. Closing
            // the response from the watcher unblocks it just the same.
            withContext(Dispatchers.IO) {
                val source = response.body?.source() ?: return@withContext
                while (coroutineContext.isActive) {
                    val line = source.readUtf8Line() ?: break
                    lastLineAt = System.currentTimeMillis()
                    if (line.isNotEmpty()) append(line.asLogLine())
                }
            }
        } catch (closed: IOException) {
            // Closing the response is how a read is stopped: by the watcher when the
            // feed has gone quiet, or by the page being left.
        } finally {
            watcher.cancel()
            if (feed === response) feed = null
            response.close()
        }

        return if (quiet) FeedEnd.Quiet else FeedEnd.Stopped
    }

    private fun append(line: LogLine) {
        _state.update { current ->
            val next = current.lines + line
            val kept = next.newestLines()
            current.copy(
                lines = kept,
                dropped = current.dropped || kept.size < next.size,
            )
        }
    }

    /** Why the feed ended, which decides whether it is worth opening again. */
    private enum class FeedEnd {
        /** The response ended or failed. Say so, and try again shortly. */
        Stopped,

        /** The feed was closed for being quiet. Open a fresh one at once. */
        Quiet,

        /** The account isn't an admin, so there is nothing to follow. */
        Refused,
    }

    companion object {
        /** How long after a feed stops before it is opened again. */
        private const val RetryMillis = 5_000L

        /** How long a feed may say nothing before it is reopened. */
        private const val QuietMillis = 60_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { LogsViewModel(container.repository) }
        }
    }
}
