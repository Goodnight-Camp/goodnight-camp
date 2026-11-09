package com.example.pleostest.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.widget.NumberPicker
import android.widget.TextView
import com.example.pleostest.R

class GcTimePickerDialog(
    context: Context,
    private val initialHour: Int,
    private val initialMinute: Int,
    private val onTimeSet: (hour: Int, minute: Int) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_time_picker)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val hourPicker = findViewById<NumberPicker>(R.id.gc_picker_hour)
        val minutePicker = findViewById<NumberPicker>(R.id.gc_picker_minute)

        setupPicker(hourPicker, 0, 23, initialHour)
        setupPicker(minutePicker, 0, 59, initialMinute)

        listOf(hourPicker, minutePicker).forEach { applyPickerStyle(it) }

        findViewById<TextView>(R.id.gc_time_picker_cancel).setOnClickListener { dismiss() }
        findViewById<TextView>(R.id.gc_time_picker_confirm).setOnClickListener {
            onTimeSet(hourPicker.value, minutePicker.value)
            dismiss()
        }
    }

    private fun setupPicker(picker: NumberPicker, min: Int, max: Int, initial: Int) {
        val labels = Array(max - min + 1) { String.format("%02d", it + min) }
        picker.apply {
            minValue = min
            maxValue = max
            displayedValues = labels
            value = initial
            wrapSelectorWheel = true
        }
    }

    private fun applyPickerStyle(picker: NumberPicker) {
        // Text size and color (API 29+; AAOS is always 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            picker.textSize = context.resources.getDimension(R.dimen.gc_text_title_lg)
            picker.setTextColor(context.getColor(R.color.text_primary))
        }

        // Divider color via reflection (best-effort)
        try {
            val dividerColor = context.getColor(R.color.informative_night)
            val dividerField = NumberPicker::class.java.getDeclaredField("mSelectionDivider")
            dividerField.isAccessible = true
            dividerField.set(picker, ColorDrawable(dividerColor))
        } catch (_: Exception) { }
    }
}
