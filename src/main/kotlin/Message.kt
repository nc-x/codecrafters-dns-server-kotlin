import java.nio.ByteBuffer
import java.nio.ByteOrder

typealias Byte3 = Short

data class Header(
    val id: Short,
    // +Short
    val qr: Boolean,
    val opcode: Byte,
    val aa: Boolean,
    val tc: Boolean,
    val rd: Boolean,
    val ra: Boolean,
    val z: Byte,
    val rcode: Byte3,
    // -Short
    val qdcount: Short,
    val ancount: Short,
    val nscount: Short,
    val arcount: Short,
) {

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(id)
        val packed = ((if (qr) 1 else 0) shl 15 or
                (opcode.toInt() and 0b1111 shl 11) or
                (if (aa) 1 else 0 shl 10) or
                (if (tc) 1 else 0 shl 9) or
                (if (rd) 1 else 0 shl 8) or
                (if (ra) 1 else 0 shl 7) or
                (z.toInt() and 0b1111 shl 3) or
                (rcode.toInt() and 0b111)).toShort()
        buffer.putShort(packed.toShort())
        buffer.putShort(qdcount)
        buffer.putShort(ancount)
        buffer.putShort(nscount)
        buffer.putShort(arcount)
        return buffer.array()
    }

    companion object {
        fun Int.toBoolean() = this.toInt() == 1

        fun default(): Header {
            return Header(
                id = 1234,
                qr = 1.toBoolean(),
                opcode = 0,
                aa = 0.toBoolean(),
                tc = 0.toBoolean(),
                rd = 0.toBoolean(),
                ra = 0.toBoolean(),
                z = 0,
                rcode = 0,
                qdcount = 0,
                ancount = 0,
                nscount = 0,
                arcount = 0
            )
        }
    }

}
