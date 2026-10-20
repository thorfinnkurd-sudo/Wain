package com.wain.app

import com.wain.app.core.geo.LatLon
import com.wain.app.data.reports.RateLimiter
import com.wain.app.data.reports.Report
import com.wain.app.data.reports.ReportDraft
import com.wain.app.data.reports.ReportOrigin
import com.wain.app.data.reports.ReportPolicy
import com.wain.app.data.reports.ReportType
import com.wain.app.data.reports.ReportValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReportsConfigTest {
    private val here = LatLon(33.3152, 44.3661)

    @Test fun validatesPositionAndComment() {
        val bad = ReportPolicy.validate(LatLon(91.0, 0.0), null)
        assertEquals(ReportValidation.Reason.BAD_POSITION, (bad as ReportValidation.Invalid).reason)
        val long = ReportPolicy.validate(here, "x".repeat(201))
        assertEquals(ReportValidation.Reason.COMMENT_TOO_LONG, (long as ReportValidation.Invalid).reason)
        assertEquals("hi", (ReportPolicy.validate(here, "  hi\u0007 ") as ReportValidation.Ok).cleanedComment)
        assertEquals(null, (ReportPolicy.validate(here, "   ") as ReportValidation.Ok).cleanedComment)
    }

    private fun report(type: ReportType, ageMs: Long, p: LatLon, now: Long) =
        Report("1", type, p, null, now - ageMs, ReportPolicy.expiresAt(type, now - ageMs), ReportOrigin.LOCAL_ONLY)

    @Test fun detectsDuplicates() {
        val now = 10_000_000L
        val near = LatLon(33.31524, here.lon)
        val draft = ReportDraft(ReportType.ACCIDENT, here, null)
        assertTrue(ReportPolicy.isDuplicate(draft, listOf(report(ReportType.ACCIDENT, 5 * 60_000L, near, now)), now))
        assertFalse(ReportPolicy.isDuplicate(draft, listOf(report(ReportType.TRAFFIC, 5 * 60_000L, near, now)), now))
        assertFalse(ReportPolicy.isDuplicate(draft, listOf(report(ReportType.ACCIDENT, 20 * 60_000L, near, now)), now))
        assertFalse(ReportPolicy.isDuplicate(draft, listOf(report(ReportType.ACCIDENT, 60_000L, LatLon(33.32, 44.37), now)), now))
    }

    @Test fun reportsExpire() {
        val created = 1_000L
        val r = report(ReportType.TRAFFIC, 0, here, created)
        assertEquals(created + 3_600_000L, r.expiresAtMs)
        assertTrue(ReportPolicy.isActive(r, created + 3_599_999L))
        assertFalse(ReportPolicy.isActive(r, created + 3_600_000L))
    }

    @Test fun rateLimiterSlidesWindow() {
        val l = RateLimiter(max = 2, windowMs = 1000)
        assertTrue(l.tryAcquire(0))
        assertTrue(l.tryAcquire(10))
        assertFalse(l.tryAcquire(20))
        assertTrue(l.tryAcquire(1100))
    }

    @Test fun envExampleContainsNoSecrets() {
        val f = listOf(File("../.env.example"), File(".env.example")).first { it.exists() }
        val secretish = Regex("(KEY|PASSWORD|TOKEN|SECRET)", RegexOption.IGNORE_CASE)
        f.readLines().filter { it.isNotBlank() && !it.trim().startsWith("#") }.forEach { line ->
            val (k, v) = line.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (secretish.containsMatchIn(k)) assertTrue("$k must be empty in .env.example", v.isBlank())
        }
    }
}
