package com.korailauto.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordNotificationTest {
    @Test
    fun `accepts only Discord incoming webhook URLs`() {
        assertTrue(
            DiscordWebhookUrlValidator.isValid(
                "https://discord.com/api/webhooks/1234567890/webhook-token",
            ),
        )
        assertFalse(DiscordWebhookUrlValidator.isValid("http://discord.com/api/webhooks/1/token"))
        assertFalse(DiscordWebhookUrlValidator.isValid("https://example.com/api/webhooks/1/token"))
        assertFalse(DiscordWebhookUrlValidator.isValid("https://discord.com/api/webhooks/1"))
    }

    @Test
    fun `recognizes confirmed booking and waitlist completion separately`() {
        assertTrue(
            ReservationCompletionDetector.matches(
                ReservationCompletionType.BOOKING,
                listOf("승차권 예약이 완료되었습니다."),
            ),
        )
        assertTrue(
            ReservationCompletionDetector.matches(
                ReservationCompletionType.WAITLIST,
                listOf("예약 대기 신청이 완료되었습니다."),
            ),
        )
        assertFalse(
            ReservationCompletionDetector.matches(
                ReservationCompletionType.BOOKING,
                listOf("예약 대기 신청이 완료되었습니다."),
            ),
        )
        assertFalse(
            ReservationCompletionDetector.matches(
                ReservationCompletionType.WAITLIST,
                listOf("예약 대기 신청 화면"),
            ),
        )
    }

    @Test
    fun `formats completion and stopped-after-action messages accurately`() {
        val booking = DiscordMessageFormatter.completion(ReservationCompletionType.BOOKING, 0L)
        val waitlist = DiscordMessageFormatter.completion(ReservationCompletionType.WAITLIST, 0L)
        val stoppedBooking = DiscordMessageFormatter.actionStopped(ReservationCompletionType.BOOKING, "1번째", 0L)
        val stoppedWaitlist = DiscordMessageFormatter.actionStopped(ReservationCompletionType.WAITLIST, "2번째", 0L)

        assertTrue(booking.contains("예약이 완료되었습니다"))
        assertTrue(waitlist.contains("예약 대기 신청이 완료되었습니다"))
        assertTrue(stoppedBooking.contains("1번째 항목에 예매 요청을 보냈습니다"))
        assertTrue(stoppedWaitlist.contains("2번째 항목에 예약 대기 신청 요청을 보냈습니다"))
        assertTrue(stoppedBooking.contains("최종 완료는 확인되지 않았"))
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").containsMatchIn(booking))
    }

    @Test
    fun `retries three times after the first failed attempt`() {
        var attempts = 0
        val sender = RetryingDiscordSender(
            transport = DiscordTransport { _, _ -> ++attempts >= 4 },
            wait = {},
        )

        assertTrue(sender.send("https://discord.com/api/webhooks/1/token", "test"))
        assertEquals(4, attempts)
    }

    @Test
    fun `stops after the configured retry attempts`() {
        var attempts = 0
        val sender = RetryingDiscordSender(
            transport = DiscordTransport { _, _ -> ++attempts; false },
            wait = {},
        )

        assertFalse(sender.send("https://discord.com/api/webhooks/1/token", "test"))
        assertEquals(4, attempts)
    }
}
