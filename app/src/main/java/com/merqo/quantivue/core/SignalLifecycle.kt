package com.merqo.quantivue.core

/** Applies temporal hysteresis so a valid direction cannot flicker on every sampled frame. */
class SignalLifecycleManager(private val confirmationsRequired: Int = 2) {
    init { require(confirmationsRequired >= 1) }
    private var candidate: Direction = Direction.NO_TRADE
    private var candidateCount = 0
    private var active: Direction = Direction.NO_TRADE

    fun reset() {
        candidate = Direction.NO_TRADE
        candidateCount = 0
        active = Direction.NO_TRADE
    }

    fun apply(result: PredictionResult): PredictionResult {
        if (result.direction == Direction.NO_TRADE) {
            val lifecycle = if (active != Direction.NO_TRADE) SignalLifecycle.INVALIDATED else SignalLifecycle.VALIDATING
            active = Direction.NO_TRADE
            candidate = Direction.NO_TRADE
            candidateCount = 0
            return result.copy(lifecycle = lifecycle)
        }
        if (candidate == result.direction) candidateCount++ else {
            candidate = result.direction
            candidateCount = 1
        }
        if (candidateCount < confirmationsRequired) {
            return result.copy(
                direction = Direction.NO_TRADE,
                lifecycle = SignalLifecycle.VALIDATING,
                gateReason = "Directional setup awaiting temporal stability"
            )
        }
        active = result.direction
        return result.copy(lifecycle = SignalLifecycle.ACTIVE)
    }
}
