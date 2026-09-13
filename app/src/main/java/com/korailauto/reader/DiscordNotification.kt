package com.korailauto.reader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ReservationCompletionType {
    BOOKING,
    WAITLIST,
}

object ReservationCompletionDetector {
    fun matches(type: ReservationCompletionType, textValues: Collection<String>): Boolean {
        val text = textValues.joinToString(" ")
            .filterNot(Char::isWhitespace)

        return when (type) {
            ReservationCompletionType.BOOKING ->
                text.contains("승차권") && text.contains("예약") && text.contains("완료")

            ReservationCompletionType.WAITLIST ->
                text.contains("예약대기") && text.contains("신청") && text.contains("완료")
        }
    }
}

object DiscordWebhookUrlValidator {
    fun isValid(value: String): Boolean = try {
        val uri = URI(value.trim())
        val host = uri.host?.lowercase(Locale.ROOT)
        val pathSegments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            host in setOf("discord.com", "discordapp.com") &&
            pathSegments.size >= 4 &&
            pathSegments[0] == "api" &&
            pathSegments[1] == "webhooks" &&
            pathSegments[2].isNotBlank() &&
            pathSegments[3].isNotBlank()
    } catch (_: Exception) {
        false
    }
}

object DiscordWebhookSettings {
    private const val PREFS_NAME = "discord_webhook_settings"
    private const val KEY_CIPHERTEXT = "webhook_ciphertext"
    private const val KEY_IV = "webhook_iv"
    private const val KEY_ALIAS = "korail_discord_webhook_v1"
    private const val GCM_TAG_LENGTH_BITS = 128

    fun save(context: Context, webhookUrl: String): Boolean {
        val value = webhookUrl.trim()
        if (!DiscordWebhookUrlValidator.isValid(value)) return false

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        prefs(context).edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        return true
    }

    fun load(context: Context): String? {
        val ciphertext = prefs(context).getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = prefs(context).getString(KEY_IV, null) ?: run {
            clear(context)
            return null
        }

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            clear(context)
            null
        }
    }

    fun maskedWebhook(context: Context): String? {
        val uri = load(context)?.let(::URI) ?: return null
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        return if (segments.size >= 4) {
            "https://${uri.host}/api/webhooks/${segments[2]}/••••"
        } else {
            "저장됨"
        }
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}

object DiscordMessageFormatter {
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA)

    fun completion(type: ReservationCompletionType, completedAtMillis: Long): String {
        val completedAt = Instant.ofEpochMilli(completedAtMillis)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
        val result = when (type) {
            ReservationCompletionType.BOOKING -> "예약이 완료되었습니다"
            ReservationCompletionType.WAITLIST -> "예약 대기 신청이 완료되었습니다"
        }
        return "코레일 자동화: $result ($completedAt)"
    }

    fun actionStopped(
        type: ReservationCompletionType,
        itemLabel: String,
        stoppedAtMillis: Long,
    ): String {
        val stoppedAt = Instant.ofEpochMilli(stoppedAtMillis)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
        val action = when (type) {
            ReservationCompletionType.BOOKING -> "예매 요청을 보냈습니다"
            ReservationCompletionType.WAITLIST -> "예약 대기 신청 요청을 보냈습니다"
        }
        return "코레일 자동화: $itemLabel 항목에 $action. 최종 완료는 확인되지 않았으며 자동화를 중지했습니다. ($stoppedAt)"
    }

    fun test(): String = "Korail Screen Reader: Discord 알림 연결 테스트"
}

fun interface DiscordTransport {
    fun post(webhookUrl: String, content: String): Boolean
}

class RetryingDiscordSender(
    private val transport: DiscordTransport,
    private val retryDelaysMillis: LongArray = longArrayOf(1_000L, 2_000L, 4_000L),
    private val wait: (Long) -> Unit = Thread::sleep,
) {
    fun send(webhookUrl: String, content: String): Boolean {
        for (attempt in 0..retryDelaysMillis.size) {
            try {
                if (transport.post(webhookUrl, content)) return true
            } catch (_: Exception) {
                // A transient network failure is retried below.
            }

            if (attempt == retryDelaysMillis.size) break
            try {
                wait(retryDelaysMillis[attempt])
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }
}

private object HttpUrlConnectionDiscordTransport : DiscordTransport {
    override fun post(webhookUrl: String, content: String): Boolean {
        val connection = (URL(webhookUrl).openConnection() as? HttpURLConnection) ?: return false
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
            connection.readTimeout = NETWORK_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { output ->
                output.write("{\"content\":\"${escapeJson(content)}\"}")
            }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }

    private fun escapeJson(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private const val NETWORK_TIMEOUT_MILLIS = 10_000
}

object DiscordNotificationClient {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "discord-notification").apply { isDaemon = true }
    }
    private val sender = RetryingDiscordSender(HttpUrlConnectionDiscordTransport)

    fun sendCompletion(
        context: Context,
        type: ReservationCompletionType,
        completedAtMillis: Long,
        callback: (Boolean) -> Unit,
    ) {
        send(context, DiscordMessageFormatter.completion(type, completedAtMillis), callback)
    }

    fun sendActionStopped(
        context: Context,
        type: ReservationCompletionType,
        itemLabel: String,
        stoppedAtMillis: Long,
        callback: (Boolean) -> Unit,
    ) {
        send(context, DiscordMessageFormatter.actionStopped(type, itemLabel, stoppedAtMillis), callback)
    }

    fun sendTest(context: Context, callback: (Boolean) -> Unit) {
        send(context, DiscordMessageFormatter.test(), callback)
    }

    private fun send(context: Context, content: String, callback: (Boolean) -> Unit) {
        val webhookUrl = DiscordWebhookSettings.load(context.applicationContext)
        if (webhookUrl == null) {
            callback(false)
            return
        }

        executor.execute {
            val delivered = sender.send(webhookUrl, content)
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(delivered) }
        }
    }
}
