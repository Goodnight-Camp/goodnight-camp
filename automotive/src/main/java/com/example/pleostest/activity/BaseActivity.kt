package com.example.pleostest.activity

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.pleostest.R
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.util.GNCLog

abstract class BaseActivity : AppCompatActivity() {

    private var portraitOverlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GNCLog.i("[${javaClass.simpleName}] onCreate")
    }

    override fun onResume() {
        super.onResume()
        GNCLog.i("[${javaClass.simpleName}] onResume")
    }

    override fun onPause() {
        super.onPause()
        GNCLog.i("[${javaClass.simpleName}] onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        GNCLog.i("[${javaClass.simpleName}] onDestroy")
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        attachPortraitOverlay()
        updatePortraitOverlay(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientation = if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) "PORTRAIT" else "LANDSCAPE"
        GNCLog.i("[${javaClass.simpleName}] onConfigurationChanged — orientation=$orientation")
        updatePortraitOverlay(newConfig)
    }

    private fun attachPortraitOverlay() {
        val decor = window.decorView as FrameLayout
        val overlay = LayoutInflater.from(this)
            .inflate(R.layout.overlay_portrait_warning, decor, false)
        decor.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        portraitOverlay = overlay
        GNCLog.d("[${javaClass.simpleName}] portrait overlay 부착 완료")
    }

    private fun updatePortraitOverlay(config: Configuration) {
        val isPortrait = config.orientation == Configuration.ORIENTATION_PORTRAIT
        portraitOverlay?.visibility = if (isPortrait) View.VISIBLE else View.GONE
        if (isPortrait) GNCLog.w("[${javaClass.simpleName}] Portrait 모드 → 경고 오버레이 표시")
    }

    protected fun refreshHeaderLocation() {
        val name = GlobalVariables.locationName
        if (name == "로딩 중..") {
            GNCLog.d("[${javaClass.simpleName}] refreshHeaderLocation — 위치 미수신, 갱신 생략")
            return
        }
        try {
            findViewById<View>(R.id.gc_header)
                ?.findViewById<TextView>(R.id.gc_context_location)
                ?.text = name
            GNCLog.d("[${javaClass.simpleName}] 헤더 위치 갱신: $name")
        } catch (_: Exception) {}
    }
}
