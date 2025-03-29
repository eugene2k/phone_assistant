package io.github.eugene2k.phone_assistant

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

private val PACKAGE_INSTALLER_ACTIVITIES = arrayOf(
    "com.android.packageinstaller.permission.ui.GrantPermissionsActivity",
    "com.google.android.packageinstaller"
)

class Overlay(context: Context) : androidx.appcompat.widget.AppCompatTextView(context)

enum class State {
    CallInProgress, Answering, CallEnded, Other, Home
}

sealed class ViberInterface {
    data class CallInProgress(val inner: List<AccessibilityNodeInfo>) : ViberInterface()
    data object Answering : ViberInterface()
    data object CallEnded : ViberInterface()
    data object Other : ViberInterface()
    companion object {
        const val CALL_IN_PROGRESS = 0b01
        const val ANSWERING = 0b10
        const val CALL_ENDED = 0b100

        fun checkState(node: AccessibilityNodeInfo, statesToCheck: Int): ViberInterface {
            var results: List<AccessibilityNodeInfo>
            if (statesToCheck and CALL_IN_PROGRESS != 0) {
                results =
                    node.findAccessibilityNodeInfosByViewId("com.viber.voip:id/speakerPhone")
                if (results.isNotEmpty()) {
                    Log.d(javaClass.name, "checkState is CallInProgress")
                    return CallInProgress(results)
                }
            }
            if (statesToCheck and ANSWERING != 0) {
                results =
                    node.findAccessibilityNodeInfosByViewId("com.viber.voip:id/phone_answer")
                if (results.isNotEmpty()) {
                    Log.d(javaClass.name, "checkState is Answering")
                    return Answering
                }
            }
            if (statesToCheck and CALL_ENDED != 0) {
                results =
                    node.findAccessibilityNodeInfosByViewId("com.viber.voip:id/phone_redial")
                if (results.isNotEmpty()) {
                    Log.d(javaClass.name, "checkState is CallEnded")
                    return CallEnded
                }
            }
            Log.d(javaClass.name, "checkState is Other")
            return Other
        }

        fun tryEndCall(root: AccessibilityNodeInfo) {
            val result =
                root.findAccessibilityNodeInfosByViewId("com.viber.voip:id/leaveConference")
            if (result.isNotEmpty()) {
                val endCallButton = result[0]
                endCallButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }
}

class AccessibilityOverride : AccessibilityService() {
    companion object {
        const val LIMITED_MODE_DISABLE = "io.github.eugene2k.phone_assistant.LIMITED_MODE_DISABLE"
        const val OVERLAY_ENABLE = "io.github.eugene2k.phone_assistant.OVERLAY_ENABLE"
    }

    class SpecialDeviceAdminReceiver : DeviceAdminReceiver()

    private var deviceAdminReceiver: ComponentName? = null
    private var limitedMode = false
    private val disableLimitedModeHandler = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            limitedMode = false
            Log.i(javaClass.name, "Limited mode disabled")
        }
    }
    private val enableOverlayHandler = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val contact = intent?.getStringExtra("contact") ?: ""
            accessibilityWindow?.text = contact
            @Suppress("DEPRECATION")
            val accessibilityWindowParams =
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE
                )

            val winMgr =
                getSystemService(WINDOW_SERVICE) as WindowManager
            try {
                winMgr.addView(
                    accessibilityWindow,
                    accessibilityWindowParams
                )
            } catch (_: IllegalStateException) {
                Log.e(javaClass.name, "Overlay already present")
                winMgr.removeView(accessibilityWindow)
            }
        }
    }
    private var lastHandledState = State.Other
    private var wakeLock: PowerManager.WakeLock? = null
    private var accessibilityWindow: Overlay? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        deviceAdminReceiver = ComponentName(this, SpecialDeviceAdminReceiver::class.java)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            javaClass.name
        )
        registerReceiver(disableLimitedModeHandler, IntentFilter(LIMITED_MODE_DISABLE))
        registerReceiver(enableOverlayHandler, IntentFilter(OVERLAY_ENABLE))

        val label = Overlay(this)
        label.textSize = 34.0f
        label.textAlignment = View.TEXT_ALIGNMENT_CENTER
        label.gravity = Gravity.CENTER_VERTICAL
        accessibilityWindow = label
        Log.d(javaClass.name, "Service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(javaClass.name, "Service connected")
        val formatted = serviceInfo.toString()
            .replace(", ", ",\n   ")
        Log.d(javaClass.name, "ServiceInfo {\n   %s\n}".format(formatted))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        fun handleViberEvent(event: AccessibilityEvent) {
            val source = event.source
            if (source != null) {
                val root = if (source.window != null) {
                    source.window.root
                } else {
                    Log.e(
                        javaClass.name,
                        "event source window is null, trying getRootInActiveWindow()"
                    )
                    rootInActiveWindow
                }
                var node = source
                if (root != null) {
                    node = root
                } else {
                    Log.e(
                        javaClass.name,
                        "root is null trying event source"
                    )
                }
                val result = ViberInterface.checkState(
                    node,
                    ViberInterface.CALL_IN_PROGRESS or ViberInterface.ANSWERING or ViberInterface.CALL_ENDED
                )
                handleViberState(result)
            } else {
                Log.e(
                    javaClass.name,
                    "both event root and event source are null"
                )
            }
        }

        if (event != null) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.d(javaClass.name, "Window State Changed")
                    Log.d(
                        javaClass.name,
                        "Package: %s".format(event.packageName?.toString() ?: "<null>")
                    )
                    Log.d(
                        javaClass.name,
                        "Class: %s".format(event.className?.toString() ?: "<null>")
                    )
                    Log.d(javaClass.name, "limitedMode=%b".format(limitedMode))
                    if (event.packageName !in PACKAGE_INSTALLER_ACTIVITIES) {
                        val className = event.className.toString()

                        if (event.packageName == packageName) {
                            if (className == MainActivity::class.java.name) {
                                if (wakeLock!!.isHeld)
                                    wakeLock!!.release()
                                limitedMode = true
                                lastHandledState = State.Home
                                Log.i(javaClass.name, "Limited mode enabled")
                            }
                        } else {
                            if (limitedMode) {
                                when (event.packageName) {
                                    "com.viber.voip" -> {
                                        if (className == "androidx.appcompat.app.AlertDialog") {
                                            val am =
                                                getSystemService(ACTIVITY_SERVICE) as ActivityManager
                                            am.killBackgroundProcesses("com.viber.voip")
                                            startLauncher()
                                            return
                                        }
                                        handleViberEvent(event)
                                    }

                                    "com.whatsapp" -> {
                                        printNodeInfoTree(event.source)
                                    }

                                    else -> {
                                        lastHandledState = State.Other
                                        startLauncher()
                                    }
                                }
                            }
                        }
                    }
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    Log.d(javaClass.name, "Window Content Changed")
                    val className = event.className.toString()
                    Log.d(javaClass.name, "Class: %s".format(className))
                    val changeTypesMap = listOf(
                        Pair(
                            AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED,
                            "CONTENT_CHANGE_TYPE_UNDEFINED"
                        ),
                        Pair(
                            AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE,
                            "CONTENT_CHANGE_TYPE_SUBTREE"
                        ),
                        Pair(
                            AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT,
                            "CONTENT_CHANGE_TYPE_TEXT"
                        ),
                        Pair(
                            AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION,
                            "CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION"
                        ),
                    )
                    var changeTypes = arrayListOf<String>()
                    for (type in changeTypesMap) {
                        if (event.contentChangeTypes and type.first != 0) {
                            changeTypes.add(type.second)
                        }
                    }
                    Log.d(
                        javaClass.name,
                        changeTypes.joinToString(prefix = "Content Change Types: ")
                    )
                    if (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0) return

                    if (limitedMode)
                        when (event.packageName) {
                            "com.viber.voip" -> {
                                handleViberEvent(event)
                            }
                        }
                }

                else -> {
                    Log.d(
                        javaClass.name,
                        "Unrecognized event type: %x".format(event.eventType)
                    )
                }
            }
        }
    }

    private fun handleViberState(result: ViberInterface) {
        when (result) {
            is ViberInterface.CallInProgress -> if (lastHandledState != State.CallInProgress) {
                Log.d(javaClass.name, "ViberState: CallInProgress")

                val speakerButton = result.inner[0]
                speakerButton.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )

                val accessibilityWindow = accessibilityWindow
                if (accessibilityWindow != null) {
                    val buttonBounds = Rect()
                    speakerButton.getBoundsInScreen(buttonBounds)
                    accessibilityWindow.text = ""
                    val accessibilityWindowParams =
                        WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSPARENT
                        )
                    val winMgr =
                        getSystemService(WINDOW_SERVICE) as WindowManager
                    removeOverlay()
                    Log.d(javaClass.name, "Attaching overlay")
                    winMgr.addView(accessibilityWindow, accessibilityWindowParams)
                }
                if (!wakeLock!!.isHeld) wakeLock!!.acquire(120 * 60 * 1000L /*120 minutes*/)

                lastHandledState = State.CallInProgress
            }

            ViberInterface.CallEnded -> if (lastHandledState != State.CallEnded) {
                Log.d(javaClass.name, "ViberState: CallEnded")
                lastHandledState = State.CallEnded
                startLauncher()
            }

            ViberInterface.Answering -> if (lastHandledState != State.Answering) {
                Log.d(javaClass.name, "ViberState: Answering")
                removeOverlay()
                // Call needs answering
                // NOTE: There are three possible outcomes after this state:
                // 1. the user ignores
                // 2. the user picks up
                // 3. the user declines
                // in cases 1 or 3, the user should be returned to the home screen
                // otherwise nothing should be done.
                // Since the check for the main activity doesn't happen when handling
                // PhoneFragmentActivity, and in the TYPE_WINDOW_CONTENT_CHANGED event handler it is
                // only checked in the redialing case expectMainActivity=true is a viable solution

                lastHandledState = State.Answering
            }

            else -> {
                Log.d(
                    javaClass.name,
                    "ViberState: Other"
                )
                if (lastHandledState == State.Answering) {
                    startLauncher()
                }
                lastHandledState = State.Other
                printNodeInfoTree(rootInActiveWindow)
            }
        }
    }

    private fun printNodeInfoTree(view: AccessibilityNodeInfo?) {
        fun nodeInfoTreeToFormattedString(
            view: AccessibilityNodeInfo?,
            childIndex: Int,
            indent: Int
        ): String {
            val indentStr = " ".repeat(indent)
            if (view != null) {
                val childCount = view.childCount
                var children = "None"
                if (childCount > 0)
                    children = (0..<childCount).joinToString(prefix = "\n", separator = "") {
                        val childView = view.getChild(it)
                        nodeInfoTreeToFormattedString(childView, it, indent + 4)
                    }

                val msg = arrayOf(
                    "View(%d):\n".format(childIndex),
                    "    className: %s\n".format(view.className),
                    "    viewIdResourceName: %s\n".format(view.viewIdResourceName),
                    "    text: %s\n".format(view.text),
                    "    children: %s\n".format(children)
                ).joinToString(separator = "") {
                    indentStr + it
                }
                return msg
            } else {
                return indentStr + "View(%d): Error\n".format(childIndex)
            }
        }

        val msg = nodeInfoTreeToFormattedString(view, 0, 0)
        var start = 0
        var n = 0
        while (true) {
            val i = msg.slice(start..<msg.length).indexOf('\n')
            if (i == -1) break
            if (n < 50) {
                n += 1
            } else {
                n = 0
                Log.d(javaClass.name, msg.slice(start..start + i))
                start += i + 1
            }
        }
        Log.d(javaClass.name, msg.slice(start..<msg.length))
    }

    private fun startLauncher() {
        Log.d(javaClass.name, "Starting Launcher")
        if (wakeLock?.isHeld == true) wakeLock?.release()
        removeOverlay()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
    }

    private fun removeOverlay() {
        Log.d(javaClass.name, "Removing overlay if present")
        try {
            val winMgr =
                getSystemService(WINDOW_SERVICE) as WindowManager
            winMgr.removeView(accessibilityWindow)
        } catch (_: IllegalArgumentException) {
        }
    }

    override fun onInterrupt() {
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        when (event?.action) {
            KeyEvent.ACTION_DOWN -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        Log.d(javaClass.name, "VOLUME DOWN button pressed!")
                        return true
                    }

                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        Log.d(javaClass.name, "VOLUME UP button pressed!")
                        return true
                    }

                    KeyEvent.KEYCODE_HOME -> {
                        Log.d(javaClass.name, "HOME button pressed")
                        val root = rootInActiveWindow
                        if (root == null) {
                            Log.e(javaClass.name, "getRootInActiveWindow() returned null")
                            startLauncher()
                        } else {
                            when (root.packageName) {
                                "com.viber.voip" -> {
                                    ViberInterface.tryEndCall(root)
                                    removeOverlay()
                                }

                                else -> {
                                    startLauncher()
                                }
                            }
                        }
                        if (wakeLock?.isHeld == true) wakeLock?.release()
                        try {
                            val dpm =
                                getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                            dpm.lockNow()
                        } catch (_: SecurityException) {}
                        return true
                    }

                    else -> {
                        Log.d(javaClass.name, "Key %X pressed".format(event.keyCode))
                        return super.onKeyEvent(event)
                    }
                }
            }

            KeyEvent.ACTION_UP -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        Log.d(javaClass.name, "BACK button released")

                        return if (limitedMode) true
                        else super.onKeyEvent(event)
                    }

                    KeyEvent.KEYCODE_APP_SWITCH -> {
                        Log.d(
                            javaClass.name,
                            "APP_SWITCH button released"
                        )

                        return if (limitedMode) true
                        else super.onKeyEvent(event)
                    }

                    KeyEvent.KEYCODE_HOME -> {
                        Log.d(
                            javaClass.name,
                            "HOME_BUTTON button released"
                        )
                        if (lastHandledState != State.Home && lastHandledState != State.Answering) {
                            startLauncher()
                        }
                        return true
                    }

                    else -> {
                        Log.d(javaClass.name, "Key %X released".format(event.keyCode))
                        return super.onKeyEvent(event)
                    }
                }
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(disableLimitedModeHandler)
        unregisterReceiver(enableOverlayHandler)
    }
}
