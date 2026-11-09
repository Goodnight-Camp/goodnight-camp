package com.example.pleostest.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.pleostest.R
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.util.GNCLog
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PlaceResult(
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double
)

class DestinationSearchDialog(
    context: Context,
    private val onPlaceSelected: (name: String, distanceKm: Int) -> Unit
) : Dialog(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_destination_search)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            )
            val attrs = attributes
            attrs.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            attrs.y = 60
            attributes = attrs
        }

        val input = findViewById<EditText>(R.id.gc_search_input)
        val progress = findViewById<ProgressBar>(R.id.gc_search_progress)
        val results = findViewById<LinearLayout>(R.id.gc_search_results)

        input.requestFocus()

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: return
                searchRunnable?.let { mainHandler.removeCallbacks(it) }
                if (query.length < 2) {
                    results.visibility = View.GONE
                    results.removeAllViews()
                    return
                }
                searchRunnable = Runnable { search(query, progress, results) }
                mainHandler.postDelayed(searchRunnable!!, 400)
            }
        })

        input.setOnEditorActionListener { _, _, _ ->
            val query = input.text.toString().trim()
            if (query.length >= 2) search(query, progress, results)
            true
        }

        findViewById<TextView>(R.id.gc_search_cancel).setOnClickListener { dismiss() }
    }

    private fun search(query: String, progress: ProgressBar, container: LinearLayout) {
        progress.visibility = View.VISIBLE
        container.visibility = View.GONE
        container.removeAllViews()

        Thread {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&countrycodes=kr&limit=5&addressdetails=1")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "GoodNightCamp/1.0 (Android Automotive)")
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val body = conn.inputStream.bufferedReader().readText()
                val places = parseResults(JSONArray(body))
                GNCLog.i("[DestinationSearch] \"$query\" → ${places.size}개 결과")
                mainHandler.post { showResults(places, container, progress) }
            } catch (e: Exception) {
                GNCLog.e("[DestinationSearch] 검색 오류: ${e.message}")
                mainHandler.post { progress.visibility = View.GONE }
            }
        }.start()
    }

    private fun parseResults(array: JSONArray): List<PlaceResult> {
        val list = mutableListOf<PlaceResult>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val addr = item.optJSONObject("address")

            // display_name 첫 토큰이 항상 실제 장소명 (가천대학교, 스타벅스 등)
            val name = item.optString("display_name")
                .split(",").first().trim()
                .ifEmpty { "알 수 없는 장소" }

            val displayAddr = buildString {
                addr?.optString("state")?.ifEmpty { null }?.let { append(it) }
                addr?.optString("city")?.ifEmpty { null }?.let {
                    if (isNotEmpty()) append(" "); append(it)
                }
                addr?.optString("city_district")?.ifEmpty { null }?.let {
                    if (isNotEmpty()) append(" "); append(it)
                }
                addr?.optString("suburb")?.ifEmpty { null }?.let {
                    if (isNotEmpty()) append(" "); append(it)
                }
            }.ifEmpty {
                item.optString("display_name").split(",")
                    .drop(1).take(3).joinToString(" ").trim()
            }

            list.add(PlaceResult(
                name = name,
                address = displayAddr,
                lat = item.optDouble("lat"),
                lng = item.optDouble("lon")
            ))
        }
        return list
    }

    private fun showResults(places: List<PlaceResult>, container: LinearLayout, progress: ProgressBar) {
        progress.visibility = View.GONE
        if (places.isEmpty()) return

        container.removeAllViews()
        places.forEach { place ->
            val row = layoutInflater.inflate(R.layout.item_destination_result, container, false)
            row.findViewById<TextView>(R.id.gc_result_name).text = place.name
            row.findViewById<TextView>(R.id.gc_result_address).text = place.address
            row.setOnClickListener { selectPlace(place) }
            container.addView(row)
        }
        container.visibility = View.VISIBLE
    }

    private fun selectPlace(place: PlaceResult) {
        val distanceKm = if (GlobalVariables.currentLat != 0.0 && GlobalVariables.currentLng != 0.0) {
            val dist = FloatArray(1)
            Location.distanceBetween(
                GlobalVariables.currentLat, GlobalVariables.currentLng,
                place.lat, place.lng, dist
            )
            // 직선거리 × 1.3 도로 보정
            Math.round(dist[0] / 1000.0 * 1.3).toInt()
        } else 0

        GNCLog.i("[DestinationSearch] 선택: ${place.name}, 거리: ${distanceKm}km")
        onPlaceSelected(place.name, distanceKm)
        dismiss()
    }
}
