package io.github.eugene2k.phone_assistant

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DelayedRunnable(private val action: () -> Unit) : Runnable {
    private var shouldProceed = true
    override fun run() {
        if (shouldProceed) action()
    }

    fun cancel() {
        shouldProceed = false
    }

    fun init() {
        shouldProceed = true
    }
}

open class MainActivity : FragmentActivity() {
    private val dateTimeInfo = object {
        private val locale = Locale("ru", "RU")
        private val date = Calendar.getInstance().time

        fun setTime() {
            val timeFormat = SimpleDateFormat("HH:HH", locale)
            val clockView = findViewById<TextView>(R.id.clock)
            clockView.text = timeFormat.format(date)
        }

        fun setDate() {
            val weekdayFormat = SimpleDateFormat("EEEE", locale)
            val weekDayView = findViewById<TextView>(R.id.weekday)
            weekDayView.text = weekdayFormat.format(date).uppercase(Locale.getDefault())

            val dateFormat = SimpleDateFormat("d MMMM", locale)
            val dateView = findViewById<TextView>(R.id.date)
            dateView.text = dateFormat.format(date).uppercase(Locale.getDefault())

            val yearFormat = SimpleDateFormat("y", locale)
            val yearView = findViewById<TextView>(R.id.date_year)
            yearView.text = yearFormat.format(date)
        }
    }
    private var mTimeChanged: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            dateTimeInfo.setTime()
        }
    }
    private var mDateChanged: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            dateTimeInfo.setDate()
        }
    }
    private var mBatteryChanged: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val batteryStatus = findViewById<TextView>(R.id.batteryStatus)
            val batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            batteryStatus.text = getString(R.string.charge_level, batteryLevel)
        }
    }
    private var mBatteryOkay: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val b = findViewById<Button>(R.id.button)
            b.setBackgroundColor(resources.getColor(R.color.green, null))
            b.setText(R.string.make_calls)
            b.isEnabled = true
        }
    }
    private var mBatteryLow: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val b = findViewById<Button>(R.id.button)
            b.setBackgroundColor(resources.getColor(R.color.crimson, null))
            b.setText(R.string.charge_now)
            b.isEnabled = false
        }
    }

    private val escapeSandbox = DelayedRunnable {
        sendBroadcast(Intent(AccessibilityOverride.LIMITED_MODE_DISABLE))
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        startActivity(intent)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(
            DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(
                this,
                AccessibilityOverride.SpecialDeviceAdminReceiver().javaClass
            )
        )
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        }.launch(intent)
        setContentView(R.layout.home)
        val status = findViewById<TextView>(R.id.batteryStatus)
        status.setOnTouchListener { view: View, motionEvent: MotionEvent ->
            if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                escapeSandbox.init()
                view.postDelayed(escapeSandbox, 5000)
            } else if (motionEvent.actionMasked == MotionEvent.ACTION_UP) {
                escapeSandbox.cancel()
            }
            true
        }
        val b = findViewById<Button>(R.id.button)
        b.setOnClickListener {
            val intent = Intent(
                this,
                ContactsListActivity::class.java
            )
            startActivity(intent)
        }

        b.setText(R.string.make_calls)
        dateTimeInfo.setTime()
        dateTimeInfo.setDate()
        registerReceiver(mTimeChanged, IntentFilter(Intent.ACTION_TIME_CHANGED))
        registerReceiver(mDateChanged, IntentFilter(Intent.ACTION_DATE_CHANGED))
        registerReceiver(mBatteryLow, IntentFilter(Intent.ACTION_BATTERY_LOW))
        registerReceiver(mBatteryOkay, IntentFilter(Intent.ACTION_BATTERY_OKAY))
        registerReceiver(mBatteryChanged, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mTimeChanged)
        unregisterReceiver(mDateChanged)
        unregisterReceiver(mBatteryLow)
        unregisterReceiver(mBatteryOkay)
        unregisterReceiver(mBatteryChanged)
    }

    override fun onResume() {
        super.onResume()
    }
}
