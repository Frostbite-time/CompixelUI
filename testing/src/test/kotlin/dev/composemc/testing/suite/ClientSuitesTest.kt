package dev.composemc.testing.suite

import org.junit.jupiter.api.Test
import kotlin.test.*

class ClientSuitesTest {
    @Test fun `acceptance reports pass only after every step in order`() {
        val log = AcceptanceLog()
        assertFailsWith<IllegalStateException> { log.pass(AcceptanceStep.RENDERER) }
        assertFailsWith<IllegalStateException> { log.report("26.3") }
        AcceptanceStep.entries.dropLast(1).forEach { log.pass(it) }
        assertEquals(AcceptanceStep.entries.last(), log.next)
        assertFailsWith<IllegalStateException> { log.report("26.3") }
        assertTrue(log.failure("26.3", IllegalStateException("boom")).startsWith("FAIL 26.3 at STRESS"))
        log.pass(AcceptanceStep.STRESS, "12 cycles")
        val report = log.report("26.3 NeoForge opengl")
        assertTrue(report.startsWith("PASS 26.3 NeoForge opengl\n"))
        assertEquals(AcceptanceStep.entries.size + 1, report.trimEnd().lines().size)
        assertTrue(report.contains("(12 cycles)"))
    }

    @Test fun `benchmark schedule alternates order and must be recorded completely`() {
        val log = BenchmarkLog(samples = 120, repeats = 2)
        assertEquals(BenchmarkPlan.cases, log.schedule.take(BenchmarkPlan.cases.size).map { it.second })
        assertEquals(BenchmarkPlan.cases.reversed(), log.schedule.drop(BenchmarkPlan.cases.size).map { it.second })
        assertFailsWith<IllegalStateException> { log.record(0, BenchmarkPlan.cases[1], emptyMap()) }
        log.schedule.dropLast(1).forEach { (repeat, case) -> log.record(repeat, case, mapOf("name" to case.name)) }
        assertFailsWith<IllegalStateException> { log.report("1.21.1") }
        val (repeat, case) = log.schedule.last()
        log.record(repeat, case, mapOf("name" to case.name))
        assertTrue(log.report("1.21.1").startsWith("PASS 1.21.1\n${log.schedule.size} measured cases"))
    }

    @Test fun `suite identities are fixed`() {
        assertEquals(ClientSuite.ACCEPTANCE, ClientSuite.of("acceptance"))
        assertEquals("composemc-benchmark.txt", ClientSuite.of("benchmark").report)
        assertFailsWith<IllegalArgumentException> { ClientSuite.of("smoke") }
    }

    @Test fun `pixel checks use top-left ARGB`() {
        val width = 40
        val height = 20
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or (it * 97) }
        for (y in 0 until 8) for (x in 0 until 8) pixels[y * width + x] = SuitePixels.MARKER_ARGB
        val capture = ScreenPixels(width, height, pixels)
        SuitePixels.requireMarker(capture, "marker", guiWidth = width / 2)
        val stretched = pixels.copyOf().also { for (y in 0 until 16) for (x in 0 until 16) it[y * width + x] = SuitePixels.MARKER_ARGB }
        assertFailsWith<IllegalStateException> { SuitePixels.requireMarker(ScreenPixels(width, height, stretched), "stretched", guiWidth = width / 2) }
        assertFailsWith<IllegalStateException> { SuitePixels.requireMarker(ScreenPixels(width, height, IntArray(width * height)), "missing", width / 2) }
        assertEquals(SuitePixels.MARKER_ARGB, capture.argb(7, 7))
        assertEquals(4, capture.region(10, 10, 2, 2).size)
        assertFailsWith<IllegalArgumentException> { capture.region(39, 19, 2, 2) }
    }

    @Test fun `preview comparison tolerates one percent of small differences`() {
        val reference = IntArray(1000) { 0xFF804020.toInt() }
        val drifted = reference.copyOf().also { for (index in 0 until 10) it[index] = 0xFF904020.toInt() }
        SuitePixels.requireSameRegion(reference, drifted, "drift")
        drifted[10] = 0xFF904020.toInt()
        assertFailsWith<IllegalStateException> { SuitePixels.requireSameRegion(reference, drifted, "changed") }
        val warm = ScreenPixels(10, 10, IntArray(100) { 0xFF905020.toInt() })
        assertEquals(64, SuitePixels.previewRegion(warm, "warm", 1, 1, 8, 8).size)
        val cold = ScreenPixels(10, 10, IntArray(100) { 0xFF205090.toInt() })
        assertFailsWith<IllegalStateException> { SuitePixels.previewRegion(cold, "cold", 0, 0, 10, 10) }
    }
}
