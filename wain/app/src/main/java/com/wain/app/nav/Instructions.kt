package com.wain.app.nav

import com.wain.app.data.routing.Maneuver
import kotlin.math.roundToInt

/** Localised (ar / en / ckb) turn instructions built from Valhalla maneuver types, with English fallback. */
object InstructionFormatter {
    private enum class Kind { START, DEST, DEST_RIGHT, DEST_LEFT, CONTINUE, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN,
        SHARP_LEFT, LEFT, SLIGHT_LEFT, RAMP_STRAIGHT, RAMP_RIGHT, RAMP_LEFT, EXIT_RIGHT, EXIT_LEFT, KEEP_RIGHT,
        KEEP_LEFT, MERGE, ROUNDABOUT, ROUNDABOUT_EXIT, FERRY, UNKNOWN }

    private fun kind(type: Int): Kind = when (type) {
        1, 2, 3 -> Kind.START
        4 -> Kind.DEST
        5 -> Kind.DEST_RIGHT
        6 -> Kind.DEST_LEFT
        7, 8, 22 -> Kind.CONTINUE
        9 -> Kind.SLIGHT_RIGHT
        10 -> Kind.RIGHT
        11 -> Kind.SHARP_RIGHT
        12, 13 -> Kind.UTURN
        14 -> Kind.SHARP_LEFT
        15 -> Kind.LEFT
        16 -> Kind.SLIGHT_LEFT
        17 -> Kind.RAMP_STRAIGHT
        18 -> Kind.RAMP_RIGHT
        19 -> Kind.RAMP_LEFT
        20 -> Kind.EXIT_RIGHT
        21 -> Kind.EXIT_LEFT
        23 -> Kind.KEEP_RIGHT
        24 -> Kind.KEEP_LEFT
        25 -> Kind.MERGE
        26 -> Kind.ROUNDABOUT
        27 -> Kind.ROUNDABOUT_EXIT
        28, 29 -> Kind.FERRY
        else -> Kind.UNKNOWN
    }

    private val en = mapOf(
        Kind.START to "Head out", Kind.DEST to "You have arrived", Kind.DEST_RIGHT to "Your destination is on the right",
        Kind.DEST_LEFT to "Your destination is on the left", Kind.CONTINUE to "Continue straight",
        Kind.SLIGHT_RIGHT to "Bear right", Kind.RIGHT to "Turn right", Kind.SHARP_RIGHT to "Turn sharp right",
        Kind.UTURN to "Make a U-turn", Kind.SHARP_LEFT to "Turn sharp left", Kind.LEFT to "Turn left",
        Kind.SLIGHT_LEFT to "Bear left", Kind.RAMP_STRAIGHT to "Take the ramp", Kind.RAMP_RIGHT to "Take the ramp on the right",
        Kind.RAMP_LEFT to "Take the ramp on the left", Kind.EXIT_RIGHT to "Take the exit on the right",
        Kind.EXIT_LEFT to "Take the exit on the left", Kind.KEEP_RIGHT to "Keep right", Kind.KEEP_LEFT to "Keep left",
        Kind.MERGE to "Merge", Kind.ROUNDABOUT to "Enter the roundabout", Kind.ROUNDABOUT_EXIT to "Exit the roundabout",
        Kind.FERRY to "Take the ferry",
    )
    private val ar = mapOf(
        Kind.START to "ابدأ بالسير", Kind.DEST to "وصلت إلى وجهتك", Kind.DEST_RIGHT to "وجهتك على يمينك",
        Kind.DEST_LEFT to "وجهتك على يسارك", Kind.CONTINUE to "استمر للأمام",
        Kind.SLIGHT_RIGHT to "انعطف قليلاً يميناً", Kind.RIGHT to "انعطف يميناً", Kind.SHARP_RIGHT to "انعطف بحدّة يميناً",
        Kind.UTURN to "استدر للخلف", Kind.SHARP_LEFT to "انعطف بحدّة يساراً", Kind.LEFT to "انعطف يساراً",
        Kind.SLIGHT_LEFT to "انعطف قليلاً يساراً", Kind.RAMP_STRAIGHT to "ادخل المنحدر", Kind.RAMP_RIGHT to "ادخل المنحدر على اليمين",
        Kind.RAMP_LEFT to "ادخل المنحدر على اليسار", Kind.EXIT_RIGHT to "اخرج من المخرج الأيمن",
        Kind.EXIT_LEFT to "اخرج من المخرج الأيسر", Kind.KEEP_RIGHT to "ابقَ على اليمين", Kind.KEEP_LEFT to "ابقَ على اليسار",
        Kind.MERGE to "اندمج في الطريق", Kind.ROUNDABOUT to "ادخل الدوّار", Kind.ROUNDABOUT_EXIT to "اخرج من الدوّار",
        Kind.FERRY to "استخدم العبّارة",
    )
    private val ckb = mapOf(
        Kind.START to "دەستپێبکە", Kind.DEST to "گەیشتیتە مەبەست", Kind.DEST_RIGHT to "مەبەست لای ڕاستە",
        Kind.DEST_LEFT to "مەبەست لای چەپە", Kind.CONTINUE to "بەرەو پێش بڕۆ",
        Kind.SLIGHT_RIGHT to "کەمێک بپێچە بۆ ڕاست", Kind.RIGHT to "بپێچە بۆ ڕاست", Kind.SHARP_RIGHT to "بە توندی بپێچە بۆ ڕاست",
        Kind.UTURN to "بگەڕێوە دواوە", Kind.SHARP_LEFT to "بە توندی بپێچە بۆ چەپ", Kind.LEFT to "بپێچە بۆ چەپ",
        Kind.SLIGHT_LEFT to "کەمێک بپێچە بۆ چەپ", Kind.RAMP_STRAIGHT to "ڕێگای لاوەکی بگرە", Kind.RAMP_RIGHT to "ڕێگای لاوەکی ڕاست بگرە",
        Kind.RAMP_LEFT to "ڕێگای لاوەکی چەپ بگرە", Kind.EXIT_RIGHT to "لە دەرچوونی ڕاست دەربچۆ",
        Kind.EXIT_LEFT to "لە دەرچوونی چەپ دەربچۆ", Kind.KEEP_RIGHT to "لە ڕاست بمێنەوە", Kind.KEEP_LEFT to "لە چەپ بمێنەوە",
        Kind.MERGE to "تێکەڵ ببە", Kind.ROUNDABOUT to "بچۆ ناو فلکە", Kind.ROUNDABOUT_EXIT to "لە فلکە دەربچۆ",
        Kind.FERRY to "گەمی بەکاربهێنە",
    )

    private fun table(lang: String) = when (lang) { "ar" -> ar; "ckb" -> ckb; else -> en }

    fun text(m: Maneuver, lang: String): String {
        val k = kind(m.type)
        val base = table(lang)[k] ?: return m.instruction.ifBlank { table(lang)[Kind.CONTINUE] ?: "" }
        val exit = m.roundaboutExit
        val withExit = if (k == Kind.ROUNDABOUT && exit != null && exit > 0) {
            when (lang) {
                "ar" -> "$base، ثم اخرج من المخرج رقم $exit"
                "ckb" -> "$base، پاشان لە دەرچوونی ژمارە $exit دەربچۆ"
                else -> "$base and take exit $exit"
            }
        } else base
        val street = m.streetNames.firstOrNull()
        val showStreet = street != null && k != Kind.DEST && k != Kind.DEST_LEFT && k != Kind.DEST_RIGHT
        return if (!showStreet) withExit else when (lang) {
            "ar" -> "$withExit إلى $street"
            "ckb" -> "$withExit بۆ $street"
            else -> "$withExit onto $street"
        }
    }

    /** Rotation (degrees clockwise) to apply to an "up" arrow to depict the maneuver. */
    fun arrowRotationDeg(type: Int): Float = when (kind(type)) {
        Kind.SLIGHT_RIGHT, Kind.RAMP_RIGHT, Kind.KEEP_RIGHT, Kind.EXIT_RIGHT -> 35f
        Kind.RIGHT, Kind.DEST_RIGHT -> 90f
        Kind.SHARP_RIGHT -> 135f
        Kind.UTURN -> 180f
        Kind.SHARP_LEFT -> -135f
        Kind.LEFT, Kind.DEST_LEFT -> -90f
        Kind.SLIGHT_LEFT, Kind.RAMP_LEFT, Kind.KEEP_LEFT, Kind.EXIT_LEFT -> -35f
        else -> 0f
    }

    fun spoken(distM: Double?, m: Maneuver, lang: String): String {
        val t = text(m, lang)
        if (distM == null || distM < 60.0) return t
        val d = DistanceFormat.spoken(distM, lang)
        return when (lang) {
            "ar" -> "بعد $d، $t"
            "ckb" -> "دوای $d، $t"
            else -> "In $d, $t"
        }
    }
}

object DistanceFormat {
    private fun roundForDisplay(m: Double): Int = when {
        m < 100.0 -> (m / 10.0).roundToInt() * 10
        else -> (m / 50.0).roundToInt() * 50
    }

    fun short(m: Double, lang: String): String {
        if (m < 1000.0) {
            val v = roundForDisplay(m)
            return if (lang == "en") "$v m" else "$v م"
        }
        val km = String.format(java.util.Locale.US, "%.1f", m / 1000.0)
        return when (lang) { "en" -> "$km km"; "ckb" -> "$km کم"; else -> "$km كم" }
    }

    fun spoken(m: Double, lang: String): String {
        if (m < 1000.0) {
            val v = roundForDisplay(m)
            return when (lang) { "ar" -> "$v متر"; "ckb" -> "$v مەتر"; else -> "$v meters" }
        }
        val km = String.format(java.util.Locale.US, "%.1f", m / 1000.0)
        return when (lang) { "ar" -> "$km كيلومتر"; "ckb" -> "$km کیلۆمەتر"; else -> "$km kilometers" }
    }
}

object DurationFormat {
    fun short(seconds: Double, lang: String): String {
        val minutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
        val h = minutes / 60
        val m = minutes % 60
        return when (lang) {
            "ar" -> if (h == 0) "$m د" else "$h س ${"%02d".format(m)} د"
            "ckb" -> if (h == 0) "$m خولەک" else "$h کاتژمێر ${"%02d".format(m)} خولەک"
            else -> if (h == 0) "$m min" else "$h h ${"%02d".format(m)} min"
        }
    }
}
