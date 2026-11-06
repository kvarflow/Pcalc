package io.github.kvarflow.pcalc

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Radical Red's party and battle, read from RetroArch running on this device: the app's built-in
 * version of rr-calc's emulator/retroarch_sync.py, so the Thor needs no Termux or Python.
 *
 * RetroArch reads a game's memory for other apps when Settings > Network > Network Commands is on
 * (UDP port 55355), with the mGBA core. [stateJson] returns exactly what the script serves on
 * /state, which rr-calc's emulator sync (js/emulator_sync.js) reads once a second. It only reads
 * the game's memory; it changes nothing in the game.
 */
class RetroArchSync(
    private val host: String = "127.0.0.1",
    private val port: Int = DEFAULT_PORT,
    private val timeoutMs: Int = 500,
) {
    class RetroArchException(message: String) : Exception(message)

    /** The /state JSON, or [RetroArchException] saying why RetroArch couldn't be read. */
    @Synchronized
    fun stateJson(): String = DatagramSocket().use { socket ->
        socket.soTimeout = timeoutMs
        Reader(socket).state()
    }

    private inner class Reader(private val socket: DatagramSocket) {
        private val address = InetAddress.getByName(host)
        private val buffer = ByteArray(65536)

        /** `length` bytes of the game's memory (READ_CORE_MEMORY, a chunk at a time). */
        fun read(start: Long, length: Int): ByteArray {
            val out = ByteArrayOutputStream(length)
            var at = start
            val end = start + length
            while (at < end) {
                val size = minOf(CHUNK.toLong(), end - at).toInt()
                val message = "READ_CORE_MEMORY %x %d\n".format(at, size).toByteArray()
                socket.send(DatagramPacket(message, message.size, address, port))
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    throw RetroArchException("RetroArch did not answer: is a game running, with Network Commands on?")
                }
                // "READ_CORE_MEMORY <address> <byte> <byte>...", or "READ_CORE_MEMORY <address> -1 <why>".
                val reply = String(packet.data, 0, packet.length, Charsets.UTF_8).trim().split(WHITESPACE)
                if (reply.size < 3 || reply[0] != "READ_CORE_MEMORY" || reply[2] == "-1") {
                    val why = if (reply.size > 3) reply.drop(3) else reply
                    throw RetroArchException("RetroArch could not read the game: " + why.joinToString(" "))
                }
                for (byte in reply.drop(2).take(size)) out.write(byte.toInt(16))
                at += size
            }
            return out.toByteArray()
        }

        private fun party(countAddress: Long, address: Long): String {
            val count = minOf(u8(read(countAddress, 1), 0), 6)
            val hex = read(address, 6 * PARTY_MON_SIZE).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            return """{"count":$count,"hex":"$hex"}"""
        }

        /** The same as retroarch_sync.py's (and the mGBA script's) /state. */
        fun state(): String {
            val inBattle = u8(read(G_MAIN + 0x439, 1), 0) and 2 != 0
            val battlers = if (inBattle) minOf(u8(read(G_BATTLERS_COUNT, 1), 0), 4) else 0
            val indexes = mutableListOf<Int>()
            val mons = mutableListOf<String>()
            if (battlers > 0) {
                val indexBytes = read(G_BATTLER_PARTY_INDEXES, 2 * battlers)
                val battle = read(G_BATTLE_MONS, BATTLE_MON_SIZE * battlers)
                for (i in 0 until battlers) {
                    indexes += u16(indexBytes, 2 * i)
                    val o = i * BATTLE_MON_SIZE
                    val stages = (0x18 until 0x20).joinToString(",") { u8(battle, o + it).toString() }
                    mons += """{"species":${u16(battle, o)},"hp":${u16(battle, o + 0x28)},""" +
                        """"maxHp":${u16(battle, o + 0x2C)},"level":${u8(battle, o + 0x2A)},""" +
                        """"stages":[$stages],"status":${u32(battle, o + 0x4C)},"pid":${u32(battle, o + 0x48)}}"""
                }
            }
            val battleType = u32(read(G_BATTLE_TYPE_FLAGS, 4), 0)
            val trainer = u16(read(G_TRAINER_BATTLE_OPPONENT_A, 2), 0)
            val party = party(G_PLAYER_PARTY_COUNT, G_PLAYER_PARTY)
            val enemyParty = party(G_ENEMY_PARTY_COUNT, G_ENEMY_PARTY)
            return """{"game":"radicalred","script":$SCRIPT_VERSION,"via":"retroarch","inBattle":$inBattle,""" +
                """"battleType":$battleType,"trainer":$trainer,"party":$party,"enemyParty":$enemyParty,""" +
                """"partyIndexes":[${indexes.joinToString(",")}],"mons":[${mons.joinToString(",")}]}"""
        }
    }

    companion object {
        const val DEFAULT_PORT = 55355
        /** The version of retroarch_sync.py this matches. */
        const val SCRIPT_VERSION = 1

        // FireRed (US 1.0) addresses: Radical Red's own code still uses every one of them.
        private const val G_MAIN = 0x030030F0L // inBattle: bit 1 of the byte at +0x439
        private const val G_BATTLE_TYPE_FLAGS = 0x02022B4CL
        private const val G_BATTLE_MONS = 0x02023BE4L // 0x58 bytes a battler
        private const val G_BATTLERS_COUNT = 0x02023BCCL
        private const val G_BATTLER_PARTY_INDEXES = 0x02023BCEL // u16 a battler
        private const val G_PLAYER_PARTY_COUNT = 0x02024029L
        private const val G_PLAYER_PARTY = 0x02024284L // 100 bytes a Pokémon, as the save stores them
        private const val G_ENEMY_PARTY_COUNT = 0x02024028L
        private const val G_ENEMY_PARTY = 0x0202402CL
        private const val G_TRAINER_BATTLE_OPPONENT_A = 0x020386AEL

        private const val PARTY_MON_SIZE = 100
        private const val BATTLE_MON_SIZE = 0x58
        private const val CHUNK = 256
        private val WHITESPACE = Regex("\\s+")

        private fun u8(data: ByteArray, at: Int) = data[at].toInt() and 0xFF
        private fun u16(data: ByteArray, at: Int) = u8(data, at) or (u8(data, at + 1) shl 8)
        private fun u32(data: ByteArray, at: Int): Long = u16(data, at).toLong() or (u16(data, at + 2).toLong() shl 16)
    }
}
