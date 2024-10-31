import Message.Offset
import io.ktor.utils.io.core.*
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.readUByte
import kotlinx.io.writeString

typealias Bit3 = Byte

data class Header(
    val id: Short,
    // +Flags: Short
    val qr: Boolean,
    val opcode: Byte,
    val aa: Boolean,
    val tc: Boolean,
    val rd: Boolean,
    val ra: Boolean,
    val z: Byte,
    val rcode: Bit3,
    // -Flags: Short
    val qdcount: Short,
    val ancount: Short,
    val nscount: Short,
    val arcount: Short,
) {

    fun toByteArray(buffer: Buffer) {
        buffer.writeShort(id)
        val packed = (((if (qr) 1 else 0) shl 15) or
                ((opcode.toInt() and 0b1111) shl 11) or
                ((if (aa) 1 else 0) shl 10) or
                ((if (tc) 1 else 0) shl 9) or
                ((if (rd) 1 else 0) shl 8) or
                ((if (ra) 1 else 0) shl 7) or
                ((z.toInt() and 0b1111) shl 3) or
                (rcode.toInt() and 0b111)).toShort()
        buffer.writeShort(packed.toShort())
        buffer.writeShort(qdcount)
        buffer.writeShort(ancount)
        buffer.writeShort(nscount)
        buffer.writeShort(arcount)
    }

    companion object {
        fun from(source: Source): Header {
            val id = source.readShort()
            val packed = source.readShort().toInt()
            val qr = (packed shr 15) and 1 == 1
            val opcode = ((packed shr 11) and 0b1111).toByte()
            val aa = (packed shr 10) and 1 == 1
            val tc = (packed shr 9) and 1 == 1
            val rd = (packed shr 8) and 1 == 1
            val ra = (packed shr 7) and 1 == 1
            val z = ((packed shr 3) and 0b1111).toByte()
            val rcode = (packed and 0b111).toByte()
            val qdcount = source.readShort()
            val ancount = source.readShort()
            val nscount = source.readShort()
            val arcount = source.readShort()

            return Header(
                id,
                qr,
                opcode,
                aa,
                tc,
                rd,
                ra,
                z,
                rcode,
                qdcount,
                ancount,
                nscount,
                arcount,
            )
        }
    }

}

// https://www.rfc-editor.org/rfc/rfc1035#section-3.2.2
enum class Type(val value: Short) {
    A(1),
    NS(2),
    MD(3),
    MF(4),
    CNAME(5),
    SOA(6),
    MB(7),
    MG(8),
    MR(9),
    NULL(10),
    WKS(11),
    PTR(12),
    HINFO(13),
    MINFO(14),
    MX(15),
    TXT(16);

    companion object {
        fun from(value: Short): Type =
            Type.entries.first { it.value == value }
    }
}

// https://www.rfc-editor.org/rfc/rfc1035#section-3.2.4
enum class Class(val value: Short) {
    IN(1),
    CS(2),
    CH(3),
    HS(4);

    companion object {
        fun from(value: Short): Class =
            Class.entries.first { it.value == value }
    }
}

data class Question(
    val domainName: String,
    val type: Type,
    val clazz: Class,
) {
    @OptIn(ExperimentalStdlibApi::class)
    fun toByteArray(buffer: Buffer, offset: Offset, mappings: MutableMap<String, Int>) {
        buffer.writeDomainName(domainName, offset, mappings)
        buffer.writeShort(type.value)
        buffer.writeShort(clazz.value)
    }

    companion object {
        fun from(source: Source, bytes: ByteArray): Question {
            return Question(
                domainName = parseDomainName(source, bytes),
                type = Type.from(source.readShort()),
                clazz = Class.from(source.readShort())
            )
        }

        private fun parseDomainName(source: Source, bytes: ByteArray): String {
            var source = source
            var payloadLen: Int
            val name = StringBuilder()
            while (true) {
                payloadLen = source.readUByte().toInt()
                if (payloadLen == 0) break
                else if (payloadLen and 0b11000000 == 0b11000000) {
                    // Compressed
                    payloadLen = (payloadLen shr 8) or source.readUByte().toInt()
                    var reqdOffset = payloadLen and 0b0011111111111111
                    source = Buffer().apply { write(bytes.sliceArray(reqdOffset..<bytes.size)) }
                } else {
                    // Uncompressed
                    val label = source.readString(payloadLen.toLong())
                    name.append(label)
                    name.append(".")
                }
            }
            return name.toString()
        }
    }
}

data class Answer(
    val domainName: String,
    val type: Type,
    val clazz: Class,
    val ttl: Int,
    val rdata: String,
) {
    fun toByteArray(buffer: Buffer, offset: Offset, mappings: MutableMap<String, Int>) {
        buffer.writeDomainName(domainName, offset, mappings)
        buffer.writeShort(type.value)
        buffer.writeShort(clazz.value)
        buffer.writeInt(ttl)
        when (type) {
            Type.A -> {
                buffer.writeShort(4)
                val ipParts = rdata.split(".")
                for (part in ipParts) buffer.writeByte(part.toByte())
            }

            else -> error("RDATA is only implemented for A currently.")
        }

    }
}

data class Message(
    val header: Header,
    val questions: List<Question>,
    val answers: List<Answer>,
) {
    data class Offset(var offset: Int = 12)

    fun toByteArray(): ByteArray {
        val buffer = Buffer()
        header.toByteArray(buffer)
        val mappings = mutableMapOf<String, Int>()
        val offset = Offset()
        questions.forEach { it.toByteArray(buffer, offset, mappings) }
        answers.forEach { it.toByteArray(buffer, offset, mappings) }
        return buffer.readByteArray()
    }

    companion object {
        fun responseFor(incoming: Message): Message =
            Message(
                header = Header(
                    id = incoming.header.id,
                    qr = true, // 1
                    opcode = incoming.header.opcode,
                    aa = false, // 0
                    tc = false, // 0
                    rd = incoming.header.rd,
                    ra = false, // 0
                    z = 0,
                    rcode = if (incoming.header.opcode.toInt() == 0) 0 else 4,
                    qdcount = incoming.header.qdcount,
                    ancount = incoming.questions.size.toShort(),
                    nscount = 0,
                    arcount = 0,
                ),
                questions = incoming.questions,
                answers = incoming.questions.map { question ->
                    Answer(
                        domainName = question.domainName,
                        type = question.type,
                        clazz = question.clazz,
                        ttl = 60,
                        rdata = "8.8.8.8"
                    )
                }
            )

        fun from(source: Source): Message {
            val bytes = source.copy().readByteArray()
            val header = Header.from(source)
            val questions = (0..<header.qdcount).map { Question.from(source, bytes) }
            return Message(header, questions, listOf())
        }
    }
}

private fun Buffer.writeDomainName(domainName: String, offset: Offset, mappings: MutableMap<String, Int>) {
    var left = 0
    var domainName = domainName
    while (left != -1 && left < domainName.length) {
        val remaining = domainName.substring(left)
        if (remaining in mappings) {
            writeShort((mappings[remaining]!! or 0b1100000000000000).toShort())
            offset.offset += 2
            return
        } else {
            mappings[remaining] = offset.offset
            var right = domainName.indexOf('.', left + 1)
            right = if (right == -1) domainName.length else right
            offset.offset += (right - left + 1)
            val label = domainName.substring(left, right)
            writeByte(label.length.toByte())
            writeString(label)
            left = right + 1
        }
    }
    writeByte(0)
}
