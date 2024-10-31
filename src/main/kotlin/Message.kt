import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readString
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
    fun toByteArray(buffer: Buffer) {
        buffer.writeDomainName(domainName)
        buffer.writeShort(type.value)
        buffer.writeShort(clazz.value)
    }

    companion object {
        fun from(source: Source) =
            Question(
                domainName = parseDomainName(source),
                type = Type.from(source.readShort()),
                clazz = Class.from(source.readShort())
            )
    }
}

data class Answer(
    val domainName: String,
    val type: Type,
    val clazz: Class,
    val ttl: Int,
    val rdata: String,
) {
    fun toByteArray(buffer: Buffer) {
        buffer.writeDomainName(domainName)
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
    val question: Question,
    val answer: Answer?,
) {
    fun toByteArray(): ByteArray {
        val buffer = Buffer()
        header.toByteArray(buffer)
        question.toByteArray(buffer)
        answer?.toByteArray(buffer)
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
                    qdcount = 1,
                    ancount = 1,
                    nscount = 0,
                    arcount = 0,
                ),
                question = incoming.question,
                answer = Answer(
                    domainName = incoming.question.domainName,
                    type = incoming.question.type,
                    clazz = incoming.question.clazz,
                    ttl = 60,
                    rdata = "8.8.8.8"
                )
            )

        fun from(source: Source): Message {
            val header = Header.from(source)
            val question = Question.from(source)
            return Message(header, question, null)
        }
    }
}

private fun parseDomainName(source: Source): String {
    var payloadLen: Byte
    val name = StringBuilder()
    do {
        payloadLen = source.readByte()
        name.append(source.readString(payloadLen.toLong()))
        name.append(".")
    } while (payloadLen != 0.toByte())
    return name.toString()
}

private fun Buffer.writeDomainName(domainName: String) {
    domainName
        .split("\\W+".toRegex())
        .filter { it.isNotEmpty() }
        .forEach {
            writeByte(it.length.toByte())
            writeString(it)
        }
    writeByte(0)
}
