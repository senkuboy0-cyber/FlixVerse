package com.castletv.settings

import com.castletv.CastleTvProviderPlugin

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/** Castle TV settings, built entirely in code (no XML layout or drawables). */
class SettingsFragment(
    private val plugin: CastleTvProviderPlugin,
    private val sharedPref: SharedPreferences,
) : DialogFragment() {

    private fun dp(v: Int) = Ui.dp(requireContext(), v)

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(requireContext()).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    // Centered dialog (not a bottom sheet)
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val dm = resources.displayMetrics
            val width = minOf((dm.widthPixels * 0.9f).toInt(), (500 * dm.density).toInt())
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.dialogBackground(ctx)
            setPadding(dp(18), dp(20), dp(18), dp(18))
        }

        // Header: titles + save button
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("CASTLE TV", 12f, Color.parseColor("#9A9A9A")).apply { letterSpacing = 0.1f })
        titles.addView(text("Settings", 24f, Color.WHITE, true))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val save = ImageView(ctx).apply {
            setImageDrawable(IconDrawable(IconDrawable.Kind.SAVE))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = "Save & Reload"
            background = Ui.outline(ctx)
            setOnClickListener { askRestart() }
        }
        header.addView(save, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)

        root.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#2A2A2A")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(12), 0, dp(16)) })

        root.addView(text("HOME PAGE", 12f, Color.parseColor("#9A9A9A")).apply {
            letterSpacing = 0.1f
            setPadding(dp(4), 0, 0, dp(8))
        })

        // Manage Sections row
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            background = Ui.settingsItem(ctx)
            setOnClickListener { ManageSectionsDialog(ctx) { askRestart() }.show() }
        }
        row.addView(text("⚙", 20f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = Ui.circle(Color.parseColor("#4A3210"))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text("Manage Sections", 16f, Color.WHITE, true))
        texts.addView(text("Add, hide, rename or reorder home page sections", 12f, Color.parseColor("#AAAAAA")).apply {
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        row.addView(text("›", 22f, Color.parseColor("#888888")).apply { setPadding(dp(8), 0, 0, 0) })
        root.addView(row)

        return ScrollView(ctx).apply { addView(root) }
    }

    private fun askRestart() {
        AlertDialog.Builder(requireContext())
            .setTitle("Save & Reload")
            .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
            .setPositiveButton("Yes") { _, _ ->
                dismiss()
                restartApp()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            context.startActivity(Intent.makeRestartActivityTask(componentName))
            Runtime.getRuntime().exit(0)
        }
    }
}
