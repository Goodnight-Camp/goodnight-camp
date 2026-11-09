package com.example.pleostest

import android.app.Application
import android.content.Intent
import com.example.pleostest.service.CarDataService
import com.example.pleostest.util.GNCLog

class GoodNightCampApp : Application() {

    override fun onCreate() {
        super.onCreate()
        GNCLog.i("[App] 굿나잇 캠프 시작 — packageName=$packageName")
        GNCLog.i("[App] CarDataService 시작 요청")
        startForegroundService(Intent(this, CarDataService::class.java))
    }
}
