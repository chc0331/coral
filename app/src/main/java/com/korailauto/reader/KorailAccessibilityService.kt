package com.korailauto.reader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.Locale
import kotlin.random.Random

private enum class WaitlistFlowStep {
    DISMISS_NOTICE,
    CHECK_SPECIAL_SEAT,
    CHECK_PRIVACY,
    ACCEPT_PRIVACY,
    SUBMIT,
}

class KorailAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val automationWakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:KorailAutoRefresh",
        )
    }

    private var pendingSnapshot: Runnable? = null
    private var pendingRefresh: Runnable? = null
    private var pendingActionButton: Runnable? = null
    private var pendingWaitlistFlow: Runnable? = null
    private var pendingCompletionCheck: Runnable? = null
    private var automationRunId = 0L
    private var nextScreenshotRequestId = 0L
    private var activeScreenshotRequestId: Long? = null
    private var lastOcrAtMillis = 0L
    private var lastEventText: List<String> = emptyList()
    private var automationState = AutomationState.IDLE
    private var pendingReservationAction: ReservationAction? = null
    private var pendingItemIndex: Int? = null
    private var actionButtonAttempts = 0
    private var waitlistFlowStep: WaitlistFlowStep? = null
    private var waitlistFlowAttempts = 0
    private var waitlistItemLabel: String? = null
    private var pendingCompletionType: ReservationCompletionType? = null
    private var pendingCompletionItemLabel: String? = null
    private var completionCheckAttempts = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        AutomationSettings.enableWhenServiceConnected(this)
        serviceInfo = serviceInfo.apply {
            packageNames = arrayOf(TARGET_PACKAGE)
            notificationTimeout = EVENT_DEBOUNCE_MILLIS
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        startFreshAutomationRun()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        lastEventText = event.text
            .map { it.toString().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_EVENT_TEXT)

        if (pendingCompletionType != null) return

        // The app can temporarily leave the foreground while the user opens a Korail sub-screen.
        // Resume the normal refresh loop as soon as its results screen is visible again.
        if (automationState == AutomationState.PAUSED && AutomationSettings.isEnabled(this)) {
            automationState = AutomationState.RUNNING
            scheduleNextRefresh()
            return
        }
        scheduleSnapshot(evaluateAutomation = false)
    }

    override fun onInterrupt() {
        pauseAutomation("접근성 서비스가 중단되었습니다.")
    }

    override fun onDestroy() {
        automationRunId += 1
        mainHandler.removeCallbacksAndMessages(null)
        releaseAutomationWakeLock()
        textRecognizer.close()
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    fun onAutomationPreferenceChanged() {
        mainHandler.post {
            if (AutomationSettings.isEnabled(this)) {
                startFreshAutomationRun()
            } else {
                pauseAutomation("자동화가 꺼져 있습니다.")
            }
        }
    }

    /** Starts a clean run whenever the service or the automation switch is turned on. */
    private fun startFreshAutomationRun() {
        automationRunId += 1
        mainHandler.removeCallbacksAndMessages(null)
        pendingSnapshot = null
        pendingRefresh = null
        pendingActionButton = null
        pendingWaitlistFlow = null
        pendingCompletionCheck = null
        activeScreenshotRequestId = null
        lastOcrAtMillis = 0L
        lastEventText = emptyList()
        pendingReservationAction = null
        pendingItemIndex = null
        actionButtonAttempts = 0
        waitlistFlowStep = null
        waitlistFlowAttempts = 0
        waitlistItemLabel = null
        pendingCompletionType = null
        pendingCompletionItemLabel = null
        completionCheckAttempts = 0

        if (!AutomationSettings.isEnabled(this)) {
            pauseAutomation("자동화가 꺼져 있습니다.")
            return
        }

        automationState = AutomationState.RUNNING
        publishStatus(automationState, "새 자동화 시나리오를 시작합니다.")
        scheduleNextRefresh()
    }

    private fun screenshotInFlight(): Boolean = activeScreenshotRequestId != null

    private fun beginScreenshotRequest(): Long? {
        if (screenshotInFlight()) return null
        return (++nextScreenshotRequestId).also { activeScreenshotRequestId = it }
    }

    private fun finishScreenshotRequest(requestId: Long) {
        if (activeScreenshotRequestId == requestId) activeScreenshotRequestId = null
    }

    private fun isCurrentRun(runId: Long): Boolean = runId == automationRunId

    private fun accessibilityTextLines(nodes: List<AccessibleNodeSnapshot>): List<OcrLine> =
        nodes.flatMap { node ->
            listOf(
                node.text,
                node.contentDescription,
                node.stateDescription,
                node.hintText,
                node.paneTitle,
            ).filter { it.isNotBlank() }
                .distinct()
                .map { text -> OcrLine(text, node.bounds) }
        }

    private fun scheduleSnapshot(evaluateAutomation: Boolean) {
        pendingSnapshot?.let(mainHandler::removeCallbacks)
        val runnable = Runnable { captureCurrentScreen(evaluateAutomation) }
        pendingSnapshot = runnable
        mainHandler.postDelayed(runnable, EVENT_DEBOUNCE_MILLIS)
    }

    private fun captureCurrentScreen(evaluateAutomation: Boolean) {
        val root = findKorailRoot()
        if (root == null) {
            if (evaluateAutomation) pauseAutomation("코레일 화면이 포그라운드에 없습니다.")
            return
        }

        val tree = try {
            AccessibilityTreeReader.read(root)
        } finally {
            root.recycle()
        }

        val nodeLines = accessibilityTextLines(tree.nodes)
        val nodeItems = SeatAvailabilityParser.parse(tree.itemBounds, nodeLines).map { item ->
            if (item.bounds in tree.waitlistItemBounds && item.generalStatus == SeatStatus.UNKNOWN) {
                item.copy(generalStatus = SeatStatus.WAITLIST)
            } else {
                item
            }
        }
        val snapshot = ScreenSnapshot(
            capturedAtMillis = System.currentTimeMillis(),
            packageName = tree.packageName,
            eventText = lastEventText,
            nodes = tree.nodes,
            refreshTarget = tree.refreshTarget,
            trainItems = nodeItems,
            automationState = automationState,
            message = "접근성 노드 ${tree.nodes.size}개와 좌석 관련 텍스트 ${nodeLines.size}개를 읽었습니다.",
        )
        ScreenSnapshotStore.publish(snapshot)

        if (tree.itemBounds.isEmpty()) {
            if (evaluateAutomation) scheduleNextRefresh()
            return
        }

        requestScreenshot(snapshot, evaluateAutomation)
    }

    private fun requestScreenshot(snapshot: ScreenSnapshot, evaluateAutomation: Boolean) {
        if (screenshotInFlight()) {
            if (evaluateAutomation) {
                mainHandler.postDelayed({ captureCurrentScreen(evaluateAutomation = true) }, RETRY_DELAY_MILLIS)
            }
            return
        }

        val now = SystemClock.uptimeMillis()
        if (!evaluateAutomation && now - lastOcrAtMillis < OCR_MIN_INTERVAL_MILLIS) return

        val runId = automationRunId
        val requestId = beginScreenshotRequest() ?: return
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
                        finishScreenshotRequest(requestId)
                        if (isCurrentRun(runId)) {
                            onScreenshotFailure(snapshot, evaluateAutomation, "스크린샷 비트맵을 만들지 못했습니다.")
                        }
                        return
                    }
                    if (!isCurrentRun(runId)) {
                        bitmap.recycle()
                        finishScreenshotRequest(requestId)
                        return
                    }
                    analyzeScreenshot(bitmap, snapshot, evaluateAutomation, runId, requestId)
                }

                override fun onFailure(errorCode: Int) {
                    finishScreenshotRequest(requestId)
                    if (isCurrentRun(runId)) {
                        onScreenshotFailure(snapshot, evaluateAutomation, "스크린샷 캡처 실패 코드: $errorCode")
                    }
                }
            },
        )
    }

    private fun analyzeScreenshot(
        bitmap: Bitmap,
        snapshot: ScreenSnapshot,
        evaluateAutomation: Boolean,
        runId: Long,
        requestId: Long,
    ) {
        textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                bitmap.recycle()
                finishScreenshotRequest(requestId)
                if (!isCurrentRun(runId)) return@addOnSuccessListener

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
                    val nodeItem = snapshot.trainItems.getOrNull(index)
                    item.copy(
                        rawText = (nodeItem?.rawText.orEmpty() + item.rawText).distinct(),
                        generalStatus = nodeItem?.generalStatus?.takeUnless { it == SeatStatus.UNKNOWN }
                            ?: item.generalStatus,
                        specialStatus = nodeItem?.specialStatus?.takeUnless { it == SeatStatus.UNKNOWN }
                            ?: item.specialStatus,
                    )
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
                finishScreenshotRequest(requestId)
                if (isCurrentRun(runId)) {
                    onScreenshotFailure(snapshot, evaluateAutomation, "OCR 실패: ${error.message.orEmpty()}")
                }
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

        acquireAutomationWakeLock()
        val delay = Random.nextLong(MIN_REFRESH_DELAY_MILLIS, MAX_REFRESH_DELAY_MILLIS + 1)
        automationState = AutomationState.RUNNING
        publishStatus(
            automationState,
            "${String.format(Locale.US, "%.1f", delay / 1_000.0)}초 후 화면을 갱신합니다.",
        )

        val runnable = Runnable { refreshAndAnalyze() }
        pendingRefresh = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun refreshAndAnalyze() {
        if (!AutomationSettings.isEnabled(this)) {
            pauseAutomation("자동화가 꺼져 있습니다.")
            return
        }

        val root = findKorailRoot()
        if (root == null) {
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

        val root = findKorailRoot()
        if (root == null) {
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
        if (screenshotInFlight()) {
            retryPendingAction("${actionLabel(action)} 버튼 화면을 읽는 중입니다.")
            return
        }

        val runId = automationRunId
        val requestId = beginScreenshotRequest() ?: return
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    if (bitmap == null) {
                        finishScreenshotRequest(requestId)
                        if (isCurrentRun(runId)) {
                            retryPendingAction("${actionLabel(action)} 버튼 화면을 읽지 못했습니다.")
                        }
                        return
                    }
                    if (!isCurrentRun(runId)) {
                        bitmap.recycle()
                        finishScreenshotRequest(requestId)
                        return
                    }

                    textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { result ->
                            bitmap.recycle()
                            finishScreenshotRequest(requestId)
                            if (!isCurrentRun(runId) || pendingReservationAction != action || !AutomationSettings.isEnabled(this@KorailAccessibilityService)) {
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
                            finishScreenshotRequest(requestId)
                            if (isCurrentRun(runId)) {
                                retryPendingAction("${actionLabel(action)} 버튼 화면 OCR에 실패했습니다.")
                            }
                        }
                }

                override fun onFailure(errorCode: Int) {
                    finishScreenshotRequest(requestId)
                    if (isCurrentRun(runId)) {
                        retryPendingAction("${actionLabel(action)} 버튼 캡처에 실패했습니다.")
                    }
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
        awaitCompletion(ReservationCompletionType.BOOKING, itemLabel)
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

        val root = findKorailRoot()
        if (root == null) {
            failWaitlistFlow("코레일 화면을 확인할 수 없어 예약 대기 신청을 중지했습니다.")
            return
        }

        val tree = try {
            AccessibilityTreeReader.read(root)
        } finally {
            root.recycle()
        }

        if (step == WaitlistFlowStep.DISMISS_NOTICE && AccessibilityTreeReader.hasWaitlistNotice(tree.nodes)) {
            val confirmTarget = AccessibilityTreeReader.findEnabledClickTarget(tree.nodes, setOf("확인"))
            if (confirmTarget != null && clickAtBounds(confirmTarget)) {
                moveWaitlistFlow(WaitlistFlowStep.CHECK_SPECIAL_SEAT, "이용 안내를 확인했습니다. 신청 화면을 확인합니다.")
            } else {
                retryWaitlistFlow("이용 안내의 확인 버튼을 찾지 못했습니다.")
            }
            return
        }

        when (step) {
            WaitlistFlowStep.DISMISS_NOTICE -> {
                moveWaitlistFlow(WaitlistFlowStep.CHECK_SPECIAL_SEAT, "예약 대기 신청 화면을 확인합니다.")
            }

            WaitlistFlowStep.CHECK_SPECIAL_SEAT -> {
                val checkBox = AccessibilityTreeReader.findCheckBoxTarget(tree.nodes, "특실좌석포함")
                when {
                    checkBox == null -> requestWaitlistCheckboxScreenshot(
                        step = WaitlistFlowStep.CHECK_SPECIAL_SEAT,
                        label = "특실좌석포함",
                        nextStep = WaitlistFlowStep.CHECK_PRIVACY,
                        selectedMessage = "OCR로 특실 좌석 포함을 선택했습니다.",
                    )

                    checkBox.checked -> moveWaitlistFlow(WaitlistFlowStep.CHECK_PRIVACY, "특실 좌석 포함을 선택했습니다.")
                    clickAtBounds(checkBox.bounds) -> scheduleWaitlistFlow()
                    else -> retryWaitlistFlow("특실 좌석 포함 체크박스를 누르지 못했습니다.")
                }
            }

            WaitlistFlowStep.CHECK_PRIVACY -> {
                val checkBox = AccessibilityTreeReader.findCheckBoxTarget(tree.nodes, "개인정보수집및이용동의")
                when {
                    checkBox == null -> requestWaitlistCheckboxScreenshot(
                        step = WaitlistFlowStep.CHECK_PRIVACY,
                        label = "개인정보수집및이용동의",
                        nextStep = WaitlistFlowStep.ACCEPT_PRIVACY,
                        selectedMessage = "OCR로 개인정보 수집 및 이용 동의를 선택했습니다.",
                    )

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

    private fun requestWaitlistCheckboxScreenshot(
        step: WaitlistFlowStep,
        label: String,
        nextStep: WaitlistFlowStep,
        selectedMessage: String,
    ) {
        if (screenshotInFlight()) {
            retryWaitlistFlow("$label 체크박스 화면을 읽는 중입니다.")
            return
        }

        val runId = automationRunId
        val requestId = beginScreenshotRequest() ?: return
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    if (bitmap == null) {
                        finishScreenshotRequest(requestId)
                        if (isCurrentRun(runId)) {
                            retryWaitlistFlow("$label 체크박스 화면을 읽지 못했습니다.")
                        }
                        return
                    }
                    if (!isCurrentRun(runId)) {
                        bitmap.recycle()
                        finishScreenshotRequest(requestId)
                        return
                    }

                    textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { result ->
                            bitmap.recycle()
                            finishScreenshotRequest(requestId)
                            if (!isCurrentRun(runId) || waitlistFlowStep != step || !AutomationSettings.isEnabled(this@KorailAccessibilityService)) {
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
                            val target = WaitlistCheckboxFinder.find(label, lines)
                            if (target != null && clickAtBounds(target)) {
                                moveWaitlistFlow(nextStep, selectedMessage)
                            } else {
                                retryWaitlistFlow("OCR로 $label 체크박스를 찾는 중입니다.")
                            }
                        }
                        .addOnFailureListener {
                            bitmap.recycle()
                            finishScreenshotRequest(requestId)
                            if (isCurrentRun(runId)) {
                                retryWaitlistFlow("$label 체크박스 화면 OCR에 실패했습니다.")
                            }
                        }
                }

                override fun onFailure(errorCode: Int) {
                    finishScreenshotRequest(requestId)
                    if (isCurrentRun(runId)) {
                        retryWaitlistFlow("$label 체크박스 화면 캡처에 실패했습니다.")
                    }
                }
            },
        )
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
        awaitCompletion(ReservationCompletionType.WAITLIST, itemLabel)
    }

    private fun awaitCompletion(type: ReservationCompletionType, itemLabel: String) {
        pendingRefresh?.let(mainHandler::removeCallbacks)
        pendingRefresh = null
        clearPendingAction()
        clearWaitlistFlow()
        clearPendingCompletion()
        lastEventText = emptyList()
        pendingCompletionType = type
        pendingCompletionItemLabel = itemLabel
        completionCheckAttempts = 0
        publishStatus(AutomationState.RUNNING, "$itemLabel 항목의 완료 안내를 확인하는 중입니다.")
        scheduleCompletionCheck()
    }

    private fun scheduleCompletionCheck() {
        pendingCompletionCheck?.let(mainHandler::removeCallbacks)
        val runnable = Runnable { checkPendingCompletion() }
        pendingCompletionCheck = runnable
        mainHandler.postDelayed(runnable, COMPLETION_CHECK_DELAY_MILLIS)
    }

    private fun checkPendingCompletion() {
        val type = pendingCompletionType ?: return
        if (!AutomationSettings.isEnabled(this)) {
            clearPendingCompletion()
            pauseAutomation("자동화가 꺼져 있습니다.")
            return
        }

        val root = findKorailRoot()
        if (root == null) {
            failCompletion("코레일 완료 안내를 확인할 수 없어 알림을 보내지 않았습니다.")
            return
        }
        val tree = try {
            AccessibilityTreeReader.read(root)
        } finally {
            root.recycle()
        }
        val nodeText = tree.nodes.flatMap { node ->
            listOf(node.text, node.contentDescription, node.stateDescription, node.hintText, node.paneTitle)
        }
        if (ReservationCompletionDetector.matches(type, lastEventText + nodeText)) {
            finishSuccessfulCompletion(type)
            return
        }

        requestCompletionScreenshot(type)
    }

    private fun requestCompletionScreenshot(type: ReservationCompletionType) {
        if (screenshotInFlight()) {
            retryCompletionCheck("완료 안내 화면을 읽는 중입니다.")
            return
        }

        val runId = automationRunId
        val requestId = beginScreenshotRequest() ?: return
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    if (bitmap == null) {
                        finishScreenshotRequest(requestId)
                        if (isCurrentRun(runId)) {
                            retryCompletionCheck("완료 안내 화면을 읽지 못했습니다.")
                        }
                        return
                    }
                    if (!isCurrentRun(runId)) {
                        bitmap.recycle()
                        finishScreenshotRequest(requestId)
                        return
                    }

                    textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { result ->
                            bitmap.recycle()
                            finishScreenshotRequest(requestId)
                            if (!isCurrentRun(runId) || pendingCompletionType != type || !AutomationSettings.isEnabled(this@KorailAccessibilityService)) {
                                return@addOnSuccessListener
                            }
                            val ocrText = result.textBlocks.flatMap { block -> block.lines.map { line -> line.text } }
                            if (ReservationCompletionDetector.matches(type, ocrText)) {
                                finishSuccessfulCompletion(type)
                            } else {
                                retryCompletionCheck("완료 안내 문구를 찾는 중입니다.")
                            }
                        }
                        .addOnFailureListener {
                            bitmap.recycle()
                            finishScreenshotRequest(requestId)
                            if (isCurrentRun(runId)) {
                                retryCompletionCheck("완료 안내 화면 OCR에 실패했습니다.")
                            }
                        }
                }

                override fun onFailure(errorCode: Int) {
                    finishScreenshotRequest(requestId)
                    if (isCurrentRun(runId)) {
                        retryCompletionCheck("완료 안내 화면 캡처에 실패했습니다.")
                    }
                }
            },
        )
    }

    private fun retryCompletionCheck(message: String) {
        completionCheckAttempts += 1
        if (completionCheckAttempts >= MAX_COMPLETION_CHECK_ATTEMPTS) {
            failCompletion("$message 최종 완료를 확인하지 못해 알림 없이 자동화를 중지했습니다.")
            return
        }
        publishStatus(
            AutomationState.RUNNING,
            "$message ($completionCheckAttempts/$MAX_COMPLETION_CHECK_ATTEMPTS)",
        )
        scheduleCompletionCheck()
    }

    private fun finishSuccessfulCompletion(type: ReservationCompletionType) {
        val itemLabel = pendingCompletionItemLabel ?: "선택한"
        clearPendingCompletion()
        AutomationSettings.setEnabled(this, false)
        releaseAutomationWakeLock()
        publishStatus(AutomationState.SELECTED, "$itemLabel 항목의 완료를 확인했습니다. Discord 알림을 전송합니다.")
        DiscordNotificationClient.sendCompletion(this, type, System.currentTimeMillis()) { delivered ->
            if (delivered) {
                publishStatus(AutomationState.SELECTED, "완료를 확인했고 Discord 알림을 전송했습니다.")
            } else {
                publishStatus(
                    AutomationState.ERROR,
                    "완료는 확인했지만 Discord 알림 전송에 실패했습니다. 웹훅 설정과 네트워크를 확인하세요.",
                )
            }
        }
    }

    private fun failCompletion(message: String) {
        clearPendingCompletion()
        AutomationSettings.setEnabled(this, false)
        releaseAutomationWakeLock()
        publishStatus(AutomationState.ERROR, message)
    }

    private fun failWaitlistFlow(message: String) {
        clearWaitlistFlow()
        resumeRefreshAfterRecoverableFailure(message)
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
        resumeRefreshAfterRecoverableFailure(message)
    }

    /**
     * A button or checkbox lookup can fail because Korail is still animating a sheet or dialog.
     * Those failures happen before the final submission, so return to periodic refresh instead of
     * turning the user's automation setting off.
     */
    private fun resumeRefreshAfterRecoverableFailure(message: String) {
        if (!AutomationSettings.isEnabled(this)) {
            pauseAutomation("자동화가 꺼져 있습니다.")
            return
        }

        publishStatus(AutomationState.RUNNING, "$message 다음 화면 갱신을 계속합니다.")
        mainHandler.postDelayed(
            {
                if (!AutomationSettings.isEnabled(this)) {
                    pauseAutomation("자동화가 꺼져 있습니다.")
                } else if (!hasKorailWindow()) {
                    pauseAutomation("코레일 화면이 포그라운드에 없습니다.")
                } else {
                    scheduleNextRefresh()
                }
            },
            RECOVERY_SETTLE_MILLIS,
        )
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

    private fun clearPendingCompletion() {
        pendingCompletionCheck?.let(mainHandler::removeCallbacks)
        pendingCompletionCheck = null
        pendingCompletionType = null
        pendingCompletionItemLabel = null
        completionCheckAttempts = 0
    }

    private fun actionLabel(action: ReservationAction): String = when (action) {
        ReservationAction.BOOK -> "예매"
        ReservationAction.WAITLIST -> "예약 대기 신청"
    }

    private fun clickAtBounds(target: ScreenBounds): Boolean {
        val root = findKorailRoot() ?: return false

        val clickedByNodeAction = try {
            AccessibilityTreeReader.performClickAtBounds(root, target)
        } finally {
            root.recycle()
        }
        return clickedByNodeAction || (isKorailInputWindowActive() && dispatchTap(target))
    }

    /**
     * TalkBack reads all interactive windows, not only rootInActiveWindow. This matters on
     * foldables where the system UI can own the focused window while Korail remains visible.
     */
    private fun findKorailRoot(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot?.packageName?.toString() == TARGET_PACKAGE) return activeRoot
        activeRoot?.recycle()

        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == TARGET_PACKAGE) return root
            root.recycle()
        }
        return null
    }

    private fun isKorailInputWindowActive(): Boolean {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            try {
                if (activeRoot.packageName?.toString() == TARGET_PACKAGE) return true
            } finally {
                activeRoot.recycle()
            }
        }

        return windows.any { window ->
            if (!window.isActive && !window.isFocused) return@any false
            val root = window.root ?: return@any false
            try {
                root.packageName?.toString() == TARGET_PACKAGE
            } finally {
                root.recycle()
            }
        }
    }

    private fun hasKorailWindow(): Boolean {
        val root = findKorailRoot() ?: return false
        root.recycle()
        return true
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
        clearPendingCompletion()
        releaseAutomationWakeLock()
        automationState = if (AutomationSettings.isEnabled(this)) AutomationState.PAUSED else AutomationState.IDLE
        publishStatus(automationState, message)
    }

    private fun acquireAutomationWakeLock() {
        if (!automationWakeLock.isHeld) automationWakeLock.acquire()
    }

    private fun releaseAutomationWakeLock() {
        if (automationWakeLock.isHeld) automationWakeLock.release()
    }

    private fun publishStatus(state: AutomationState, message: String) {
        automationState = state
        Log.i(LOG_TAG, "$state: $message")
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
        private const val MAX_REFRESH_DELAY_MILLIS = 3_500L
        private const val RESULT_SETTLE_MILLIS = 1_000L
        private const val ACTION_BUTTON_SETTLE_MILLIS = 800L
        private const val WAITLIST_STEP_SETTLE_MILLIS = 500L
        private const val COMPLETION_CHECK_DELAY_MILLIS = 500L
        private const val RECOVERY_SETTLE_MILLIS = 1_000L
        private const val RETRY_DELAY_MILLIS = 300L
        private const val TAP_DURATION_MILLIS = 50L
        private const val MAX_EVENT_TEXT = 20
        private const val MAX_ACTION_BUTTON_ATTEMPTS = 5
        private const val MAX_WAITLIST_STEP_ATTEMPTS = 12
        private const val MAX_COMPLETION_CHECK_ATTEMPTS = 12
        private const val STOP_NOTICE_LABEL = "서대구정차하는열차입니다"
        private const val LOG_TAG = "KorailAuto"

        @Volatile
        private var activeService: KorailAccessibilityService? = null

        fun notifyAutomationPreferenceChanged() {
            activeService?.onAutomationPreferenceChanged()
        }
    }
}
