package com.korailauto.reader

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var serviceStatusView: TextView
    private lateinit var automationButton: Button
    private lateinit var contentView: TextView

    private val snapshotListener: (ScreenSnapshot) -> Unit = { snapshot ->
        runOnUiThread { render(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContent())
    }

    override fun onStart() {
        super.onStart()
        ScreenSnapshotStore.addListener(snapshotListener)
        render(ScreenSnapshotStore.latest())
    }

    override fun onResume() {
        super.onResume()
        render(ScreenSnapshotStore.latest())
    }

    override fun onStop() {
        ScreenSnapshotStore.removeListener(snapshotListener)
        super.onStop()
    }

    private fun createContent(): ScrollView {
        val padding = dp(16)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        serviceStatusView = TextView(this).apply {
            textSize = 16f
        }
        container.addView(serviceStatusView)

        val settingsButton = Button(this).apply {
            text = "접근성 서비스 설정 열기"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        container.addView(settingsButton, matchWidthParams())

        automationButton = Button(this).apply {
            setOnClickListener {
                val enabled = !AutomationSettings.isEnabled(this@MainActivity)
                AutomationSettings.setEnabled(this@MainActivity, enabled)
                KorailAccessibilityService.notifyAutomationPreferenceChanged()
                render(ScreenSnapshotStore.latest())
            }
        }
        container.addView(automationButton, matchWidthParams())

        contentView = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        container.addView(contentView, matchWidthParams())

        return ScrollView(this).apply { addView(container) }
    }

    private fun render(snapshot: ScreenSnapshot) {
        val serviceEnabled = isServiceEnabled()
        serviceStatusView.text = if (serviceEnabled) {
            "접근성 서비스: 활성화됨 · 대상: ${KorailAccessibilityService.TARGET_PACKAGE}"
        } else {
            "접근성 서비스: 비활성화됨 · 설정에서 직접 켜야 합니다"
        }

        automationButton.text = if (AutomationSettings.isEnabled(this)) {
            "자동화 중지"
        } else {
            "자동화 시작"
        }

        contentView.text = formatSnapshot(snapshot)
    }

    private fun formatSnapshot(snapshot: ScreenSnapshot): String = buildString {
        appendLine("상태: ${automationStateText(snapshot.automationState)}")
        appendLine("메시지: ${snapshot.message}")
        appendLine("수집 시각: ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(snapshot.capturedAtMillis))}")
        appendLine("패키지: ${snapshot.packageName.ifBlank { "대기 중" }}")
        appendLine("접근성 노드: ${snapshot.nodes.size}개")

        if (snapshot.eventText.isNotEmpty()) {
            appendLine("이벤트 텍스트: ${snapshot.eventText.joinToString(" · ")}")
        }
        snapshot.refreshTarget?.let {
            appendLine("새로고침 영역: [${it.left},${it.top}][${it.right},${it.bottom}]")
        }

        appendLine()
        appendLine("리스트 아이템")
        if (snapshot.trainItems.isEmpty()) {
            appendLine("- 현재 화면에서 찾지 못했습니다.")
        } else {
            snapshot.trainItems.forEach { item ->
                appendLine(
                    "${item.index}. 일반실=${seatStatusText(item.generalStatus)}, " +
                        "특실=${seatStatusText(item.specialStatus)} " +
                        "[${item.bounds.left},${item.bounds.top}][${item.bounds.right},${item.bounds.bottom}]",
                )
                if (item.rawText.isNotEmpty()) {
                    appendLine("   OCR: ${item.rawText.joinToString(" | ")}")
                }
            }
        }

        val namedNodes = snapshot.nodes.filter { node ->
            node.text.isNotBlank() || node.contentDescription.isNotBlank() || node.stateDescription.isNotBlank()
        }
        appendLine()
        appendLine("접근성 텍스트")
        if (namedNodes.isEmpty()) {
            appendLine("- 노드 텍스트가 없습니다. OCR 결과를 사용합니다.")
        } else {
            namedNodes.take(40).forEach { node ->
                val label = listOf(node.text, node.contentDescription, node.stateDescription)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                appendLine("- $label (${node.className})")
            }
        }
    }

    private fun isServiceEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        val component = ComponentName(this, KorailAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
            serviceInfo.packageName == component.packageName && serviceInfo.name == component.className
        }
    }

    private fun automationStateText(state: AutomationState): String = when (state) {
        AutomationState.IDLE -> "대기"
        AutomationState.RUNNING -> "실행 중"
        AutomationState.PAUSED -> "일시 중지"
        AutomationState.SELECTED -> "좌석 가능 항목 선택 완료"
        AutomationState.ERROR -> "오류"
    }

    private fun seatStatusText(status: SeatStatus): String = when (status) {
        SeatStatus.AVAILABLE -> "가능"
        SeatStatus.WAITLIST -> "예약 대기"
        SeatStatus.SOLD_OUT -> "매진"
        SeatStatus.UNKNOWN -> "판독 불가"
    }

    private fun matchWidthParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
