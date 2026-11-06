package io.github.kvarflow.pcalc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/** RetroArchSync against a fake RetroArch answering READ_CORE_MEMORY from made-up memory. */
class RetroArchSyncTest {

    /** A fake RetroArch's memory: a fixed pattern, with a battle set up at the addresses the reader uses. */
    private val overrides = mapOf(
        0x030030F0L + 0x439 to 0x02, // gMain: in a battle
        0x02023BCCL to 2, // gBattlersCount
        0x02023BCEL to 1, 0x02023BCFL to 0, 0x02023BD0L to 0, 0x02023BD1L to 0, // gBattlerPartyIndexes: 1, 0
        0x02024029L to 3, // gPlayerPartyCount
        0x02024028L to 2, // gEnemyPartyCount
    )

    private fun memory(address: Long) = overrides[address] ?: (((address * 31 + 7) shr 3) and 0xFF).toInt()

    private var server: DatagramSocket? = null

    /** Starts a fake RetroArch; `reply` turns a request into its answer. Returns its port. */
    private fun fakeRetroArch(reply: (Long, Int) -> String): Int {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(isDaemon = true) {
            val buffer = ByteArray(1024)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: Exception) {
                    return@thread
                }
                val parts = String(packet.data, 0, packet.length).trim().split(" ")
                val answer = reply(parts[1].toLong(16), parts[2].toInt()).toByteArray()
                socket.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
            }
        }
        return socket.localPort
    }

    private fun realMemory(address: Long, size: Int) =
        "READ_CORE_MEMORY %x ".format(address) + (0 until size).joinToString(" ") { "%02X".format(memory(address + it)) }

    @After
    fun stop() {
        server?.close()
    }

    @Test
    fun readsTheBattle() {
        val port = fakeRetroArch(::realMemory)
        val json = RetroArchSync(port = port).stateJson()
        // For comparing with retroarch_sync.py against the same fake (see the README).
        System.getProperty("syncStateOut")?.let { File(it).writeText(json) }

        assertTrue(json.startsWith("""{"game":"radicalred","script":1,"via":"retroarch","inBattle":true,"""))
        assertTrue(json.contains(""""partyIndexes":[1,0],"""))
        assertTrue(json.contains(""""party":{"count":3,"hex":""""))
        assertTrue(json.contains(""""enemyParty":{"count":2,"hex":""""))
        // Six 100-byte party slots, as hex.
        val hex = Regex(""""party":\{"count":3,"hex":"([0-9a-f]*)"""").find(json)!!.groupValues[1]
        assertEquals(1200, hex.length)
        assertEquals("%02x".format(memory(0x02024284L)), hex.substring(0, 2))
        // Two battlers, each with eight stat stages.
        assertEquals(2, Regex(""""stages":\[(\d+,){7}\d+]""").findAll(json).count())
    }

    @Test
    fun notInBattleHasNoBattlers() {
        val port = fakeRetroArch { address, size ->
            if (address == 0x030030F0L + 0x439) "READ_CORE_MEMORY %x 00".format(address) else realMemory(address, size)
        }
        val json = RetroArchSync(port = port).stateJson()
        assertTrue(json.contains(""""inBattle":false,"""))
        assertTrue(json.endsWith(""""partyIndexes":[],"mons":[]}"""))
    }

    @Test
    fun saysWhenTheCoreCantBeRead() {
        val port = fakeRetroArch { address, _ -> "READ_CORE_MEMORY %x -1 no memory map defined".format(address) }
        try {
            RetroArchSync(port = port).stateJson()
            fail("expected an error")
        } catch (e: RetroArchSync.RetroArchException) {
            assertEquals("RetroArch could not read the game: no memory map defined", e.message)
        }
    }

    @Test
    fun saysWhenRetroArchIsNotRunning() {
        val unused = DatagramSocket(0).use { it.localPort }
        try {
            RetroArchSync(port = unused, timeoutMs = 200).stateJson()
            fail("expected an error")
        } catch (e: RetroArchSync.RetroArchException) {
            assertEquals("RetroArch did not answer: is a game running, with Network Commands on?", e.message)
        }
    }
}
