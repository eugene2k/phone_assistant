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


private const val WHATSAPP_CALL = "com.whatsapp.voipcalling.VoipActivityV2"
private const val VIBER_CALL = "com.viber.voip.phone.PhoneFragmentActivity"
//private const val TELEGRAM_CALL = "org.telegram.ui.LaunchActivity"


private val PACKAGE_INSTALLER_ACTIVITIES = arrayOf(
    "com.android.packageinstaller.permission.ui.GrantPermissionsActivity",
    "com.google.android.packageinstaller"
)

class Overlay(context: Context) : androidx.appcompat.widget.AppCompatTextView(context)

enum class State {
    CallInProgress, Answering, Finished, Other
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
            winMgr.addView(
                accessibilityWindow,
                accessibilityWindowParams
            )
        }
    }

    private var lastHandledState = State.Other
    private var expectMainActivity = false
    private var currentActivity = ""
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
        Log.i(javaClass.name, "Service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(javaClass.name, "Service connected")
        val formatted = serviceInfo.toString()
            .replace(", ", ",\n   ")
        Log.i(javaClass.name, "ServiceInfo {\n   %s\n}".format(formatted))
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.i(javaClass.name, "Window State Changed!")
                    Log.i(
                        javaClass.name,
                        "Package: %s".format(event.packageName?.toString() ?: "<null>")
                    )
                    Log.i(
                        javaClass.name,
                        "Class: %s".format(event.className?.toString() ?: "<null>")
                    )
                    Log.i(javaClass.name, "limitedMode=%b".format(limitedMode))
                    if (event.packageName !in PACKAGE_INSTALLER_ACTIVITIES) {
                        var className = event.className.toString()

                        if (className != "android.widget.FrameLayout")
                            currentActivity = event.className.toString()
                        if (event.packageName == packageName) {
                            if (className == MainActivity::class.java.name) {
                                if (wakeLock!!.isHeld)
                                    wakeLock!!.release()
                                limitedMode = true
                                expectMainActivity = false
                                Log.i(javaClass.name, "Limited mode enabled")
                            }
                        } else {
                            if (limitedMode) {
                                when (event.packageName) {
                                    "com.viber.voip" -> {
                                        val source = event.source
                                        printNodeInfoTree(source)
                                        if (currentActivity == "androidx.appcompat.app.AlertDialog") {
                                            expectMainActivity = false
                                            val am =
                                                getSystemService(ACTIVITY_SERVICE) as ActivityManager
                                            am.killBackgroundProcesses("com.viber.voip")
                                            startLauncher()
                                        }
                                        if (source != null) {
                                            var results =
                                                source.findAccessibilityNodeInfosByViewId("com.viber.voip:id/speakerPhone")
                                            if (results.isNotEmpty() && lastHandledState != State.CallInProgress) {
                                                handleCallInProgress(results[0])
                                                lastHandledState = State.CallInProgress
                                                return
                                            }

                                            results =
                                                source.findAccessibilityNodeInfosByViewId("com.viber.voip:id/phone_answer")
                                            if (results.isNotEmpty() && lastHandledState != State.Answering) {
                                                handlePhoneRinging()
                                                lastHandledState = State.Answering
                                                return
                                            }
                                        }
                                    }

                                    "com.whatsapp" -> {
                                        printNodeInfoTree(event.source)
                                    }

                                    else -> {
                                        expectMainActivity = false
                                        startLauncher()
                                    }
                                }
                            }
                        }
                    }
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    Log.i(javaClass.name, "Window Content Changed")
                    if (limitedMode)
                        when (event.packageName) {
                            "com.viber.voip" -> {
                                Log.i(
                                    javaClass.name,
                                    "Class: %s".format(event.className.toString())
                                )
                                if (event.className == "android.widget.Chronometer") return
                                if (currentActivity != VIBER_CALL) return
                                val source = event.source
                                if (source != null) {
                                    printNodeInfoTree(source)

                                    var results =
                                        source.findAccessibilityNodeInfosByViewId("com.viber.voip:id/phone_redial")
                                    if (results.isNotEmpty() && lastHandledState != State.Finished) {
                                        handleCallFinished()
                                        lastHandledState = State.Finished
                                        return
                                    }

                                    results =
                                        source.findAccessibilityNodeInfosByViewId("com.viber.voip:id/speakerPhone")
                                    if (results.isNotEmpty() && lastHandledState != State.CallInProgress) {
                                        handleCallInProgress(results[0])
                                        lastHandledState = State.CallInProgress
                                        return
                                    }
                                }
                            }
                        }
                }

                else -> {
                    Log.i(
                        javaClass.name,
                        "Unrecognized event type: %x".format(event.eventType)
                    )
                }
            }
        }
    }

    private fun handleCallFinished() {
        if (expectMainActivity) {
            expectMainActivity = false
            startLauncher()
        }
    }

    private fun handleCallInProgress(
        speakerButton: AccessibilityNodeInfo
    ) {
        speakerButton.performAction(
            AccessibilityNodeInfo.ACTION_CLICK
        )

        val accessibilityWindow = accessibilityWindow
        if (accessibilityWindow != null) {
            var buttonBounds = Rect()
            speakerButton.getBoundsInScreen(buttonBounds)
            accessibilityWindow.text = ""
            accessibilityWindow.invalidate()
            val accessibilityWindowParams =
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    buttonBounds.height(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE
                )
            // NOTE: window placement is screwed up smh, so this is hardcoded
            accessibilityWindowParams.y = 500
            val winMgr =
                getSystemService(WINDOW_SERVICE) as WindowManager
            if (!accessibilityWindow.isAttachedToWindow)
                winMgr.addView(accessibilityWindow, accessibilityWindowParams)
            else {
                winMgr.updateViewLayout(
                    accessibilityWindow,
                    accessibilityWindowParams
                )
            }
            if (!wakeLock!!.isHeld) wakeLock!!.acquire(120 * 60 * 1000L /*120 minutes*/)

            expectMainActivity = true
        }

    }

    private fun handlePhoneRinging() {
        // Call needs answering
        removeOverlay()
        // NOTE: There are three possible outcomes after this state:
        // 1. the user ignores
        // 2. the user picks up
        // 3. the user declines
        // in cases 1 or 3, the user should be returned to the home screen
        // otherwise nothing should be done.
        // Since the check for the main activity doesn't happen when handling
        // PhoneFragmentActivity, and in the TYPE_WINDOW_CONTENT_CHANGED event handler it is
        // only checked in the redialing case expectMainActivity=true is a viable solution
        expectMainActivity = true
    }

    private fun printNodeInfoTree(view: AccessibilityNodeInfo?) {
        fun nodeInfoTreeToFormattedString(
            view: AccessibilityNodeInfo?,
            childIndex: Int,
            indent: Int
        ): String {
            var indentStr = " ".repeat(indent)
            if (view != null) {
                val childCount = view.childCount
                var children = "None"
                if (childCount > 0)
                    children = (0..<childCount).joinToString(prefix = "\n", separator = "") {
                        var childView = view.getChild(it)
                        if (childView == null) {
                            Log.e(javaClass.name, "it = %d".format(it))
                            Log.e(javaClass.name, "childCount = %d".format(childCount))
                        }
                        nodeInfoTreeToFormattedString(childView, it, indent + 4)
                    }

                var msg = arrayOf(
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

        var root = view
        val msg = nodeInfoTreeToFormattedString(root, 0, 0)
        var start = 0
        var n = 0
        while (true) {
            val i = msg.slice(start..<msg.length).indexOf('\n')
            if (i == -1) break
            if (n < 50) {
                n += 1
            } else {
                n = 0
                Log.i(javaClass.name, msg.slice(start..start + i))
                start += i + 1
            }
        }
        Log.i(javaClass.name, msg.slice(start..<msg.length))
    }

    private fun startLauncher() {
        removeOverlay()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
    }

    private fun removeOverlay() {
        Log.i(javaClass.name, "Removing overlay")
        if (accessibilityWindow?.windowId != null) {
            val winMgr =
                getSystemService(WINDOW_SERVICE) as WindowManager
            winMgr.removeView(accessibilityWindow)
        }
    }

    override fun onInterrupt() {
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        when (event?.action) {
            KeyEvent.ACTION_DOWN -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        Log.i(javaClass.name, "VOLUME DOWN button pressed!")
                        return true
                    }

                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        Log.i(javaClass.name, "VOLUME UP button pressed!")
                        return true
                    }

                    KeyEvent.KEYCODE_HOME -> {
                        Log.i(javaClass.name, "HOME button pressed")
                        Log.i(javaClass.name, currentActivity)
                        if (event.isLongPress) {
                            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                            am.killBackgroundProcesses("com.viber.voip")
                            am.killBackgroundProcesses("com.whatsapp")
                            Log.i(javaClass.name, "Restarting calling apps")
                        }
                        if (limitedMode) {
                            val root = rootInActiveWindow
                            when (currentActivity) {
                                VIBER_CALL -> {
//                                    val topWindow =
//                                        windows.find { (it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) and (it.javaClass.name == VIBER_CALL) }
//                                    val root = topWindow?.root
                                    printNodeInfoTree(root)
                                    val result =
                                        root?.findAccessibilityNodeInfosByViewId("com.viber.voip:id/leaveConference")
                                    if (result?.isNotEmpty() == true) {
                                        val endCallButton = result[0]
                                        endCallButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    }
                                    val dpm =
                                        getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                                    dpm.lockNow()
                                }

                                WHATSAPP_CALL -> {
//                                    val endCallButton = root?.getChild(6)
//                                    endCallButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//                                    val dpm =
//                                        getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
//                                    dpm.lockNow()
                                }

                                MainActivity::class.java.name -> {
//                                    printNodeInfoTree(root)
//                                    removeOverlay()
                                    val dpm =
                                        getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                                    dpm.lockNow()
                                }

                                else -> {
                                    startLauncher()
                                }
                            }
                        } else {
                            startLauncher()
                        }
                        return true
                    }

                    KeyEvent.ACTION_UP -> {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_BACK -> {
                                Log.i(javaClass.name, "BACK button pressed")

                                return if (limitedMode) true
                                else super.onKeyEvent(event)
                            }

                            KeyEvent.KEYCODE_APP_SWITCH -> {
                                Log.i(
                                    javaClass.name,
                                    "APP_SWITCH button pressed".format(event.keyCode)
                                )

                                return if (limitedMode) true
                                else super.onKeyEvent(event)
                            }

                            else -> {
                                Log.i(javaClass.name, "Key %X pressed".format(event.keyCode))
                                return super.onKeyEvent(event)
                            }
                        }
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