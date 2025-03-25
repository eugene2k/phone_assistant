package io.github.eugene2k.phone_assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Objects

class ContactsListActivity : FragmentActivity() {
    internal class Contact(var id: Int, override var label: String) : ListAdapter.Item

    private var mBatteryLowReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            unregisterReceiver(mBatteryChangedReceiver)
            val header = findViewById<TextView>(R.id.header)
            header.text = context.getString(R.string.charge_now)
            header.setBackgroundColor(resources.getColor(R.color.crimson, null))
        }
    }

    private var mBatteryOkayReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val header = findViewById<TextView>(R.id.header)
            header.setBackgroundColor(Color.BLACK)
            registerReceiver(mBatteryChangedReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    var mBatteryChangedReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val header = findViewById<TextView>(R.id.header)
            val chargeLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            header.text = getString(R.string.charge_level, chargeLevel)
        }
    }

    private var mScreenOffReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.contacts)
        val list = findViewById<RecyclerView>(R.id.list)

        val mgr = LinearLayoutManager(this)
        list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        list.layoutManager = mgr

        registerReceiver(mBatteryLowReceiver, IntentFilter(Intent.ACTION_BATTERY_LOW))
        registerReceiver(mBatteryOkayReceiver, IntentFilter(Intent.ACTION_BATTERY_OKAY))
        registerReceiver(mBatteryChangedReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(mScreenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver(mBatteryLowReceiver)
        unregisterReceiver(mBatteryOkayReceiver)
        unregisterReceiver(mBatteryChangedReceiver)
        unregisterReceiver(mScreenOffReceiver)
    }

    override fun onResume() {
        super.onResume()
        val list = findViewById<RecyclerView>(R.id.list)

        val requiredPermissions = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.WAKE_LOCK
        )
        val requestPermissions: Array<String> = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(
                this, it
            ) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (requestPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, requestPermissions, 100)
        } else {
            setContacts(list)
        }
    }

    private fun setContacts(list: RecyclerView) {
        val pManager = packageManager
        val typeString = try {
            arrayOf(
                Pair(
                    "com.viber.voip",
                    "vnd.android.cursor.item/vnd.com.viber.voip.viber_number_call"
                ),
                Pair("com.whatsapp", "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"),
                Pair(
                    "org.telegram.messenger",
                    "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call"
                )
            ).filter {
                try {
                    pManager.getPackageInfo(it.first, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    return@filter false
                }
                return@filter true
            }.map {
                it.second
            }.first()
        } catch (_: NoSuchElementException) {
            ""
        }

        val content = contentResolver

        val projection = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.DISPLAY_NAME,
        )
        val selection = ContactsContract.Data.MIMETYPE + "=?"
        val selectionArgs = arrayOf(typeString)
        val cursor = content.query(
            ContactsContract.Data.CONTENT_URI, projection, selection, selectionArgs, null
        )
        val adapter: ListAdapter<Contact> = object : ListAdapter<Contact>(
            this
        ) {
            override fun onCreateViewHolder(
                parent: ViewGroup, viewType: Int
            ): ViewHolder {
                val l = LinearLayout(mContext)
                val metrics = resources.displayMetrics
                val hP =
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8.0f, metrics).toInt()
                run {
                    val p = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    l.layoutParams = p
                    l.setPadding(0, 30, 0, 30)
                }
                run {
                    val tv = TextView(mContext)
                    tv.textSize = 18f

                    val p = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    p.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    p.weight = 1f
                    p.marginStart = hP
                    p.marginEnd = hP
                    tv.layoutParams = p
                    l.addView(tv)
                }
                run {
                    val b = ImageButton(mContext)
                    b.setBackgroundResource(R.drawable.button_background)
                    b.setImageResource(R.drawable.call_icon)
                    b.scaleType = ImageView.ScaleType.FIT_CENTER

                    val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 10.0f, metrics)
                        .toInt()
                    val p = LinearLayout.LayoutParams(size, size)
                    p.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    p.weight = 0f
                    p.marginEnd = hP
                    p.marginStart = hP
                    b.layoutParams = p
                    l.addView(b)
                }

                return ViewHolder(l)
            }

            override fun onBindViewHolder(
                holder: ViewHolder, position: Int
            ) {
                val layout = holder.view as LinearLayout
                val item = layout.getChildAt(0) as TextView
                item.text = mList[position].label
                val b = layout.getChildAt(1) as ImageButton
                b.setOnClickListener { onAction(mList[position]) }
            }

            override fun onAction(item: Contact) {
                finish()
                var intent = Intent(AccessibilityOverride.OVERLAY_ENABLE)
                val overlayText = getString(R.string.overlay_text)
                intent.putExtra("contact", overlayText.format(item.label))
                sendBroadcast(intent)
                intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_DEFAULT)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .setDataAndType(
                        Uri.withAppendedPath(
                            ContactsContract.Data.CONTENT_URI, item.id.toString()
                        ), typeString
                    )
                startActivity(intent)
            }
        }
        checkNotNull(cursor)
        while (cursor.moveToNext()) {
            try {
                val id = cursor.getInt(0)
                val displayName = Objects.requireNonNull(cursor.getString(1))
                adapter.add(Contact(id, displayName))
            } catch (e: Exception) {
                val msg = StringBuilder(e.toString())
                msg.append("\n")
                for (stackTraceElement in e.stackTrace) {
                    msg.append(stackTraceElement.toString())
                    msg.append("\n")
                }
                Log.e(javaClass.name, msg.toString())
            }
        }
        cursor.close()
        list.adapter = adapter
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setContacts(findViewById(R.id.list))
            } else {
                Toast.makeText(
                    this, "The app was not allowed to read your contact", Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }
}