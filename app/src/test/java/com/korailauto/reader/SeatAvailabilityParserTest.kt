package com.korailauto.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatAvailabilityParserTest {
    @Test
    fun `selects the first item with either class available for booking`() {
        val first = ScreenBounds(48, 856, 1032, 1156)
        val second = ScreenBounds(48, 1240, 1032, 1540)
        val lines = listOf(
            OcrLine("일반실", ScreenBounds(680, 900, 780, 950)),
            OcrLine("매진", ScreenBounds(900, 900, 1000, 950)),
            OcrLine("특실", ScreenBounds(680, 990, 780, 1040)),
            OcrLine("매진", ScreenBounds(900, 990, 1000, 1040)),
            OcrLine("일반실", ScreenBounds(680, 1280, 780, 1330)),
            OcrLine("예약가능", ScreenBounds(880, 1280, 1020, 1330)),
            OcrLine("특실", ScreenBounds(680, 1370, 780, 1420)),
            OcrLine("매진", ScreenBounds(900, 1370, 1000, 1420)),
        )

        val items = SeatAvailabilityParser.parse(listOf(first, second), lines)

        assertEquals(SeatStatus.SOLD_OUT, items[0].generalStatus)
        assertEquals(SeatStatus.SOLD_OUT, items[0].specialStatus)
        assertEquals(SeatStatus.AVAILABLE, items[1].generalStatus)
        val choice = SeatAvailabilityParser.firstActionable(items)
        assertEquals(2, choice?.item?.index)
        assertEquals(ReservationAction.BOOK, choice?.action)
    }

    @Test
    fun `does not select unreadable or sold out items`() {
        val bounds = ScreenBounds(48, 856, 1032, 1156)
        val soldOutLines = listOf(
            OcrLine("일반실", ScreenBounds(680, 900, 780, 950)),
            OcrLine("매진", ScreenBounds(900, 900, 1000, 950)),
            OcrLine("특실", ScreenBounds(680, 990, 780, 1040)),
            OcrLine("매진", ScreenBounds(900, 990, 1000, 1040)),
        )

        val soldOutItem = SeatAvailabilityParser.parse(listOf(bounds), soldOutLines)
        val unknownItem = SeatAvailabilityParser.parse(listOf(bounds), emptyList())

        assertNull(SeatAvailabilityParser.firstActionable(soldOutItem))
        assertNull(SeatAvailabilityParser.firstActionable(unknownItem))
    }

    @Test
    fun `selects reservation wait when a class is marked 예약 대기`() {
        val bounds = ScreenBounds(48, 856, 1032, 1156)
        val lines = listOf(
            OcrLine("일반실", ScreenBounds(680, 900, 780, 950)),
            OcrLine("예약 대기", ScreenBounds(850, 900, 1020, 950)),
            OcrLine("특실", ScreenBounds(680, 990, 780, 1040)),
            OcrLine("매진", ScreenBounds(900, 990, 1000, 1040)),
        )

        val item = SeatAvailabilityParser.parse(listOf(bounds), lines).single()
        val choice = SeatAvailabilityParser.firstActionable(listOf(item))

        assertEquals(SeatStatus.WAITLIST, item.generalStatus)
        assertEquals(ReservationAction.WAITLIST, choice?.action)
    }

    @Test
    fun `parses seat states exposed by accessibility node text`() {
        val bounds = ScreenBounds(48, 856, 1032, 1156)
        val item = SeatAvailabilityParser.parse(
            listOf(bounds),
            listOf(
                OcrLine("일반실 매진", bounds),
                OcrLine("특실 예약 대기", bounds),
            ),
        ).single()

        assertEquals(SeatStatus.SOLD_OUT, item.generalStatus)
        assertEquals(SeatStatus.WAITLIST, item.specialStatus)
        assertEquals(ReservationAction.WAITLIST, SeatAvailabilityParser.firstActionable(listOf(item))?.action)
    }

    @Test
    fun `prioritizes immediate booking over an earlier reservation wait`() {
        val first = TrainItemSnapshot(
            index = 1,
            bounds = ScreenBounds(48, 856, 1032, 1156),
            rawText = listOf("일반실 예약 대기"),
            generalStatus = SeatStatus.WAITLIST,
            specialStatus = SeatStatus.SOLD_OUT,
        )
        val second = TrainItemSnapshot(
            index = 2,
            bounds = ScreenBounds(48, 1240, 1032, 1540),
            rawText = listOf("일반실 37,300원"),
            generalStatus = SeatStatus.AVAILABLE,
            specialStatus = SeatStatus.SOLD_OUT,
        )

        val choice = SeatAvailabilityParser.firstActionable(listOf(first, second))

        assertEquals(2, choice?.item?.index)
        assertEquals(ReservationAction.BOOK, choice?.action)
    }

    @Test
    fun `treats a fare combined with a seat label as immediately bookable`() {
        val bounds = ScreenBounds(48, 856, 1032, 1156)
        val lines = listOf(
            OcrLine("일반실 37,300원", ScreenBounds(680, 900, 1020, 950)),
            OcrLine("특실 매진", ScreenBounds(680, 990, 1020, 1040)),
        )

        val item = SeatAvailabilityParser.parse(listOf(bounds), lines).single()
        val choice = SeatAvailabilityParser.firstActionable(listOf(item))

        assertEquals(SeatStatus.AVAILABLE, item.generalStatus)
        assertEquals(SeatStatus.SOLD_OUT, item.specialStatus)
        assertEquals(ReservationAction.BOOK, choice?.action)
    }

    @Test
    fun `treats a recognized seat label without a status as immediately bookable`() {
        val bounds = ScreenBounds(48, 856, 1032, 1156)
        val item = SeatAvailabilityParser.parse(
            listOf(bounds),
            listOf(OcrLine("일반실", ScreenBounds(680, 900, 780, 950))),
        ).single()

        assertEquals(SeatStatus.AVAILABLE, item.generalStatus)
        assertEquals(ReservationAction.BOOK, SeatAvailabilityParser.firstActionable(listOf(item))?.action)
    }

    @Test
    fun `finds the current 바로 예매 action label`() {
        val bounds = ScreenBounds(550, 2260, 1030, 2420)

        assertEquals(
            bounds,
            ReservationActionButtonFinder.find(
                ReservationAction.BOOK,
                listOf(OcrLine("바로 예매", bounds)),
            ),
        )
    }

    @Test
    fun `finds the current 입석 plus 좌석 booking action label`() {
        val bounds = ScreenBounds(550, 2260, 1030, 2420)

        assertEquals(
            bounds,
            ReservationActionButtonFinder.find(
                ReservationAction.BOOK,
                listOf(OcrLine("입석+좌석 예매", bounds)),
            ),
        )
    }

    @Test
    fun `finds a checkbox tap area to the left of an OCR waitlist label`() {
        val labelBounds = ScreenBounds(180, 240, 580, 300)

        val target = WaitlistCheckboxFinder.find(
            "개인정보수집및이용동의",
            listOf(OcrLine("개인정보 수집 및 이용 동의", labelBounds)),
        )

        assertEquals(104f, target?.centerX)
        assertEquals(270f, target?.centerY)
        assertEquals(164, target?.right)
        assertTrue(target?.right ?: Int.MAX_VALUE < labelBounds.left)
    }

}
