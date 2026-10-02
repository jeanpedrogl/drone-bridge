package io.github.jeanpedrogl.dronebridge

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-shot answer to a command. Whatever thread the SDK calls back on, only the first
 * [ok]/[fail] counts; later ones (e.g. a callback arriving after the timeout) are ignored.
 */
class RpcReply internal constructor(private val finish: (ok: Boolean, value: Any?) -> Unit) {
    private val completed = AtomicBoolean(false)

    fun ok(result: Any? = null) {
        if (completed.compareAndSet(false, true)) finish(true, result)
    }

    fun fail(message: String) {
        if (completed.compareAndSet(false, true)) finish(false, message)
    }
}

/**
 * A command the computer can invoke by name. [args] is human-readable documentation only (it
 * shows up in `system.commands`). [probe] marks read-only commands that need no arguments;
 * `system.probe` runs all of them to find out what this particular aircraft actually supports.
 */
class RpcCommand(
    val name: String,
    val description: String,
    val args: String = "",
    val requiresConfirm: Boolean = false,
    val probe: Boolean = false,
    val run: (args: JSONObject, reply: RpcReply) -> Unit,
)

/**
 * Name -> command table behind the `/drone/rpc/request` topic. Adding a feature for the computer
 * is one [register] call; see [DroneCommands]. Requests are `{"id", "cmd", "args"}`, responses
 * `{"id", "cmd", "ok", "result" | "error"}`.
 *
 * [schedule] runs a task after a delay on the thread that is allowed to touch the bridge (the
 * main thread in the app); every response is sent through it.
 */
class RpcRegistry(
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val commands = linkedMapOf<String, RpcCommand>()

    init {
        register(RpcCommand("system.ping", "Responde 'pong'. Serve para testar a ligação.", probe = true) { _, reply ->
            reply.ok("pong")
        })
        register(RpcCommand("system.commands", "Lista todos os comandos registrados.", probe = true) { _, reply ->
            reply.ok(JSONArray().also { list ->
                commands.values.forEach { c ->
                    list.put(
                        JSONObject()
                            .put("cmd", c.name)
                            .put("description", c.description)
                            .put("args", c.args)
                            .put("requires_confirm", c.requiresConfirm)
                            .put("probe", c.probe)
                    )
                }
            })
        })
        register(RpcCommand(
            "system.probe",
            "Executa todos os comandos de leitura e informa quais o drone conectado realmente aceitou.",
        ) { _, reply -> probe(reply) })
    }

    fun register(command: RpcCommand) {
        require(commands.put(command.name, command) == null) { "Comando duplicado: ${command.name}" }
    }

    /** Parses and runs one request; the response (always exactly one) goes to [send]. */
    fun handle(requestText: String, send: (JSONObject) -> Unit) {
        val request = try {
            JSONObject(requestText)
        } catch (e: Exception) {
            send(response(null, "", false, "JSON inválido"))
            return
        }
        val id = request.opt("id")
        val name = request.optString("cmd")
        val args = request.optJSONObject("args") ?: JSONObject()
        val reply = RpcReply { ok, value -> schedule(0) { send(response(id, name, ok, value)) } }

        val command = commands[name]
        when {
            command == null -> reply.fail("Comando desconhecido: '$name'. Veja system.commands.")
            command.requiresConfirm && !args.optBoolean("confirm", false) ->
                reply.fail("'$name' exige \"confirm\": true em args.")
            else -> {
                schedule(timeoutMs) { reply.fail("Sem resposta do drone em ${timeoutMs / 1000}s.") }
                try {
                    command.run(args, reply)
                } catch (e: Exception) {
                    reply.fail(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun probe(reply: RpcReply) {
        val targets = commands.values.filter { it.probe && it.name != "system.commands" }
        val results = JSONObject()
        val pending = AtomicInteger(targets.size)
        fun record(name: String, ok: Boolean, value: Any?) {
            synchronized(results) {
                if (results.has(name)) return
                results.put(name, JSONObject().put("ok", ok).put(if (ok) "result" else "error", value ?: JSONObject.NULL))
            }
            if (pending.decrementAndGet() == 0) reply.ok(results)
        }
        // Answer with what arrived if some getters never call back (unsupported on this aircraft).
        schedule(timeoutMs - PROBE_MARGIN_MS) {
            synchronized(results) {
                targets.filter { !results.has(it.name) }.forEach {
                    results.put(it.name, JSONObject().put("ok", false).put("error", "sem resposta"))
                }
            }
            reply.ok(results)
        }
        if (targets.isEmpty()) reply.ok(results)
        targets.forEach { c ->
            val sub = RpcReply { ok, value -> record(c.name, ok, value) }
            try {
                c.run(JSONObject(), sub)
            } catch (e: Exception) {
                sub.fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun response(id: Any?, cmd: String, ok: Boolean, value: Any?): JSONObject =
        JSONObject()
            .put("id", id ?: JSONObject.NULL)
            .put("cmd", cmd)
            .put("ok", ok)
            .put(if (ok) "result" else "error", value ?: JSONObject.NULL)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val PROBE_MARGIN_MS = 2_000L
    }
}
