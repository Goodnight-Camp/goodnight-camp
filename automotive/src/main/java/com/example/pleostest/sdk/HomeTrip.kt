package com.example.pleostest.sdk

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import kotlin.math.roundToInt

/** 귀가 예측 대상 장소 */
data class TripPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val poiId: String = "",
    val poiSubId: String? = null,
)

/**
 * 순정 내비 [TripInfo]로 만든 귀가 예측.
 * 내비가 실제 도로 경로·고도·교통을 반영해 계산한 값이다 (내비 화면의 "도착 시 배터리"와 같은 출처).
 */
data class TripEstimate(
    val place: TripPlace,
    val distanceKm: Int,
    val socAtDestPct: Int,
    /** 집까지 필요한 배터리 % = 현재 잔량 − 도착 예상 잔량 */
    val neededPct: Int,
)

/** [NaviManager.requestTripEstimate] 결과. 콜백으로 정확히 1회 전달된다. */
sealed class TripResult {
    data class Estimated(val estimate: TripEstimate) : TripResult()
    /** 목적지 좌표도, 내비 HOME 즐겨찾기도 없음 */
    data object NoHomeBookmark : TripResult()
    /** 내비가 제한 시간 안에 응답하지 않음 */
    data object Timeout : TripResult()
    /** NAVI_ROUTE / NAVI_ROUTE_SEARCH 권한 없음 */
    data object PermissionDenied : TripResult()
    /** 내비가 onError 로 실패를 알렸거나, 응답 값이 비정상이거나, 현재 배터리를 모름 */
    data class Failed(val errorCode: NaviErrorCode?) : TripResult()
}

/** 귀가 예측 요청·응답 변환 (SDK 호출 없는 순수 로직) */
object HomeTrip {

    /** 앱에서 고른 목적지. 좌표를 모르면(0,0) null → HOME 즐겨찾기로 대체 */
    fun placeOf(name: String, lat: Double, lng: Double): TripPlace? =
        if (lat == 0.0 && lng == 0.0) null else TripPlace(name, lat, lng)

    /** 내비 HOME 즐겨찾기. 미등록이면 null */
    fun homePlace(bookmarks: List<BookmarkInfo>): TripPlace? =
        HomeRoute.findHome(bookmarks)?.let {
            TripPlace(HomeRoute.displayName(it), it.latitude, it.longitude, it.poiId, it.poiSubId)
        }

    fun toRequest(place: TripPlace): TripRequest =
        TripRequest(place.lat, place.lng, place.poiId, place.name, place.poiSubId)

    /**
     * [currentPct]가 null(차량 배터리 미수신)이면 null — 앱의 기본값 80%와 내비 실측을 섞지 않는다.
     * 내비 응답이 비정상(거리 0 이하, 잔량 범위 밖)이어도 null.
     */
    fun estimate(place: TripPlace, info: TripInfo, currentPct: Int?): TripEstimate? {
        if (currentPct == null) return null
        if (info.distance <= 0 || info.estimatedSocAtDest !in 0..100) return null
        return TripEstimate(
            place = place,
            distanceKm = (info.distance / 1000.0).roundToInt(),
            socAtDestPct = info.estimatedSocAtDest,
            neededPct = (currentPct - info.estimatedSocAtDest).coerceIn(0, 100),
        )
    }
}
