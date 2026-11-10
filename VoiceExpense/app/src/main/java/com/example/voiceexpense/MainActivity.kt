package com.example.voiceexpense

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: ExpenseStore
    private lateinit var toggle: Button
    private lateinit var folderInfo: TextView
    private lateinit var summary: TextView
    private lateinit var list: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ExpenseStore(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        fun add(v: android.view.View) = col.addView(
            v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        )

        add(TextView(this).apply {
            text = "Shake the phone 3 times, feel the buzz, then say:\n\"spent 200 on lunch\"  or  \"500 petrol category travel\""
            textSize = 15f
        })

        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        add(toggle)

        add(Button(this).apply {
            text = "Choose laptop-sync folder"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                ), 42)
            }
        })
        folderInfo = TextView(this).apply { textSize = 12f }
        add(folderInfo)

        val typed = EditText(this).apply {
            hint = "Type to test: spent 120 on tea"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        add(typed)
        add(Button(this).apply {
            text = "Add typed expense"
            setOnClickListener {
                val r = ExpenseParser.parse(typed.text.toString())
                if (r == null) {
                    Toast.makeText(context, "No amount found", Toast.LENGTH_SHORT).show()
                } else {
                    Thread {
                        store.add(Expense(System.currentTimeMillis(), r.amount, r.category, r.note))
                        runOnUiThread { typed.setText(""); refresh() }
                    }.start()
                }
            }
        })

        summary = TextView(this).apply { textSize = 16f }
        add(summary)
        list = TextView(this).apply { textSize = 13f }
        add(list)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun onToggle() {
        if (ShakeService.running) {
            stopService(Intent(this, ShakeService::class.java))
            toggle.postDelayed({ refresh() }, 300)
            return
        }
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else startListeningService()
    }

    private fun startListeningService() {
        startForegroundService(Intent(this, ShakeService::class.java))
        toggle.postDelayed({ refresh() }, 300)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListeningService()
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 42 && resultCode == RESULT_OK && uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            store.setTree(uri)
            Thread { store.sync(); runOnUiThread { refresh() } }.start()
        }
    }

    private fun refresh() {
        toggle.text = if (ShakeService.running) "Stop shake listening" else "Start shake listening"
        folderInfo.text = store.treeUri()?.let { "Copying to: ${it.lastPathSegment}" }
            ?: "No folder chosen. Files are in Android/data/com.example.voiceexpense/files/ExpenseVoice"

        val all = store.load().sortedByDescending { it.time }
        val monthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val monthTotal = all.filter { it.time >= monthStart }.sumOf { it.amount }
        summary.text = "This month: ₹%.0f".format(monthTotal)

        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        list.text = all.take(15).joinToString("\n") {
            "${fmt.format(Date(it.time))}   ₹${"%.0f".format(it.amount)}   ${it.category} - ${it.note}"
        }.ifEmpty { "No expenses yet." }
    }
}
