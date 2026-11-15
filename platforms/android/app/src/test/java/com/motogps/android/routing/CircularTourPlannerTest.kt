package com.motogps.android.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

class CircularTourPlannerTest {
    private fun meters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dy = (b.first - a.first) * 111_195.0
        val dx = (b.second - a.second) * 111_195.0 *
            cos(Math.toRadians((a.first + b.first) / 2.0))
        return hypot(dx, dy)
    }

    @Test fun createsThreeOrderedStopsAroundStart() {
        val start = 44.39 to 7.55
        val stops = CircularTourPlanner.generate(start.first, start.second, 100)
        assertEquals(3, stops.size)
        assertTrue(stops.all { it.first in -90.0..90.0 && it.second in -180.0..180.0 })
        assertTrue(stops.all { meters(start, it) > 15_000.0 })
        val straightLoopMeters = (listOf(start) + stops + start)
            .zipWithNext().sumOf { (a, b) -> meters(a, b) }
        assertEquals(100_000.0, straightLoopMeters, 3_500.0)
    }

    @Test fun directionRotatesTheLoop() {
        val north = CircularTourPlanner.generate(45.0, 9.0, 80, 0)
        val east = CircularTourPlanner.generate(45.0, 9.0, 80, 90)
        assertTrue(north[1].first > 45.0)
        assertTrue(east[1].second > 9.0)
        assertTrue(abs(north[1].first - east[1].first) > 0.01)
    }

    @Test fun validatesInputs() {
        try {
            CircularTourPlanner.generate(45.0, 9.0, 10)
            throw AssertionError("Distanza invalida accettata")
        } catch (_: IllegalArgumentException) {}
        try {
            CircularTourPlanner.generate(Double.NaN, 9.0, 100)
            throw AssertionError("Coordinate invalide accettate")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun loopSupportsOrderedValhallaBreakStops() {
        val start = 45.0 to 9.0
        val stops = CircularTourPlanner.generate(start.first, start.second, 100)
        val locations = ValhallaRouteLocations.build(
            start.first, start.second, start.first, start.second, stops)
        assertEquals(5, locations.length())
        assertTrue((0 until locations.length()).all {
            locations.getJSONObject(it).getString("type") == "break"
        })
        assertEquals(locations.getJSONObject(0).getDouble("lat"),
            locations.getJSONObject(4).getDouble("lat"), 1e-8)
        assertEquals(locations.getJSONObject(0).getDouble("lon"),
            locations.getJSONObject(4).getDouble("lon"), 1e-8)
    }
}
