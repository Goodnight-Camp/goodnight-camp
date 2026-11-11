package com.example.pleostest.sdk

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.RouteInfo

/**
 * 귀가 경로 요청 결과.
 * [NaviManager.requestHomeRoute] 콜백으로 정확히 1회 전달된다.
 */
sealed class HomeRouteResult {
    /** 순정 내비에 경로 요청을 보냄 (실제 안내 화면 전환은 내비 앱이 담당) */
    data class Requested(val homeName: String) : HomeRouteResult()
    /** 내비에 집(HOME) 즐겨찾기가 등록돼 있지 않음 */
    data object NoHomeBookmark : HomeRouteResult()
    /** 내비 서비스가 제한 시간 안에 응답하지 않음 */
    data object Timeout : HomeRouteResult()
    /** NAVI_ROUTE / NAVI_ROUTE_SEARCH 권한 없음 (SDK가 SecurityException을 던짐) */
    data object PermissionDenied : HomeRouteResult()
    /** 내비가 onError 로 실패를 알림 */
    data class Failed(val errorCode: NaviErrorCode) : HomeRouteResult()
}

/** 즐겨찾기 목록 → 귀가용 [RouteInfo] 변환 (SDK 호출 없는 순수 로직) */
object HomeRoute {

    private const val DEFAULT_HOME_NAME = "집"

    /** 실제 등록된 HOME 즐겨찾기. 없거나 미등록 자리표시자뿐이면 null */
    fun findHome(bookmarks: List<BookmarkInfo>): BookmarkInfo? =
        bookmarks.firstOrNull { it.category == BookmarkType.HOME && it.isRegistered() }

    /** HOME 즐겨찾기 이름. 비어 있으면 "집" */
    fun displayName(home: BookmarkInfo): String =
        home.name.takeIf { it.isNotBlank() } ?: DEFAULT_HOME_NAME

    /** HOME 즐겨찾기가 없거나 미등록 자리표시자뿐이면 null */
    fun buildRouteInfo(bookmarks: List<BookmarkInfo>): RouteInfo? {
        val home = findHome(bookmarks) ?: return null
        return RouteInfo(
            latitude = home.latitude,
            longitude = home.longitude,
            displayLatitude = home.displayLatitude,
            displayLongitude = home.displayLongitude,
            poiName = displayName(home),
            poiId = home.poiId,
            address = home.address,
            poiSubId = home.poiSubId.orEmpty(),
            poiProvider = home.poiProvider,
        )
    }

    /**
     * 순정 내비는 집/회사가 미등록이어도 좌표 (0,0)·빈 poiId인 자리표시자를 보낸다
     * (Pleos Connect v2.1.0 에뮬레이터 실측). 이를 실제 등록 항목과 구분한다.
     */
    private fun BookmarkInfo.isRegistered(): Boolean =
        poiId.isNotBlank() && !(latitude == 0.0 && longitude == 0.0)
}
