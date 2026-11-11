package com.example.pleostest.sdk

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTripTest {

    private val place = TripPlace(name = "서울역 KTX", lat = 37.55, lng = 126.97, poiId = "poi-1", poiSubId = "0")

    private fun tripInfo(distanceM: Int = 22_000, socAtDest: Int = 96, energy: Double = 3.1) =
        TripInfo(distanceM, 3960, socAtDest, energy, TripSummary(emptyList(), emptyList()))

    // ── 대상 장소 ──

    @Test
    fun `목적지 좌표가 있으면 그 좌표가 대상이다`() {
        val p = HomeTrip.placeOf("캠핑장", 37.1, 127.2)!!

        assertEquals("캠핑장", p.name)
        assertEquals(37.1, p.lat, 0.0)
        assertEquals(127.2, p.lng, 0.0)
    }

    @Test
    fun `목적지 좌표가 (0,0)이면 대상 없음`() {
        assertNull(HomeTrip.placeOf("집 · 마포구", 0.0, 0.0))
    }

    @Test
    fun `HOME 즐겨찾기를 대상 장소로 바꾼다`() {
        val home = BookmarkInfo(
            latitude = 37.55, longitude = 126.97, name = "서울역 KTX", poiId = "poi-1",
            poiSubId = null, address = "서울 용산구", category = BookmarkType.HOME,
        )

        val p = HomeTrip.homePlace(listOf(home))!!

        assertEquals(TripPlace("서울역 KTX", 37.55, 126.97, "poi-1", null), p)
    }

    @Test
    fun `등록 안 된 HOME 자리표시자뿐이면 대상 없음`() {
        val placeholder = BookmarkInfo(
            latitude = 0.0, longitude = 0.0, name = "", poiId = "", poiSubId = null,
            address = "", category = BookmarkType.HOME,
        )

        assertNull(HomeTrip.homePlace(listOf(placeholder)))
    }

    @Test
    fun `TripRequest는 대상 장소의 좌표와 POI를 그대로 담는다`() {
        val req = HomeTrip.toRequest(place)

        assertEquals(37.55, req.latitude, 0.0)
        assertEquals(126.97, req.longitude, 0.0)
        assertEquals("poi-1", req.poiId)
        assertEquals("서울역 KTX", req.poiName)
        assertEquals("0", req.poiSubId)
    }

    // ── 예측 변환 ──

    @Test
    fun `집까지 필요 %는 현재 잔량 − 도착 예상 잔량`() {
        // 스크린샷 실측: 현재 100%, 도착 예상 96%, 22km
        val est = HomeTrip.estimate(place, tripInfo(distanceM = 22_000, socAtDest = 96), currentPct = 100)!!

        assertEquals(4, est.neededPct)
        assertEquals(96, est.socAtDestPct)
        assertEquals(22, est.distanceKm)
        assertEquals(place, est.place)
    }

    @Test
    fun `거리는 km 반올림`() {
        assertEquals(23, HomeTrip.estimate(place, tripInfo(distanceM = 22_500), 100)!!.distanceKm)
    }

    @Test
    fun `도착 예상이 현재보다 높으면 필요량 0`() {
        assertEquals(0, HomeTrip.estimate(place, tripInfo(socAtDest = 81), currentPct = 80)!!.neededPct)
    }

    @Test
    fun `현재 배터리를 모르면 예측 불가 (기본값 80%와 섞지 않는다)`() {
        assertNull(HomeTrip.estimate(place, tripInfo(), currentPct = null))
    }

    @Test
    fun `내비가 비정상 값을 주면 예측 불가`() {
        assertNull(HomeTrip.estimate(place, tripInfo(distanceM = 0), 100))
        assertNull(HomeTrip.estimate(place, tripInfo(socAtDest = -1), 100))
        assertNull(HomeTrip.estimate(place, tripInfo(socAtDest = 101), 100))
    }
}
