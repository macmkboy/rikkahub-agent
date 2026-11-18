package me.rerere.rikkahub.data.ai.watchdog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed class WatchdogDecision {
    data object Active : WatchdogDecision()
    data class Stuck(val state: StreamHeartbeatState) : WatchdogDecision()
    data object SwitchModelRequested : WatchdogDecision()
    data object TimeoutExpired : WatchdogDecision()
}

/**
 * Controller and active timer managing streaming heartbeats, stuck detection,
 * and interactive user stuck resolutions.
 */
class StreamWatchdogController(
    val idleTimeoutMs: Long = 75_000L,
    val stuckThresholdMs: Long = 20_000L,
    val canSwitchModel: Boolean = false,
    val candidateModelName: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val startTimeMs = AtomicLong(clock())
    private val lastChunkTimeMs = AtomicLong(startTimeMs.get())
    private val extensionMs = AtomicLong(0L)
    private val stuckGraceUntilMs = AtomicLong(0L)
    private val switchRequested = AtomicBoolean(false)

    private val _heartbeatState = MutableStateFlow<StreamHeartbeatState?>(null)
    val heartbeatState: StateFlow<StreamHeartbeatState?> = _heartbeatState.asStateFlow()

    private var tickerJob: Job? = null

    /**
     * Resets timers and flags for a fresh retry attempt so stale idle time
     * or a prior switch request does not immediately abort the new attempt.
     */
    fun resetForAttempt() {
        val now = clock()
        startTimeMs.set(now)
        lastChunkTimeMs.set(now)
        extensionMs.set(0L)
        stuckGraceUntilMs.set(0L)
        switchRequested.set(false)
        tick(now)
    }

    /**
     * Start the 1-second heartbeat ticker coroutine.
     */
    fun start(scope: CoroutineScope) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                tick()
                delay(1000L)
            }
        }
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        _heartbeatState.value = null
    }

    fun onChunkReceived() {
        lastChunkTimeMs.set(clock())
        stuckGraceUntilMs.set(0L)
        tick()
    }

    fun extendDeadline(seconds: Long = 120L) {
        val now = clock()
        val addMs = seconds * 1000L
        val currentIdleMs = now - lastChunkTimeMs.get()
        val currentEffective = idleTimeoutMs + extensionMs.get()
        // Guarantee at least `seconds` from `now` even if we were already close to or past timeout.
        val neededExtension = (currentIdleMs + addMs) - idleTimeoutMs
        val targetExtension = maxOf(currentEffective - idleTimeoutMs + addMs, neededExtension)
        extensionMs.set(targetExtension.coerceAtLeast(addMs))
        stuckGraceUntilMs.set(now + addMs)
        tick(now)
    }

    fun requestModelSwitch() {
        switchRequested.set(true)
        tick()
    }

    fun evaluate(nowMs: Long = clock()): WatchdogDecision {
        if (switchRequested.get()) {
            return WatchdogDecision.SwitchModelRequested
        }

        val lastChunk = lastChunkTimeMs.get()
        val idleMs = nowMs - lastChunk
        val effectiveTimeoutMs = idleTimeoutMs + extensionMs.get()

        if (idleTimeoutMs > 0 && idleMs >= effectiveTimeoutMs) {
            return WatchdogDecision.TimeoutExpired
        }

        val state = buildState(nowMs, lastChunk, idleMs)
        return if (state.isStuck) WatchdogDecision.Stuck(state) else WatchdogDecision.Active
    }

    fun tick(nowMs: Long = clock()): StreamHeartbeatState {
        val lastChunk = lastChunkTimeMs.get()
        val idleMs = nowMs - lastChunk
        val state = buildState(nowMs, lastChunk, idleMs)
        _heartbeatState.value = state
        return state
    }

    private fun buildState(nowMs: Long, lastChunk: Long, idleMs: Long): StreamHeartbeatState {
        val inGrace = nowMs < stuckGraceUntilMs.get()
        val isStuck = !inGrace && idleMs >= stuckThresholdMs
        val elapsedSec = (nowMs - startTimeMs.get()).coerceAtLeast(0L) / 1000L
        val idleSec = idleMs.coerceAtLeast(0L) / 1000L
        val statusText = when {
            isStuck -> "Streaming stalled (${elapsedSec}s elapsed, idle ${idleSec}s)"
            inGrace -> {
                val graceRem = ((stuckGraceUntilMs.get() - nowMs) / 1000L).coerceAtLeast(1L)
                "Waiting +${graceRem}s (${elapsedSec}s elapsed)"
            }
            else -> "Streaming (${elapsedSec}s elapsed)"
        }
        return StreamHeartbeatState(
            elapsedSeconds = elapsedSec,
            idleSeconds = idleSec,
            isStuck = isStuck,
            canSwitchModel = canSwitchModel,
            candidateModelName = candidateModelName,
            statusText = statusText,
        )
    }
}
