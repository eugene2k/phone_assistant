package io.github.eugene2k.phone_assistant

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.edit


class AppSettingsActivity : Activity() {
    internal class ChosenApp(override var label: String, var cursorType: String) :
        ListAdapter.Item

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.contacts)
        val list = findViewById<RecyclerView>(R.id.list)
        val pManager = packageManager

        val appsList: ListAdapter<ChosenApp> =
            object : ListAdapter<ChosenApp>(
                this
            ) {
                override fun onCreateViewHolder(
                    parent: ViewGroup,
                    viewType: Int
                ): ViewHolder {
                    val tv = TextView(mContext)
                    tv.setPadding(30, 30, 30, 30)
                    tv.textSize = 20f
                    return ViewHolder(tv)
                }

                override fun onBindViewHolder(
                    holder: ViewHolder,
                    position: Int
                ) {
                    val item = holder.view as TextView
                    item.text = mList[position].label
                    item.setOnClickListener { onAction(mList[position]) }
                }

                override fun onAction(item: ChosenApp) {
                    val prefs = getString(R.string.preferences_file)
                    applicationContext.getSharedPreferences(prefs, MODE_PRIVATE).edit {
                        putString("typeString", item.cursorType)
                    }
                    finish()
                }
            }
        val allApps: List<ResolveInfo>
        run {
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            allApps = pManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        var typeString: String
        for (ri in allApps) {
            typeString = when (ri.activityInfo.packageName) {
                "com.viber.voip" -> "vnd.android.cursor.item/vnd.com.viber.voip.viber_number_call"
                "com.whatsapp" -> "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
                "org.telegram.messenger" -> "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call"
                else -> continue
            }
            val app = ChosenApp(ri.loadLabel(pManager).toString(), typeString)
            appsList.add(app)
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = appsList
    }
}