package io.github.jeanpedrogl.dronebridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcRegistryTest {
    // Tasks scheduled "later" wait here until the test fires them, like a main-thread Handler.
    private class Clock {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delay: Long, task: () -> Unit) { tasks += delay to task }
        /** Runs every task due within [delay] ms, in order, including ones they schedule. */
        fun advance(delay: Long) {
            while (true) {
                val due = tasks.filter { it.first <= delay }.minByOrNull { it.first } ?: return
                tasks.remove(due)
                due.second()
            }
        }
    }

    private val clock = Clock()
    private val registry = RpcRegistry(clock::schedule, timeoutMs = 10_000)
    private val sent = mutableListOf<JSONObject>()

    private fun call(text: String) = registry.handle(text) { sent += it }

    @Test
    fun runsCommandAndEchoesIdAndResult() {
        registry.register(RpcCommand("t.echo", "echo") { args, reply -> reply.ok(args.getInt("n") + 1) })
        call("""{"id": 7, "cmd": "t.echo", "args": {"n": 41}}""")
        clock.advance(0)
        assertEquals(1, sent.size)
        assertEquals(7, sent[0].getInt("id"))
        assertTrue(sent[0].getBoolean("ok"))
        assertEquals(42, sent[0].getInt("result"))
    }

    @Test
    fun unknownCommandAndBadJsonFail() {
        call("""{"id": 1, "cmd": "nope"}""")
        call("not json")
        clock.advance(0)
        assertEquals(2, sent.size)
        assertTrue(sent.none { it.getBoolean("ok") })
        assertTrue(sent.any { it.optString("error").contains("nope") })
        assertTrue(sent.any { it.optString("error") == "JSON inválido" })
    }

    @Test
    fun confirmIsRequiredWhenDeclared() {
        var ran = false
        registry.register(RpcCommand("t.danger", "d", requiresConfirm = true) { _, reply -> ran = true; reply.ok() })
        call("""{"id": 1, "cmd": "t.danger", "args": {}}""")
        clock.advance(0)
        assertFalse(ran)
        assertFalse(sent[0].getBoolean("ok"))
        call("""{"id": 2, "cmd": "t.danger", "args": {"confirm": true}}""")
        clock.advance(0)
        assertTrue(ran)
        assertTrue(sent[1].getBoolean("ok"))
    }

    @Test
    fun exceptionInCommandBecomesErrorResponse() {
        registry.register(RpcCommand("t.boom", "b") { _, _ -> throw IllegalArgumentException("argumento ruim") })
        call("""{"id": 1, "cmd": "t.boom"}""")
        clock.advance(0)
        assertEquals("argumento ruim", sent[0].getString("error"))
    }

    @Test
    fun silentCommandTimesOutAndLateReplyIsIgnored() {
        var late: RpcReply? = null
        registry.register(RpcCommand("t.slow", "s") { _, reply -> late = reply })
        call("""{"id": 1, "cmd": "t.slow"}""")
        clock.advance(0)
        assertTrue(sent.isEmpty())
        clock.advance(10_000)
        assertEquals(1, sent.size)
        assertFalse(sent[0].getBoolean("ok"))
        late!!.ok("tarde demais")
        clock.advance(0)
        assertEquals(1, sent.size)
    }

    @Test
    fun duplicateRegistrationIsRejected() {
        registry.register(RpcCommand("t.a", "a") { _, r -> r.ok() })
        val error = runCatching { registry.register(RpcCommand("t.a", "a") { _, r -> r.ok() }) }
        assertTrue(error.isFailure)
    }

    @Test
    fun commandsListDescribesEveryCommand() {
        registry.register(RpcCommand("t.x", "faz x", args = "n:int", requiresConfirm = true) { _, r -> r.ok() })
        call("""{"id": 1, "cmd": "system.commands"}""")
        clock.advance(0)
        val list = sent[0].getJSONArray("result")
        val names = (0 until list.length()).map { list.getJSONObject(it).getString("cmd") }
        assertTrue(names.containsAll(listOf("system.ping", "system.commands", "system.probe", "t.x")))
    }

    @Test
    fun probeRunsOnlyProbeCommandsAndReportsSilentOnesAsFailed() {
        registry.register(RpcCommand("t.ok", "ok", probe = true) { _, r -> r.ok("fine") })
        registry.register(RpcCommand("t.err", "err", probe = true) { _, r -> r.fail("não suportado") })
        registry.register(RpcCommand("t.mute", "mute", probe = true) { _, _ -> })
        registry.register(RpcCommand("t.action", "not a probe") { _, _ -> throw AssertionError("must not run") })
        call("""{"id": 1, "cmd": "system.probe"}""")
        clock.advance(8_000)
        val result = sent.single { it.getInt("id") == 1 }.getJSONObject("result")
        assertTrue(result.getJSONObject("t.ok").getBoolean("ok"))
        assertEquals("não suportado", result.getJSONObject("t.err").getString("error"))
        assertFalse(result.getJSONObject("t.mute").getBoolean("ok"))
        assertTrue(result.getJSONObject("system.ping").getBoolean("ok"))
        assertFalse(result.has("t.action"))
    }
}
