package com.example.pleostest

import android.app.Application
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import com.example.pleostest.service.CarDataService
import com.example.pleostest.util.GNCLog

class GoodNightCampApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 차박은 대부분 저녁~새벽에 쓰므로 앱 기본은 밤 팔레트(values-night).
        // 기상 화면만 아침 팔레트(values)로 연다 — WakeActivity 참고. (DESIGN.md: 다크 + 기상 화면만 밝게)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        GNCLog.i("[App] 굿나잇 캠프 시작 — packageName=$packageName")
        GNCLog.i("[App] CarDataService 시작 요청")
        startForegroundService(Intent(this, CarDataService::class.java))
    }
}
