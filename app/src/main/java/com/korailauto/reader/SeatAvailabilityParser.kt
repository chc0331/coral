package com.korailauto.reader

import kotlin.math.abs
import kotlin.math.max

data class OcrLine(
    val text: String,
    val bounds: ScreenBounds,
)

object SeatAvailabilityParser {
    fun parse(itemBounds: List<ScreenBounds>, ocrLines: List<OcrLine>): List<TrainItemSnapshot> =
        itemBounds.mapIndexed { index, bounds ->
            val lines = ocrLines.filter { line ->
                bounds.contains(line.bounds.centerX, line.bounds.centerY)
            }
            TrainItemSnapshot(
                index = index + 1,
                bounds = bounds,
                rawText = lines.map { it.text },
                generalStatus = findSeatStatus("일반실", bounds, lines),
                specialStatus = findSeatStatus("특실", bounds, lines),
            )
        }

    fun firstActionable(items: List<TrainItemSnapshot>): TrainReservationChoice? {
        val bookableItem = items.firstOrNull { item ->
            item.generalStatus == SeatStatus.AVAILABLE || item.specialStatus == SeatStatus.AVAILABLE
        }
        if (bookableItem != null) return TrainReservationChoice(bookableItem, ReservationAction.BOOK)

        val waitlistItem = items.firstOrNull { item ->
            item.generalStatus == SeatStatus.WAITLIST || item.specialStatus == SeatStatus.WAITLIST
        }
        return waitlistItem?.let { TrainReservationChoice(it, ReservationAction.WAITLIST) }
    }

    private fun findSeatStatus(
        label: String,
        itemBounds: ScreenBounds,
        lines: List<OcrLine>,
    ): SeatStatus {
        val labelLine = lines.firstOrNull { line -> compact(line.text).contains(label) } ?: return SeatStatus.UNKNOWN
        statusFrom(labelLine.text)?.let { return it }

        // Korean OCR often combines a seat label and its fare into one line,
        // for example "일반실 37,300원". A non-empty suffix is the seat
        // status itself, and is immediately bookable unless handled above.
        if (compact(labelLine.text).replaceFirst(label, "").isNotBlank()) {
            return SeatStatus.AVAILABLE
        }

        val rowTolerance = max(80f, itemBounds.height * 0.3f)
        val statusLine = lines
            .asSequence()
            .filter { line ->
                val normalized = compact(line.text)
                line.bounds.centerX > labelLine.bounds.centerX + 20f &&
                    abs(line.bounds.centerY - labelLine.bounds.centerY) <= rowTolerance &&
                    !normalized.contains("일반실") &&
                    !normalized.contains("특실")
            }
            .minWithOrNull(
                compareBy<OcrLine> { abs(it.bounds.centerY - labelLine.bounds.centerY) }
                    .thenBy { it.bounds.centerX },
            )
            ?: return SeatStatus.AVAILABLE

        return statusFrom(statusLine.text) ?: SeatStatus.AVAILABLE
    }

    private fun statusFrom(value: String): SeatStatus? {
        val normalized = compact(value)
        return when {
            normalized.contains("매진") -> SeatStatus.SOLD_OUT
            normalized.contains("예약대기") -> SeatStatus.WAITLIST
            else -> null
        }
    }

    private fun compact(value: String): String = value.filterNot(Char::isWhitespace)
}

object ReservationActionButtonFinder {
    fun find(action: ReservationAction, lines: List<OcrLine>): ScreenBounds? =
        lines.firstOrNull { line -> matches(action, line.text) }?.bounds

    private fun matches(action: ReservationAction, value: String): Boolean {
        val normalized = value.filterNot(Char::isWhitespace)
        return when (action) {
            ReservationAction.BOOK -> normalized in setOf(
                "예매",
                "예매하기",
                "바로예매",
                "바로예매하기",
                "입석+좌석예매",
            )
            ReservationAction.WAITLIST -> normalized in setOf("예약대기", "예약대기신청", "예약대기신청하기")
        }
    }
}

object WaitlistCheckboxFinder {
    private const val LABEL_GAP_PX = 16
    private const val MIN_TAP_WIDTH_PX = 96
    private const val MIN_VERTICAL_RADIUS_PX = 48

    /**
     * Korail's Compose checkbox often has no readable accessibility label. The visual checkbox is
     * immediately left of its OCR label, so return a compact tap area centered on that checkbox.
     */
    fun find(label: String, lines: List<OcrLine>): ScreenBounds? {
        val labelLine = lines.firstOrNull { line ->
            line.text.filterNot(Char::isWhitespace).contains(label.filterNot(Char::isWhitespace))
        } ?: return null

        val right = labelLine.bounds.left - LABEL_GAP_PX
        val width = max(MIN_TAP_WIDTH_PX, labelLine.bounds.height * 2)
        val left = (right - width).coerceAtLeast(0)
        if (right <= left) return null

        val verticalRadius = max(MIN_VERTICAL_RADIUS_PX, labelLine.bounds.height)
        return ScreenBounds(
            left = left,
            top = (labelLine.bounds.centerY - verticalRadius).toInt().coerceAtLeast(0),
            right = right,
            bottom = (labelLine.bounds.centerY + verticalRadius).toInt(),
        )
    }
}
