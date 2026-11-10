package com.example.pleostest.sdk

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.BookmarkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeRouteTest {

    private fun bookmark(
        category: BookmarkType,
        name: String = "북마크",
        lat: Double = 37.0,
        lng: Double = 127.0,
        poiSubId: String? = "0",
    ) = BookmarkInfo(
        latitude = lat,
        longitude = lng,
        displayLatitude = lat + 0.001,
        displayLongitude = lng + 0.001,
        name = name,
        poiId = "poi-$name",
        poiSubId = poiSubId,
        address = "주소-$name",
        category = category,
        poiProvider = "provider",
    )

    @Test
    fun `HOME 즐겨찾기가 있으면 그 항목으로 RouteInfo를 만든다`() {
        val bookmarks = listOf(
            bookmark(BookmarkType.OFFICE, name = "회사"),
            bookmark(BookmarkType.HOME, name = "우리집", lat = 37.56, lng = 126.90),
        )

        val route = HomeRoute.buildRouteInfo(bookmarks)!!

        assertEquals(37.56, route.latitude, 0.0)
        assertEquals(126.90, route.longitude, 0.0)
        assertEquals(37.561, route.displayLatitude!!, 1e-9)
        assertEquals(126.901, route.displayLongitude!!, 1e-9)
        assertEquals("우리집", route.poiName)
        assertEquals("poi-우리집", route.poiId)
        assertEquals("주소-우리집", route.address)
        assertEquals("0", route.poiSubId)
        assertEquals("provider", route.poiProvider)
        assertEquals(RouteOption.RECOMMENDED, route.routeOption)
    }

    @Test
    fun `HOME 즐겨찾기가 없으면 null`() {
        val bookmarks = listOf(
            bookmark(BookmarkType.OFFICE),
            bookmark(BookmarkType.DEFAULT),
        )

        assertNull(HomeRoute.buildRouteInfo(bookmarks))
        assertNull(HomeRoute.buildRouteInfo(emptyList()))
    }

    @Test
    fun `미등록 HOME 자리표시자(좌표 0,0 · 빈 poiId)는 집 없음으로 본다`() {
        // Pleos Connect v2.1.0 순정 내비 실측: 집 미등록 시에도 아래 항목을 보냄
        // BookmarkInfo(longitude=0.0, latitude=0.0, name=, address=, poiId=, poiSubId=null, category=HOME)
        val placeholder = BookmarkInfo(
            latitude = 0.0, longitude = 0.0, name = "", poiId = "", poiSubId = null,
            address = "", category = BookmarkType.HOME,
        )

        assertNull(HomeRoute.buildRouteInfo(listOf(placeholder)))
    }

    @Test
    fun `poiSubId가 null이면 빈 문자열로 채운다`() {
        val route = HomeRoute.buildRouteInfo(listOf(bookmark(BookmarkType.HOME, poiSubId = null)))!!

        assertEquals("", route.poiSubId)
    }

    @Test
    fun `HOME 이름이 비어 있으면 poiName은 '집'`() {
        val route = HomeRoute.buildRouteInfo(listOf(bookmark(BookmarkType.HOME, name = " ")))!!

        assertEquals("집", route.poiName)
    }
}
