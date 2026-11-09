package com.example.pleostest.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.pleostest.R
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.sdk.NaviPlace

class DestinationPickerDialog(
    context: Context,
    private val places: List<NaviPlace>,
    private val onSelected: (name: String, distanceKm: Int) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_destination_picker)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val list = findViewById<LinearLayout>(R.id.gc_place_list)
        var lastSection = ""

        places.forEach { place ->
            // 섹션 헤더
            if (place.section != lastSection) {
                lastSection = place.section
                val header = TextView(context).apply {
                    text = place.section
                    setTextAppearance(R.style.TextAppearance_GC_Caption)
                    setTextColor(context.getColor(R.color.text_tertiary))
                    setPadding(0, resources.getDimensionPixelSize(R.dimen.gc_space_16), 0,
                        resources.getDimensionPixelSize(R.dimen.gc_space_8))
                }
                list.addView(header)
            }

            // 항목 행
            val row = layoutInflater.inflate(R.layout.item_destination_result, list, false)
            row.findViewById<TextView>(R.id.gc_result_name).text = place.label
            row.findViewById<TextView>(R.id.gc_result_address).apply {
                text = place.address
                visibility = if (place.address.isNotBlank()) View.VISIBLE else View.GONE
            }
            row.setOnClickListener { selectPlace(place) }
            list.addView(row)
        }

        if (places.isEmpty()) {
            val empty = TextView(context).apply {
                text = "최근 목적지가 없어요"
                setTextAppearance(R.style.TextAppearance_GC_LabelLg)
                gravity = android.view.Gravity.CENTER
                setPadding(0, resources.getDimensionPixelSize(R.dimen.gc_space_40), 0,
                    resources.getDimensionPixelSize(R.dimen.gc_space_40))
            }
            list.addView(empty)
        }

        findViewById<TextView>(R.id.gc_picker_cancel).setOnClickListener { dismiss() }
    }

    private fun selectPlace(place: NaviPlace) {
        val distanceKm = if (GlobalVariables.currentLat != 0.0 && GlobalVariables.currentLng != 0.0) {
            val dist = FloatArray(1)
            Location.distanceBetween(
                GlobalVariables.currentLat, GlobalVariables.currentLng,
                place.lat, place.lng, dist
            )
            Math.round(dist[0] / 1000.0 * 1.3).toInt()
        } else 0

        onSelected(place.label, distanceKm)
        dismiss()
    }
}
