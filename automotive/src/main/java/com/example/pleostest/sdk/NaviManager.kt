package com.example.pleostest.sdk

import android.content.Context
import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.example.pleostest.util.GNCLog

data class NaviPlace(
    val label: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val section: String
)

class NaviManager(context: Context) {

    private val naviHelper = NaviHelper(context)

    var onDestination: ((name: String, distanceKm: Int) -> Unit)? = null

    @Volatile private var pendingBookmarks: List<NaviPlace>? = null
    @Volatile private var pendingRecent: List<NaviPlace>? = null
    private var placeListCallback: ((List<NaviPlace>) -> Unit)? = null

    private val listener = object : NaviHelperEventListener {

        override fun onDestinationChanged() {
            GNCLog.i("[NaviManager] 목적지 변경됨 → 정보 요청")
            naviHelper.getDestinationInfo()
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val name = destinationInfo.name.takeIf { it.isNotBlank() } ?: return
            val km = Math.round(destinationInfo.remainDistance / 1000.0).toInt()
            GNCLog.i("[NaviManager] 목적지: $name | 거리: ${destinationInfo.remainDistance}m → ${km}km")
            onDestination?.invoke(name, km)
        }

        override fun onBookmarkInfo(bookmarkInfo: List<BookmarkInfo>) {
            GNCLog.i("[NaviManager] 즐겨찾기 ${bookmarkInfo.size}개")
            pendingBookmarks = bookmarkInfo.mapNotNull { b ->
                val label = when (b.category) {
                    BookmarkType.HOME -> "집"
                    BookmarkType.OFFICE -> "직장"
                    else -> b.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                }
                NaviPlace(label, b.address.orEmpty(), b.latitude, b.longitude, "즐겨찾기")
            }
            tryDeliverPlaces()
        }

        override fun onRecentDestinationInfo(recentDestinationInfo: List<RecentDestinationInfo>) {
            GNCLog.i("[NaviManager] 최근 목적지 ${recentDestinationInfo.size}개")
            pendingRecent = recentDestinationInfo.mapNotNull { r ->
                val label = r.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NaviPlace(label, r.address.orEmpty(), r.latitude, r.longitude, "최근 목적지")
            }
            tryDeliverPlaces()
        }

        override fun onError(errorCode: NaviErrorCode) {
            GNCLog.e("[NaviManager] 오류: $errorCode")
        }
    }

    fun initialize() {
        naviHelper.initialize(
            { GNCLog.i("[NaviManager] 초기화 완료") },
            { e -> GNCLog.e("[NaviManager] 초기화 오류: ${e.message}") }
        )
        naviHelper.addListener(listener)
    }

    fun requestPlaceList(onResult: (List<NaviPlace>) -> Unit) {
        pendingBookmarks = null
        pendingRecent = null
        placeListCallback = onResult
        naviHelper.getBookmarkInfo()
        naviHelper.getRecentDestinationInfo()
    }

    private fun tryDeliverPlaces() {
        val bookmarks = pendingBookmarks ?: return
        val recent = pendingRecent ?: return
        val cb = placeListCallback ?: return
        pendingBookmarks = null
        pendingRecent = null
        placeListCallback = null
        val combined = bookmarks + recent
        GNCLog.i("[NaviManager] 장소 목록 전달 — 즐겨찾기=${bookmarks.size}개, 최근=${recent.size}개, 합계=${combined.size}개")
        cb(combined)
    }

    fun release() {
        onDestination = null
        placeListCallback = null
        naviHelper.removeListener(listener)
        naviHelper.release()
    }
}
