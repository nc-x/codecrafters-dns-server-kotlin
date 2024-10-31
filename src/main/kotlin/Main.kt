import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.io.use

fun main(args: Array<String>) = runBlocking {
    val selectorManager = SelectorManager(Dispatchers.IO)
    aSocket(selectorManager).udp().bind(InetSocketAddress("127.0.0.1", 2053)).use { socket ->
        while (true) {
            val datagram = socket.receive()
            val packet = buildPacket {
                write(Header.default().toByteArray())
            }
            socket.send(Datagram(packet, datagram.address))
        }
    }
}
