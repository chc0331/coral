package com.korailauto.reader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.random.Random

private enum class WaitlistFlowStep {
    DISMISS_NOTICE,
    OPEN_APPLICATION,
    CHECK_SPECIAL_SEAT,
    CHECK_PRIVACY,
    ACCEPT_PRIVACY,
    SUBMIT,
}

class KorailAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    private var pendingSnapshot: Runnable? = null
    private var pendingRefresh: Runnable? = null
    private var pendingActionButton: Runnable? = null
    private var pendingWaitlistFlow: Runnable? = null
    private var screenshotInFlight = false
    private var lastOcrAtMillis = 0L
    private var lastEventText: List<String> = emptyList()
    private var automationState = AutomationState.IDLE
    private var pendingReservationAction: ReservationAction? = null
    private var pendingItemIndex: Int? = null
    private var actionButtonAttempts = 0
    private var waitlistFlowStep: WaitlistFlowStep? = null
    private var waitlistFlowAttempts = 0
    private var waitlistItemLabel: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        AutomationSettings.enableWhenServiceConnected(this)
        serviceInfo = serviceInfo.apply {
            packageNames = arrayOf(TARGET_PACKAGE)
            notificationTimeout = EVENT_DEBOUNCE_MILLIS
        }
        publishStatus(
            state = AutomationState.IDLE,
            message = "코레일 화면을 기다리는 중입니다.",
        )
        onAutomationPreferenceChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        lastEventText = event.text
            .map { it.toString().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_EVENT_TEXT)

        scheduleSnapshot(evaluateAutomation = false)
    }

    override fun onInterrupt() {
        pauseAutomation("접근성 서비스가 중단되었습니다.")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        textRecognizer.close()
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    fun onAutomationPreferenceChanged() {
        mainHandler.post {
            if (AutomationSettings.isEnabled(this)) {
                automationState = AutomationState.RUNNING
                publishStatus(automationState, "자동 갱신을 준비합니다.")
                scheduleNextRefresh()
            } else {
                pauseAutomation("자동화가 꺼져 있습니다.")
            }
        }
    }

    private fun scheduleSnapshot(evaluateAutomation: Boolean) {
        pendingSnapshot?.let(mainHandler::removeCallbacks)
        val runnable = Runnable { captureCurrentScreen(evaluateAutomation) }
        pendingSnapshot = runnable
        mainHandler.postDelayed(runnable, EVENT_DEBOUNCE_MILLIS)
    }

    private fun captureCurrentScreen(evaluateAutomation: Boolean) {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != TARGET_PACKAGE) {
            root?.recycle()
            if (evaluateAutomation) pauseAutomation("코레일 화면이 포그라운드에 없습니다.")
            return
        }

        val tree = try {
            AccessibilityTreeReader.read(root)
        } finally {
            root.recycle()
        }

        val snapshot = ScreenSnapshot(
            capturedAtMillis = System.currentTimeMillis(),
            packageName = tree.packageName,
            eventText = lastEventText,
            nodes = tree.nodes,
            refreshTarget = tree.refreshTarget,
            trainItems = tree.itemBounds.mapIndexed { index, bounds ->
                val nodeReportsWaitlist = bounds in tree.waitlistItemBounds
                TrainItemSnapshot(
                    index + 1,
                    bounds,
                    emptyList(),
                    if (nodeReportsWaitlist) SeatStatus.WAITLIST else SeatStatus.UNKNOWN,
                    SeatStatus.UNKNOWN,
                )
            },
            automationState = automationState,
            message = "접근성 노드 ${tree.nodes.size}개를 읽었습니다.",
        )
        ScreenSnapshotStore.publish(snapshot)

        if (tree.itemBounds.isEmpty()) {
            if (evaluateAutomation) scheduleNextRefresh()
            return
        }

        requestScreenshot(snapshot, evaluateAutomation)
    }

    private fun requestScreenshot(snapshot: ScreenSnapshot, evaluateAutomation: Boolean) {
        if (screenshotInFlight) {
            if (evaluateAutomation) {
                mainHandler.postDelayed({ captureCurrentScreen(evaluateAutomation = true) }, RETRY_DELAY_MILLIS)
            }
            return
        }

        val now = SystemClock.uptimeMillis()
        if (!evaluateAutomation && now - lastOcrAtMillis < OCR_MIN_INTERVAL_MILLIS) return

        screenshotInFlight = true
        lastOcrAtMillis = now
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    if (bitmap == null) {
                        screenshotInFlight = false
                        onScreenshotFailure(snapshot, evaluateAutomation, "스크린샷 비트맵을 만들지 못했습니다.")
                        return
                    }
                    analyzeScreenshot(bitmap, snapshot, evaluateAutomation)
                }

                override fun onFailure(errorCode: Int) {
                    screenshotInFlight = false
                    onScreenshotFailure(snapshot, evaluateAutomation, "스크린샷 캡처 실패 코드: $errorCode")
                }
            },
        )
    }

    private fun analyzeScreenshot(bitmap: Bitmap, snapshot: ScreenSnapshot, evaluateAutomation: Boolean) {
        textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                bitmap.recycle()
                screenshotInFlight = false

                val lines = result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        val bounds = line.boundingBox ?: return@mapNotNull null
                        OcrLine(
                            text = line.text,
                            bounds = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        )
                    }
                }
                val parsedItems = SeatAvailabilityParser.parse(snapshot.trainItems.map { it.bounds }, lines)
                val items = parsedItems.mapIndexed { index, item ->
                    val nodeReportedWaitlist = snapshot.trainItems.getOrNull(index)?.generalStatus == SeatStatus.WAITLIST
                    if (nodeReportedWaitlist) item.copy(generalStatus = SeatStatus.WAITLIST) else item
                }
                val updated = snapshot.copy(
                    trainItems = items,
                    automationState = automationState,
                    message = "OCR로 리스트 아이템 ${items.size}개를 분석했습니다.",
                )
                ScreenSnapshotStore.publish(updated)

                if (evaluateAutomation) handleAutomationResult(updated)
            }
            .addOnFailureListener { error ->
                bitmap.recycle()
                screenshotInFlight = false
                onScreenshotFailure(snapshot, evaluateAutomation, "OCR 실패: ${error.message.orEmpty()}")
            }
    }

    private fun onScreenshotFailure(snapshot: ScreenSnapshot, evaluateAutomation: Boolean, message: String) {
        ScreenSnapshotStore.publish(
            snapshot.copy(
                automationState = if (evaluateAutomation) AutomationState.ERROR else automationState,
                message = message,
            ),
        )
        if (evaluateAutomation) scheduleNextRefresh()
    }

    private fun scheduleNextRefresh() {
        pendingRefresh?.let(mainHandler::removeCallbacks)
        if (!AutomationSettings.isEnabled(this)) {
            pauseAutomation("자동화가 꺼져 있습니다.")
            return
        }

        val delay = Random.nextLong(MIN_REFRESH_DELAY_MILLIS, MAX_REFRESH_DELAY_MILLIS + 1)
        automationState = AutomationState.RUNNING
        publishStatus(automationState, "${delay / 1000}초 후 화면을 갱신합니다.")

        val runnable = Runnable { refreshAndAnalyze() }
        pendingRefresh = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun refreshAndAnalyze() {
        if (!AutomationSettings.isEnabled(this)) {
            pauseAutomation("자동화가 꺼져 있습니다.")
            return
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != TARGET_PACKAGE) {
            root?.recycle()
            pauseAutomation("코레일 화면이 포그라운드에 없습니다.")
            return
        }

        val tree = try {
            AccessibilityTreeReader.read(root)
        } finally {
            root.recycle()
        }
        val refreshTarget = tree.refreshTarget
        if (refreshTarget == null || !clickAtBounds(refreshTarget)) {
            publishStatus(AutomationState.ERROR, "새로고침 버튼을 누르지 못했습니다.")
            scheduleNextRefresh()
            return
        }

        publishStatus(AutomationState.RUNNING, "화면을 갱신했습니다. 결과를 확인합니다.")
        mainHandler.postDelayed(
            { captureCurrentScreen(evaluateAutomation = true) },
            RESULT_SETTLE_MILLIS,
        )
    }

    private fun handleAutomationResult(snapshot: ScreenSnapshot) {
        if (!AutomationSettings.isEnabled(this)) return

        val choice = SeatAvailabilityParser.firstActionable(snapshot.trainItems)
        if (choice == null) {
            ScreenSnapshotStore.publish(
                snapshot.copy(
                    automationState = AutomationState.RUNNING,
                    message = "예매 또는 예약 대기 가능한 항목이 없거나 상태를 판독하지 못했습니다.",
                ),
            )
            scheduleNextRefresh()
            return
        }

        if (clickAtBounds(choice.item.bounds)) {
            pendingReservationAction = choice.action
            pendingItemIndex = choice.item.index
            actionButtonAttempts = 0
            pendingRefresh?.let(mainHandler::removeCallbacks)
            pendingRefresh = null
            ScreenSnapshotStore.publish(
                snapshot.copy(
                    automationState = AutomationState.RUNNING,
                    message = "${choice.item.index}번째 항목을 선택했습니다. ${actionLabel(choice.action)} 버튼을 찾습니다.",
                ),
            )
            scheduleActionButtonClick()
        } else {
            ScreenSnapshotStore.publish(
                snapshot.copy(
                    automationState = AutomationState.ERROR,
                    message = "${choice.item.index}번째 선택 가능 항목을 누르지 못했습니다.",
                ),
            )
            scheduleNextRefresh()
        }
    }

    private fun scheduleActionButtonClick() {
        pendingActionButton?.let(mainHandler::removeCallbacks)
        val runnable = Runnable { clickPendingActionButton() }
        pendingActionButton = runnable
        mainHandler.postDelayed(runnable, ACTION_BUTTON_SETTLE_MILLIS)
    }

    private fun clickPendingActionButton() {
        val action = pendingReservationAction ?: return
        if (!AutomationSettings.isEnabled(this)) {
            clearPendingAction()
            pauseAutomation("자동화가 꺼져 있습니다.")
            return
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != TARGET_PACKAGE) {
            root?.recycle()
            failPendingAction("코레일 화면을 확인할 수 없어 ${actionLabel(action)} 버튼을 누르지 않았습니다.")
            return
        }

        val tree = try {
            AccessibilityTreeReader.read(root)
        } finally {
            root.recycle()
        }

        if (action == ReservationAction.BOOK && AccessibilityTreeReader.containsLabel(tree.nodes, STOP_NOTICE_LABEL)) {
            val confirmTarget = AccessibilityTreeReader.findEnabledClickTarget(tree.nodes, setOf("확인"))
            if (confirmTarget != null && clickAtBounds(confirmTarget)) {
                actionButtonAttempts = 0
                publishStatus(AutomationState.RUNNING, "서대구 정차 안내를 확인했습니다. 바로 예매를 진행합니다.")
                scheduleActionButtonClick()
            } else {
                retryPendingAction("서대구 정차 안내의 확인 버튼을 찾지 못했습니다.")
            }
            return
        }

        val actionTarget = AccessibilityTreeReader.findReservationActionTarget(tree.nodes, action)
        if (actionTarget != null && clickAtBounds(actionTarget)) {
            completePendingAction(action)
            return
        }

        requestActionButtonScreenshot(action)
    }

    private fun requestActionButtonScreenshot(action: ReservationAction) {
        if (screenshotInFlight) {
            retryPendingAction("${actionLabel(action)} 버튼 화면을 읽는 중입니다.")
            return
        }

        screenshotInFlight = true
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    if (bitmap == null) {
                        screenshotInFlight = false
                        retryPendingAction("${actionLabel(action)} 버튼 화면을 읽지 못했습니다.")
                        return
                    }

                    textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { result ->
                            bitmap.recycle()
                            screenshotInFlight = false
                            if (pendingReservationAction != action || !AutomationSettings.isEnabled(this@KorailAccessibilityService)) {
                                return@addOnSuccessListener
                            }

                            val lines = result.textBlocks.flatMap { block ->
                                block.lines.mapNotNull { line ->
                                    val bounds = line.boundingBox ?: return@mapNotNull null
                                    OcrLine(
                                        text = line.text,
                                        bounds = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                                    )
                                }
                            }
                            val actionTarget = ReservationActionButtonFinder.find(action, lines)
                            if (actionTarget != null && clickAtBounds(actionTarget)) {
                                completePendingAction(action)
                            } else {
                                retryPendingAction("${actionLabel(action)} 버튼을 찾는 중입니다.")
                            }
                        }
                        .addOnFailureListener {
                            bitmap.recycle()
                            screenshotInFlight = false
                            retryPendingAction("${actionLabel(action)} 버튼 화면 OCR에 실패했습니다.")
                        }
                }

                override fun onFailure(errorCode: Int) {
                    screenshotInFlight = false
                    retryPendingAction("${actionLabel(action)} 버튼 캡처에 실패했습니다.")
                }
            },
        )
    }

    private fun completePendingAction(action: ReservationAction) {
        val itemLabel = pendingItemIndex?.let { "${it}번째" } ?: "선택한"
        clearPendingAction()
        if (action == ReservationAction.WAITLIST) {
            startWaitlistFlow(itemLabel)
            return
        }

        AutomationSettings.setEnabled(this, false)
        automationState = AutomationState.SELECTED
        ScreenSnapshotStore.publish(
            ScreenSnapshotStore.latest().copy(
                capturedAtMillis = System.currentTimeMillis(),
                automationState = AutomationState.SELECTED,
                message = "$itemLabel 항목의 ${actionLabel(action)} 버튼을 눌러 자동화를 중지했습니다.",
            ),
        )
    }

    private fun startWaitlistFlow(itemLabel: String) {
        waitlistFlowStep = WaitlistFlowStep.DISMISS_NOTICE
        waitlistFlowAttempts = 0
        waitlistItemLabel = itemLabel
        publishStatus(AutomationState.RUNNING, "$itemLabel 항목의 예약 대기 신청 절차를 시작합니다.")
        scheduleWaitlistFlow()
    }

    private fun scheduleWaitlistFlow() {
        pendingWaitlistFlow?.let(mainHandler::removeCallbacks)
        val runnable = Runnable { advanceWaitlistFlow() }
        pendingWaitlistFlow = runnable
        mainHandler.postDelayed(runnable, WAITLIST_STEP_SETTLE_MILLIS)
    }

    private fun advanceWaitlistFlow() {
        val step = waitlistFlowStep ?: return
        if (!AutomationSettings.isEnabled(this)) {
            clearWaitlistFlow()
            pauseAutomation("자동화가 꺼져 있습니다.")
            return
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != TARGET_PACKAGE) {
            root?.recycle()
            failWaitlistFlow("코레일 화면을 확인할 수 없어 예약 대기 신청을 중지했습니다.")
            return
        }

        val tree = try {
            AccessibilityTreeReader.read(root)
        } finally {
            root.recycle()
        }

        when (step) {
            WaitlistFlowStep.DISMISS_NOTICE -> {
                if (!AccessibilityTreeReader.containsLabel(tree.nodes, STOP_NOTICE_LABEL)) {
                    moveWaitlistFlow(WaitlistFlowStep.OPEN_APPLICATION, "예약 대기 신청 화면을 기다립니다.")
                    return
                }
                val confirmTarget = AccessibilityTreeReader.findEnabledClickTarget(tree.nodes, setOf("확인"))
                if (confirmTarget != null && clickAtBounds(confirmTarget)) {
                    moveWaitlistFlow(WaitlistFlowStep.OPEN_APPLICATION, "이용 안내를 확인했습니다.")
                } else {
                    retryWaitlistFlow("이용 안내의 확인 버튼을 찾지 못했습니다.")
                }
            }

            WaitlistFlowStep.OPEN_APPLICATION -> {
                val applyTarget = AccessibilityTreeReader.findEnabledClickTarget(
                    tree.nodes,
                    setOf("예약대기신청", "예약대기신청하기"),
                )
                if (applyTarget != null && clickAtBounds(applyTarget)) {
                    moveWaitlistFlow(WaitlistFlowStep.CHECK_SPECIAL_SEAT, "예약 대기 신청 화면을 열었습니다.")
                } else {
                    retryWaitlistFlow("예약 대기 신청 버튼을 찾는 중입니다.")
                }
            }

            WaitlistFlowStep.CHECK_SPECIAL_SEAT -> {
                val checkBox = AccessibilityTreeReader.findCheckBoxTarget(tree.nodes, "특실좌석포함")
                when {
                    checkBox == null -> retryWaitlistFlow("특실 좌석 포함 체크박스를 찾는 중입니다.")
                    checkBox.checked -> moveWaitlistFlow(WaitlistFlowStep.CHECK_PRIVACY, "특실 좌석 포함을 선택했습니다.")
                    clickAtBounds(checkBox.bounds) -> scheduleWaitlistFlow()
                    else -> retryWaitlistFlow("특실 좌석 포함 체크박스를 누르지 못했습니다.")
                }
            }

            WaitlistFlowStep.CHECK_PRIVACY -> {
                val checkBox = AccessibilityTreeReader.findCheckBoxTarget(tree.nodes, "개인정보수집및이용동의")
                when {
                    checkBox == null -> retryWaitlistFlow("개인정보 수집 및 이용 동의 체크박스를 찾는 중입니다.")
                    checkBox.checked -> moveWaitlistFlow(WaitlistFlowStep.ACCEPT_PRIVACY, "개인정보 수집 및 이용 동의를 선택했습니다.")
                    clickAtBounds(checkBox.bounds) -> scheduleWaitlistFlow()
                    else -> retryWaitlistFlow("개인정보 수집 및 이용 동의 체크박스를 누르지 못했습니다.")
                }
            }

            WaitlistFlowStep.ACCEPT_PRIVACY -> {
                val agreeTarget = AccessibilityTreeReader.findEnabledClickTarget(tree.nodes, setOf("동의"))
                when {
                    agreeTarget != null && clickAtBounds(agreeTarget) ->
                        moveWaitlistFlow(WaitlistFlowStep.SUBMIT, "개인정보 수집 및 이용에 동의했습니다.")

                    AccessibilityTreeReader.findEnabledClickTarget(tree.nodes, setOf("신청", "신청하기")) != null ->
                        moveWaitlistFlow(WaitlistFlowStep.SUBMIT, "신청 버튼 활성화를 확인했습니다.")

                    else -> retryWaitlistFlow("개인정보 수집 및 이용 동의 창을 기다립니다.")
                }
            }

            WaitlistFlowStep.SUBMIT -> {
                val submitTarget = AccessibilityTreeReader.findEnabledClickTarget(tree.nodes, setOf("신청", "신청하기"))
                if (submitTarget != null && clickAtBounds(submitTarget)) {
                    completeWaitlistFlow()
                } else {
                    retryWaitlistFlow("활성화된 신청 버튼을 찾는 중입니다.")
                }
            }
        }
    }

    private fun moveWaitlistFlow(nextStep: WaitlistFlowStep, message: String) {
        waitlistFlowStep = nextStep
        waitlistFlowAttempts = 0
        publishStatus(AutomationState.RUNNING, message)
        scheduleWaitlistFlow()
    }

    private fun retryWaitlistFlow(message: String) {
        waitlistFlowAttempts += 1
        if (waitlistFlowAttempts >= MAX_WAITLIST_STEP_ATTEMPTS) {
            failWaitlistFlow("$message 안전을 위해 예약 대기 신청을 중지했습니다.")
            return
        }

        publishStatus(
            AutomationState.RUNNING,
            "$message (${waitlistFlowAttempts}/$MAX_WAITLIST_STEP_ATTEMPTS)",
        )
        scheduleWaitlistFlow()
    }

    private fun completeWaitlistFlow() {
        val itemLabel = waitlistItemLabel ?: "선택한"
        clearWaitlistFlow()
        AutomationSettings.setEnabled(this, false)
        automationState = AutomationState.SELECTED
        ScreenSnapshotStore.publish(
            ScreenSnapshotStore.latest().copy(
                capturedAtMillis = System.currentTimeMillis(),
                automationState = AutomationState.SELECTED,
                message = "$itemLabel 항목의 예약 대기 신청을 완료하고 자동화를 중지했습니다.",
            ),
        )
    }

    private fun failWaitlistFlow(message: String) {
        clearWaitlistFlow()
        AutomationSettings.setEnabled(this, false)
        publishStatus(AutomationState.ERROR, message)
    }

    private fun retryPendingAction(message: String) {
        actionButtonAttempts += 1
        if (actionButtonAttempts >= MAX_ACTION_BUTTON_ATTEMPTS) {
            failPendingAction("$message 자동으로 다시 갱신하지 않고 중지했습니다.")
            return
        }

        publishStatus(
            AutomationState.RUNNING,
            "$message (${actionButtonAttempts}/$MAX_ACTION_BUTTON_ATTEMPTS)",
        )
        scheduleActionButtonClick()
    }

    private fun failPendingAction(message: String) {
        clearPendingAction()
        AutomationSettings.setEnabled(this, false)
        publishStatus(AutomationState.ERROR, message)
    }

    private fun clearPendingAction() {
        pendingActionButton?.let(mainHandler::removeCallbacks)
        pendingActionButton = null
        pendingReservationAction = null
        pendingItemIndex = null
        actionButtonAttempts = 0
    }

    private fun clearWaitlistFlow() {
        pendingWaitlistFlow?.let(mainHandler::removeCallbacks)
        pendingWaitlistFlow = null
        waitlistFlowStep = null
        waitlistFlowAttempts = 0
        waitlistItemLabel = null
    }

    private fun actionLabel(action: ReservationAction): String = when (action) {
        ReservationAction.BOOK -> "예매"
        ReservationAction.WAITLIST -> "예약 대기 신청"
    }

    private fun clickAtBounds(target: ScreenBounds): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != TARGET_PACKAGE) {
            root.recycle()
            return false
        }

        val clickedByNodeAction = try {
            AccessibilityTreeReader.performClickAtBounds(root, target)
        } finally {
            root.recycle()
        }
        return clickedByNodeAction || (isKorailForeground() && dispatchTap(target))
    }

    private fun isKorailForeground(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            root.packageName?.toString() == TARGET_PACKAGE
        } finally {
            root.recycle()
        }
    }

    private fun dispatchTap(target: ScreenBounds): Boolean {
        val path = Path().apply { moveTo(target.centerX, target.centerY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MILLIS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun pauseAutomation(message: String) {
        pendingRefresh?.let(mainHandler::removeCallbacks)
        pendingRefresh = null
        clearPendingAction()
        clearWaitlistFlow()
        automationState = if (AutomationSettings.isEnabled(this)) AutomationState.PAUSED else AutomationState.IDLE
        publishStatus(automationState, message)
    }

    private fun publishStatus(state: AutomationState, message: String) {
        automationState = state
        val previous = ScreenSnapshotStore.latest()
        ScreenSnapshotStore.publish(
            previous.copy(
                capturedAtMillis = System.currentTimeMillis(),
                automationState = state,
                message = message,
            ),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "com.korail.talk"

        private const val EVENT_DEBOUNCE_MILLIS = 300L
        private const val OCR_MIN_INTERVAL_MILLIS = 1_500L
        private const val MIN_REFRESH_DELAY_MILLIS = 2_000L
        private const val MAX_REFRESH_DELAY_MILLIS = 5_000L
        private const val RESULT_SETTLE_MILLIS = 1_000L
        private const val ACTION_BUTTON_SETTLE_MILLIS = 800L
        private const val WAITLIST_STEP_SETTLE_MILLIS = 500L
        private const val RETRY_DELAY_MILLIS = 300L
        private const val TAP_DURATION_MILLIS = 50L
        private const val MAX_EVENT_TEXT = 20
        private const val MAX_ACTION_BUTTON_ATTEMPTS = 5
        private const val MAX_WAITLIST_STEP_ATTEMPTS = 12
        private const val STOP_NOTICE_LABEL = "서대구정차하는열차입니다"

        @Volatile
        private var activeService: KorailAccessibilityService? = null

        fun notifyAutomationPreferenceChanged() {
            activeService?.onAutomationPreferenceChanged()
        }
    }
}
