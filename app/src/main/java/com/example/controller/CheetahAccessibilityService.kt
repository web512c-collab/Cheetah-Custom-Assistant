package com.example.controller

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.voice.EgyptianPhoneticNormalizer

/**
 * Permission-gated AccessibilityService allowing Cheetah to perform explicit,
 * voice-requested screen actions (Back, Home, Scroll, Tap label) when enabled by user.
 */
class CheetahAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive; Cheetah never scrapes passwords, OTPs, or background app contents.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun performScroll(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollable(root) ?: return false
        val action = if (down) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return scrollable.performAction(action)
    }

    fun tapElementByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val normTarget = EgyptianPhoneticNormalizer.normalizeForNlu(targetText)
        if (normTarget.isEmpty()) return false
        val node = findNodeByText(root, normTarget) ?: return false
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        return (clickable ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstScrollable(child)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, normTarget: String): AccessibilityNodeInfo? {
        val text = EgyptianPhoneticNormalizer.normalizeForNlu(node.text?.toString().orEmpty())
        val desc = EgyptianPhoneticNormalizer.normalizeForNlu(node.contentDescription?.toString().orEmpty())
        if (text.contains(normTarget) || desc.contains(normTarget)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findNodeByText(child, normTarget)
            if (match != null) return match
        }
        return null
    }

    companion object {
        @Volatile
        var activeInstance: CheetahAccessibilityService? = null
            private set
    }
}
