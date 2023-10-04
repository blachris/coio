![Java CI](https://github.com/blachris/coio/workflows/Java%20CI/badge.svg)
 [ ![Download](https://api.bintray.com/packages/blachris/jvm/coio/images/download.svg) ](https://bintray.com/blachris/jvm/coio/_latestVersion)

# CoIO

Kotlin [Coroutines](https://kotlinlang.org/docs/reference/coroutines-overview.html) make asynchronous programming simple and efficient
but there is no official library that provides IO operations in a coroutine.
These operations, such as reading a file or sending data over a TCP connection, benefit heavily from coroutines because they can take relatively long
and use few CPU resources.
This library seeks to wrap existing Java non-blocking IO libraries to provide a uniform and simple API with coroutines.

## Features

* Simple, lean stream interfaces with suspend read and write functions using ByteBuffers
* Supports file access
* Supports TCP servers and clients
* Supports UDP unicast and multicast
* Wrapping any stream with TLS

## Alternatives

* Official Kotlin coroutine IO - Does not exist yet.
* Java blocking IO - Must be used with care in coroutines because the blocking operations can consume your worker threads.
Consider using the [IO Dispatcher](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-i-o.html).
* Java NIO - Efficient non-blocking operation available but steep learning curve and quite a bit of code needed to build coroutines.
* Java NIO2 - Nice asynchronous methods that can be easily wrapped into a coroutine. 

## Usage

### TCP Server

~~~
val server = CoIOTcp.listen(8775)

for (connection in server) {
    launch {
        val buf = ByteBuffer.allocate(1024)
        connection.readFully(buf)
        buf.flip()
        connection.writeFully(buf)
    }
}
~~~
