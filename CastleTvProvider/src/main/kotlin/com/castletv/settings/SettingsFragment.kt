package com.castletv.settings

import com.castletv.CastleTvProviderPlugin

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class SettingsFragment(
    private val plugin: CastleTvProviderPlugin,
    private val sharedPref: SharedPreferences,
) : DialogFragment() {

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", "com.castletv")
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", "com.castletv")
        if (id == 0) throw Exception("View ID $name not found.")
        return this.findViewById(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", "com.castletv")
        this.background = res.getDrawable(outlineId, null)
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
            val maxDialogWidth = (500 * dm.density).toInt()
            val width = minOf((dm.widthPixels * 0.9f).toInt(), maxDialogWidth)
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val layoutId = res.getIdentifier("fragment_castle_settings", "layout", "com.castletv")
        val layout = res.getLayout(layoutId)
        val view = inflater.inflate(layout, container, false)

        val drawableId = res.getIdentifier("dialog_background", "drawable", "com.castletv")
        if (drawableId != 0) view.background = res.getDrawable(drawableId, null)

        val saveIcon: ImageView = view.findView("saveIcon")
        val sectionsIcon: ImageView = view.findView("sectionsIcon")
        val sectionsRow: View = view.findView("sectionsRow")

        saveIcon.setImageDrawable(getDrawable("save_icon"))
        sectionsIcon.setImageDrawable(getDrawable("settings_icon"))
        sectionsIcon.background = getDrawable("icon_circle_brown")
        sectionsRow.background = getDrawable("settings_item_background")
        saveIcon.makeTvCompatible()

        sectionsRow.setOnClickListener {
            ManageSectionsDialog(requireContext(), res) { askRestart() }.show()
        }

        saveIcon.setOnClickListener { askRestart() }

        return view
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
