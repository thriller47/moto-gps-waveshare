package com.motogps.android.viewmodel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExplorerWeatherTest {
    @Test fun loopPreservesArrivalTimeAndOrigin() {
        val shape = listOf(45.0 to 7.0, 45.1 to 7.0, 45.0 to 7.0)
        val samples = ExplorerWeather.sample(shape, 3600)
        assertTrue(samples.size in 3..21)
        assertEquals(0L, samples.first().elapsed)
        assertEquals(3600L, samples.last().elapsed)
        assertEquals(samples.first().lat, samples.last().lat, 0.000001)
        assertEquals(samples.first().lon, samples.last().lon, 0.000001)
        assertTrue(samples.last().km > 20)
        assertTrue(samples.zipWithNext().all { (a,b) -> a.elapsed < b.elapsed })
        assertTrue(ExplorerWeather.sample(emptyList(), 100).isEmpty())
    }

    @Test fun precedingHourAndMissingForecastAreHandled() {
        val location = JSONObject("""{"hourly":{"time":[3600,7200],"temperature_2m":[10,12],
            "precipitation_probability":[20,80],"precipitation":[0,3],"wind_speed_10m":[10,20],
            "wind_gusts_10m":[15,30],"wind_direction_10m":[90,180],"weather_code":[0,61]}}""")
        assertEquals(20.0, ExplorerWeather.reading(location,3600)!!.probability,0.0)
        assertEquals(80.0, ExplorerWeather.reading(location,3601)!!.probability,0.0)
        assertNull(ExplorerWeather.reading(location,7201))
        location.getJSONObject("hourly").getJSONArray("wind_gusts_10m").put(1,JSONObject.NULL)
        assertNull(ExplorerWeather.reading(location,3601))
    }

    @Test fun incompleteOrTiedProfilesDoNotGetRecommendation() {
        val s = ExplorerWeather.Sample(45.0,7.0,0.0,0)
        fun p(prob: Double?) = ExplorerWeather.Profile("A", listOf(ExplorerWeather.Point(s,0,
            prob?.let { ExplorerWeather.Reading(10.0,it,0.0,5.0,10.0,90.0,0) })))
        assertEquals(1, ExplorerWeather.leastPrecipitation(listOf(p(70.0),p(10.0))))
        assertNull(ExplorerWeather.leastPrecipitation(listOf(p(10.0),p(10.0))))
        assertNull(ExplorerWeather.leastPrecipitation(listOf(p(10.0),p(null))))
    }
}
