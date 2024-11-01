import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    if (args.size == 2 && args[0] == "--resolver") {
        val addr = args[1]
        val (ip, port) = addr.split(":")
        val forwardTo = InetSocketAddress(ip, port.toInt())
        val selectorManager = SelectorManager(Dispatchers.IO)
        aSocket(selectorManager).udp().bind(InetSocketAddress("127.0.0.1", 2053)).use { socket ->
            aSocket(selectorManager).udp().connect(forwardTo).use { forwardingServer ->
                while (true) {
                    val datagram = socket.receive()
                    val incoming = Message.from(datagram.packet)
                    val outgoing = incoming.questions.flatMap {
                        val message = incoming.copy(header = incoming.header.copy(qdcount = 1), questions = listOf(it))
                        val packet = buildPacket {
                            write(message.toByteArray())
                        }
                        forwardingServer.send(Datagram(packet, forwardTo))
                        val resp = forwardingServer.receive()
                        Message.from(resp.packet).answers
                    }
                    val packet = buildPacket {
                        val response = Message.responseFor(incoming)
                        val message = response.copy(
                            header = response.header.copy(ancount = outgoing.size.toShort()),
                            answers = outgoing
                        )
                        write(message.toByteArray())
                    }
                    socket.send(Datagram(packet, datagram.address))
                }
            }
        }
    } else {
        val selectorManager = SelectorManager(Dispatchers.IO)
        aSocket(selectorManager).udp().bind(InetSocketAddress("127.0.0.1", 2053)).use { socket ->
            while (true) {
                val datagram = socket.receive()
                val incoming = Message.from(datagram.packet)
                val packet = buildPacket {
                    val outgoing = Message.responseFor(incoming)
                    write(outgoing.toByteArray())
                }
                socket.send(Datagram(packet, datagram.address))
            }
        }
    }
}
