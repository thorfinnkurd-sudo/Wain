package com.wain.app.ui

object CameraPolicy {
    /** Navigation camera zoom: closer at low speed, further out when driving fast so the road ahead stays visible. */
    fun zoomForSpeedKmh(kmh: Double?): Double = when {
        kmh == null -> 17.0
        kmh < 25.0 -> 17.5
        kmh < 55.0 -> 16.8
        kmh < 85.0 -> 16.0
        else -> 15.3
    }

    fun tiltFor(threeD: Boolean): Double = if (threeD) 60.0 else 0.0
}
