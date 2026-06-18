package com.ps2pad.gamepad

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Current state of every control. [toPacket] serialises it into the 13-byte
 * binary frame the PC server expects (see pc-server/server.py).
 */
class InputState {

    // analog axes, already in Xbox range (-32767..32767, +Y = up)
    @Volatile var lx = 0
    @Volatile var ly = 0
    @Volatile var rx = 0
    @Volatile var ry = 0

    // analog triggers 0..255
    @Volatile var l2 = 0
    @Volatile var r2 = 0

    /** Which virtual controller on the PC this pad drives (0 = P1, 1 = P2, …). */
    @Volatile var player = 0

    @Volatile private var buttons = 0

    fun setButton(bit: Int, down: Boolean) {
        buttons = if (down) buttons or (1 shl bit) else buttons and (1 shl bit).inv()
    }

    fun clearButtons() { buttons = 0 }

    fun toPacket(): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC.toByte())
        buf.put(player.toByte())
        buf.putShort(buttons.toShort())
        buf.putShort(lx.toShort())
        buf.putShort(ly.toShort())
        buf.putShort(rx.toShort())
        buf.putShort(ry.toShort())
        buf.put(l2.toByte())
        buf.put(r2.toByte())
        return buf.array()
    }

    companion object {
        const val MAGIC = 0xA5
        const val PACKET_SIZE = 14

        // bit indices — must match BUTTON_BITS in server.py
        const val DPAD_UP = 0
        const val DPAD_DOWN = 1
        const val DPAD_LEFT = 2
        const val DPAD_RIGHT = 3
        const val START = 4
        const val SELECT = 5
        const val L3 = 6
        const val R3 = 7
        const val L1 = 8
        const val R1 = 9
        const val CROSS = 10
        const val CIRCLE = 11
        const val SQUARE = 12
        const val TRIANGLE = 13
    }
}
