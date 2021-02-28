package com.github.gordonmu.coio.tls

import com.github.gordonmu.coio.CoIOStream
import io.netty.buffer.ByteBufAllocator
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import java.io.InputStream
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine

/**
 * @param keyCertChain a stream over a sequence of PEM encoded certificates
 * @param key stream of an unencrypted private key, PEM encoded, PKCS#8 format
 */
fun CoIOStream.wrapTlsServer(keyCertChain: InputStream,
                             key: InputStream): CoIOStream {
    val ctxServ: SslContext = SslContextBuilder.forServer(keyCertChain, key)
            .sslProvider(SslProvider.OPENSSL)
            .protocols("TLSv1.3")
            //.ciphers(listOf("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"))
            //.ciphers(listOf("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256"))
            .build()
    return this.wrapTls(ctxServ.newEngine(ByteBufAllocator.DEFAULT))
}

fun CoIOStream.wrapTlsServer(keyCertChain: List<X509Certificate>,
                             key: PrivateKey): CoIOStream {
    val ctxServ: SslContext = SslContextBuilder.forServer(key, keyCertChain)
            .sslProvider(SslProvider.OPENSSL)
            .protocols("TLSv1.3")
            //.ciphers(listOf("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"))
            //.ciphers(listOf("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256"))
            .build()
    return this.wrapTls(ctxServ.newEngine(ByteBufAllocator.DEFAULT))
}

/**
 * @param trustedCerts a stream over a sequence of PEM encoded certificates
 */
fun CoIOStream.wrapTlsClient(serverName: String,
                             trustedCerts: InputStream): CoIOStream = wrapTlsClient(serverName, com.github.gordonmu.coio.tls.loadX509Certificates(trustedCerts))

fun CoIOStream.wrapTlsClient(serverName: String,
                             trustedCerts: List<X509Certificate>): CoIOStream {
    val ctxClient: SslContext = SslContextBuilder.forClient()
            .sslProvider(SslProvider.OPENSSL)
            .trustManager(trustedCerts)
            .protocols("TLSv1.3")
            //.ciphers(listOf("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"))
            //.ciphers(listOf("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256"))
            .build()
    return this.wrapTls(ctxClient.newEngine(ByteBufAllocator.DEFAULT, serverName, 0))
}

fun CoIOStream.wrapTls(sslEngine: SSLEngine): CoIOStream {
    return com.github.gordonmu.coio.tls.TlsChannelAdapter(sslEngine, this)
}