package com.korailauto.reader

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x in left.toFloat()..right.toFloat() && y in top.toFloat()..bottom.toFloat()
}

enum class SeatStatus {
    AVAILABLE,
    WAITLIST,
    SOLD_OUT,
    UNKNOWN,
}

enum class ReservationAction {
    BOOK,
    WAITLIST,
}

enum class AutomationState {
    IDLE,
    RUNNING,
    PAUSED,
    SELECTED,
    ERROR,
}

data class AccessibleNodeSnapshot(
    val index: Int,
    val parentIndex: Int?,
    val depth: Int,
    val className: String,
    val text: String,
    val contentDescription: String,
    val stateDescription: String,
    val hintText: String,
    val paneTitle: String,
    val viewId: String,
    val bounds: ScreenBounds,
    val clickable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val scrollable: Boolean,
)

data class TrainItemSnapshot(
    val index: Int,
    val bounds: ScreenBounds,
    val rawText: List<String>,
    val generalStatus: SeatStatus,
    val specialStatus: SeatStatus,
)

data class TrainReservationChoice(
    val item: TrainItemSnapshot,
    val action: ReservationAction,
)

data class ScreenSnapshot(
    val capturedAtMillis: Long,
    val packageName: String,
    val eventText: List<String>,
    val nodes: List<AccessibleNodeSnapshot>,
    val refreshTarget: ScreenBounds?,
    val trainItems: List<TrainItemSnapshot>,
    val automationState: AutomationState,
    val message: String,
) {
    companion object {
        fun empty(message: String = "접근성 서비스를 활성화하세요.") = ScreenSnapshot(
            capturedAtMillis = System.currentTimeMillis(),
            packageName = "",
            eventText = emptyList(),
            nodes = emptyList(),
            refreshTarget = null,
            trainItems = emptyList(),
            automationState = AutomationState.IDLE,
            message = message,
        )
    }
}
