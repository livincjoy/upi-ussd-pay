package com.upiusssd.pay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * UpiAccessibilityService
 *
 * Automates *99# USSD dialog navigation.
 * Features:
 * 1. DYNAMIC MENU PARSING: Automatically extracts the menu digits for "Send Money",
 *    "UPI ID", and "Mobile" from the live USSD text so you don't even have to
 *    manually adjust the 3 boxes!
 * 2. CRITICAL SECURITY FEATURE: Automatically detects the UPI PIN prompt and STOPS
 *    automation immediately so the user manually enters their confidential PIN.
 */
class UpiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "UpiAccessibility"
        
        // State Machine Flags
        var isPaymentActive = false
        var targetVpa = ""
        var targetAmount = ""
        var targetRemarks = "1"
        var isMobileMode = false

        // Configurable Menu Numbers (Auto-adjusted dynamically if detected in USSD)
        var menuSendMoney = "1"
        var menuUpiId = "3"
        var menuMobile = "1"

        var currentStep = 0
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = arrayOf("com.android.phone", "com.google.android.dialer", "com.samsung.android.dialer")
        }
        serviceInfo = info
        Log.d(TAG, "UPI USSD Accessibility Service connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isPaymentActive || event == null) return

        val rootNode = rootInActiveWindow ?: return
        val dialogText = extractDialogText(rootNode)
        Log.d(TAG, "Observed Dialog Text: $dialogText (Step: $currentStep)")

        // 1. Check if we hit UPI PIN prompt -> STOP immediately for security!
        if (isPinPrompt(dialogText)) {
            Log.i(TAG, ">>> Detected UPI PIN prompt! Halting automation for user security. <<<")
            isPaymentActive = false
            currentStep = 0
            return
        }

        // 2. Dynamic USSD NLP: Auto-adjust menu numbers from incoming text
        autoAdjustMenuFromUssd(dialogText)

        // Delay slightly (500ms) to ensure USSD dialog view has rendered its input field
        handler.postDelayed({
            processUssdStep(rootNode, dialogText)
        }, 500)
    }

    /**
     * Dynamically extracts menu choice numbers directly from incoming USSD text
     */
    private fun autoAdjustMenuFromUssd(dialogText: String) {
        val lines = dialogText.split("\n")
        for (line in lines) {
            val trimmed = line.trim()

            // Dynamic check for "Send Money"
            val sendMatch = Regex("^(\\d+)[\\.\\:\\-\\s]+.*send\\s*money", RegexOption.IGNORE_CASE).find(trimmed)
            if (sendMatch != null) {
                val detected = sendMatch.groupValues[1]
                if (detected != menuSendMoney) {
                    Log.i(TAG, "Auto-Adjusted menuSendMoney: $menuSendMoney -> $detected")
                    menuSendMoney = detected
                }
            }

            // Dynamic check for "UPI ID / VPA"
            val upiMatch = Regex("^(\\d+)[\\.\\:\\-\\s]+.*(upi\\s*id|vpa)", RegexOption.IGNORE_CASE).find(trimmed)
            if (upiMatch != null) {
                val detected = upiMatch.groupValues[1]
                if (detected != menuUpiId) {
                    Log.i(TAG, "Auto-Adjusted menuUpiId: $menuUpiId -> $detected")
                    menuUpiId = detected
                }
            }

            // Dynamic check for "Mobile"
            val mobMatch = Regex("^(\\d+)[\\.\\:\\-\\s]+.*mobile", RegexOption.IGNORE_CASE).find(trimmed)
            if (mobMatch != null) {
                val detected = mobMatch.groupValues[1]
                if (detected != menuMobile) {
                    Log.i(TAG, "Auto-Adjusted menuMobile: $menuMobile -> $detected")
                    menuMobile = detected
                }
            }
        }
    }

    private fun processUssdStep(rootNode: AccessibilityNodeInfo, text: String) {
        val lowerText = text.lowercase()

        when {
            // Step 1: Main Menu (Welcome to *99# -> Send Money)
            (lowerText.contains("send money") || lowerText.contains("select option") || currentStep == 0) &&
            !lowerText.contains("enter mobile") && !lowerText.contains("enter upi") -> {
                Log.d(TAG, "Step 1: Selecting Send Money option ($menuSendMoney)")
                if (fillInputAndSend(rootNode, menuSendMoney)) {
                    currentStep = 1
                }
            }

            // Step 2: Select Transfer Method (1. Mobile, 2. UPI ID, etc.)
            (lowerText.contains("mobile") || lowerText.contains("upi id") || lowerText.contains("beneficiary")) &&
            currentStep == 1 -> {
                val option = if (isMobileMode) menuMobile else menuUpiId
                Log.d(TAG, "Step 2: Selecting Method option ($option)")
                if (fillInputAndSend(rootNode, option)) {
                    currentStep = 2
                }
            }

            // Step 3: Enter UPI ID or Mobile Number
            (lowerText.contains("enter upi id") || lowerText.contains("enter mobile") || lowerText.contains("payee")) &&
            currentStep == 2 -> {
                Log.d(TAG, "Step 3: Entering Recipient ($targetVpa)")
                if (fillInputAndSend(rootNode, targetVpa)) {
                    currentStep = 3
                }
            }

            // Step 4: Enter Amount
            (lowerText.contains("enter amount") || lowerText.contains("amount (rs)")) &&
            currentStep == 3 -> {
                Log.d(TAG, "Step 4: Entering Amount ($targetAmount)")
                if (fillInputAndSend(rootNode, targetAmount)) {
                    currentStep = 4
                }
            }

            // Step 5: Enter Remarks
            (lowerText.contains("remark") || lowerText.contains("remarks") || lowerText.contains("skip")) &&
            currentStep == 4 -> {
                Log.d(TAG, "Step 5: Entering Remarks / Skip ($targetRemarks)")
                if (fillInputAndSend(rootNode, targetRemarks)) {
                    currentStep = 5
                }
            }
        }
    }

    private fun fillInputAndSend(rootNode: AccessibilityNodeInfo, value: String): Boolean {
        val editTexts = rootNode.findAccessibilityNodeInfosByViewId("android:id/input_field")
            .ifEmpty { findNodesByClassName(rootNode, "android.widget.EditText") }

        if (editTexts.isEmpty()) {
            Log.w(TAG, "No EditText found in USSD dialog")
            return false
        }

        val inputNode = editTexts[0]
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        if (!textSet) {
            Log.w(TAG, "Failed to set text on USSD input field")
            return false
        }

        // Find and click "Send" / "OK" / "Reply" button
        handler.postDelayed({
            clickSendButton(rootNode)
        }, 250)

        return true
    }

    private fun clickSendButton(rootNode: AccessibilityNodeInfo) {
        val sendButtons = rootNode.findAccessibilityNodeInfosByViewId("android:id/button1")
            .ifEmpty { findNodesByText(rootNode, "Send") }
            .ifEmpty { findNodesByText(rootNode, "OK") }
            .ifEmpty { findNodesByText(rootNode, "Reply") }

        if (sendButtons.isNotEmpty()) {
            sendButtons[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Successfully clicked Send button")
        } else {
            Log.w(TAG, "Send button not found in USSD window")
        }
    }

    private fun isPinPrompt(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("enter upi pin") ||
               lower.contains("enter 6-digit pin") ||
               lower.contains("enter 4-digit pin") ||
               lower.contains("enter mpin") ||
               lower.contains("upi pin")
    }

    private fun extractDialogText(node: AccessibilityNodeInfo): String {
        val builder = StringBuilder()
        val textViews = findNodesByClassName(node, "android.widget.TextView")
        for (tv in textViews) {
            tv.text?.let { builder.append(it).append(" ") }
        }
        return builder.toString()
    }

    private fun findNodesByClassName(node: AccessibilityNodeInfo, className: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        if (node.className?.toString() == className) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { results.addAll(findNodesByClassName(it, className)) }
        }
        return results
    }

    private fun findNodesByText(node: AccessibilityNodeInfo, targetText: String): List<AccessibilityNodeInfo> {
        return node.findAccessibilityNodeInfosByText(targetText)
    }

    override fun onInterrupt() {
        Log.w(TAG, "UPI Accessibility Service Interrupted")
        isPaymentActive = false
    }
}
