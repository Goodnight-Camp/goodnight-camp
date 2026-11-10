package com.example.pleostest.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
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

    // ── 귀가 경로 요청 상태 (메인 스레드에서만 접근) ──
    // SDK 리스너는 바인더 스레드에서 호출되므로 mainHandler로 넘겨 직렬화한다.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var homeRouteCallback: ((HomeRouteResult) -> Unit)? = null
    private var homeRouteName: String? = null   // null = 즐겨찾기 대기 중, non-null = 경로 시작 대기 중
    private val homeRouteTimeout = Runnable {
        val name = homeRouteName
        if (name == null) {
            GNCLog.e("[NaviManager] 귀가 경로 — 즐겨찾기 응답 없음 (${BOOKMARK_TIMEOUT_MS}ms)")
            finishHomeRoute(HomeRouteResult.Timeout)
        } else {
            // onRouteStarted는 내비 앱 구현에 따라 오지 않을 수도 있음 → 요청 자체는 성공으로 처리
            GNCLog.i("[NaviManager] 귀가 경로 — onRouteStarted 미수신, 요청 완료로 처리")
            finishHomeRoute(HomeRouteResult.Requested(name))
        }
    }

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
            mainHandler.post { onHomeRouteBookmarks(bookmarkInfo) }
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

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            GNCLog.i("[NaviManager] 경로 안내 시작 — ${routeStartInfo.name}, ${routeStartInfo.distance}m, ${routeStartInfo.duration}s")
            mainHandler.post {
                homeRouteName?.let { finishHomeRoute(HomeRouteResult.Requested(it)) }
            }
        }

        override fun onError(errorCode: NaviErrorCode) {
            GNCLog.e("[NaviManager] 오류: $errorCode")
            mainHandler.post {
                // 경로 요청 이후의 오류만 귀가 경로 실패로 귀속
                if (homeRouteName != null) finishHomeRoute(HomeRouteResult.Failed(errorCode))
            }
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

    // ────────────────────────────────────────────────────────────────
    // 귀가 경로 실행 — 내비 HOME 즐겨찾기로 순정 내비 경로 안내 시작
    // ────────────────────────────────────────────────────────────────

    /**
     * 즐겨찾기 조회 → HOME 항목으로 [NaviHelper.requestRoute] 호출.
     * [onResult]는 메인 스레드에서 정확히 1회, 항상 비동기로(이 함수가 반환된 뒤) 호출된다.
     * 이미 요청 중이면 false 반환(무시).
     * 메인 스레드에서 호출할 것.
     */
    fun requestHomeRoute(onResult: (HomeRouteResult) -> Unit): Boolean {
        if (homeRouteCallback != null) {
            GNCLog.d("[NaviManager] 귀가 경로 요청 이미 진행 중 → 무시")
            return false
        }
        homeRouteCallback = onResult
        homeRouteName = null
        mainHandler.postDelayed(homeRouteTimeout, BOOKMARK_TIMEOUT_MS)
        GNCLog.i("[NaviManager] 귀가 경로 요청 — 즐겨찾기 조회")
        try {
            naviHelper.getBookmarkInfo()
        } catch (e: SecurityException) {
            GNCLog.e("[NaviManager] 즐겨찾기 조회 권한 없음: ${e.message}")
            // 동기 콜백 금지 — 호출부가 반환값(true)을 보고 UI를 바꾸기 전에 결과가 먼저 도착하면 상태가 꼬임
            mainHandler.post { finishHomeRoute(HomeRouteResult.PermissionDenied) }
        }
        return true
    }

    private fun onHomeRouteBookmarks(bookmarks: List<BookmarkInfo>) {
        // 요청 중이 아니거나(placeList 조회 응답), 이미 경로 요청을 보낸 뒤면 무시
        if (homeRouteCallback == null || homeRouteName != null) return

        val routeInfo = HomeRoute.buildRouteInfo(bookmarks)
        if (routeInfo == null) {
            GNCLog.i("[NaviManager] 집(HOME) 즐겨찾기 없음 → 경로 요청 안 함")
            finishHomeRoute(HomeRouteResult.NoHomeBookmark)
            return
        }
        try {
            GNCLog.i("[NaviManager] requestRoute → ${routeInfo.poiName} (${routeInfo.latitude}, ${routeInfo.longitude})")
            naviHelper.requestRoute(routeInfo)
        } catch (e: SecurityException) {
            GNCLog.e("[NaviManager] 경로 요청 권한 없음: ${e.message}")
            finishHomeRoute(HomeRouteResult.PermissionDenied)
            return
        }
        homeRouteName = routeInfo.poiName
        mainHandler.removeCallbacks(homeRouteTimeout)
        mainHandler.postDelayed(homeRouteTimeout, ROUTE_START_GRACE_MS)
    }

    private fun finishHomeRoute(result: HomeRouteResult) {
        val cb = homeRouteCallback ?: return
        mainHandler.removeCallbacks(homeRouteTimeout)
        homeRouteCallback = null
        homeRouteName = null
        GNCLog.i("[NaviManager] 귀가 경로 결과: $result")
        cb(result)
    }

    fun release() {
        mainHandler.removeCallbacks(homeRouteTimeout)
        homeRouteCallback = null
        homeRouteName = null
        onDestination = null
        placeListCallback = null
        naviHelper.removeListener(listener)
        naviHelper.release()
    }

    companion object {
        /** 즐겨찾기 응답 대기 한도 — 내비 서비스 미연결/무응답 대비 */
        private const val BOOKMARK_TIMEOUT_MS = 5_000L
        /** requestRoute 후 onRouteStarted/onError 대기 한도 — 미수신 시 요청 성공으로 처리 */
        private const val ROUTE_START_GRACE_MS = 3_000L
    }
}
