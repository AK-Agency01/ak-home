package com.akhome.installer

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * The phone's ADB identity: one RSA key + self-signed certificate, made once and kept in the app's files.
 * The TV remembers it after "Always allow from this computer", so later connections need no tap on the TV.
 */
object AdbKeys {
    lateinit var key: PrivateKey; private set
    lateinit var cert: Certificate; private set

    @Synchronized fun load(ctx: Context) {
        if (::key.isInitialized) return
        val kf = File(ctx.filesDir, "adb.key"); val cf = File(ctx.filesDir, "adb.crt")
        if (kf.exists() && cf.exists()) try {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
            cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream())
            return
        } catch (_: Exception) { /* remake below */ }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=AK Home Installer")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000L),
            Date(now + 30L * 365 * 86_400_000L), name, pair.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        key = pair.private
        cert = JcaX509CertificateConverter().getCertificate(holder)
        kf.writeBytes(key.encoded); cf.writeBytes(cert.encoded)
    }
}

/** One ADB connection to one TV. Not thread-safe: use it from one worker thread. */
class AdbClient : AbsAdbConnectionManager() {
    init {
        api = Build.VERSION_CODES.R          // understands the TLS upgrade of Android 11+ TVs; old TVs never ask for it
        setTimeout(90, TimeUnit.SECONDS)     // long enough for someone to accept "Allow debugging?" on the TV
    }

    override fun getPrivateKey(): PrivateKey = AdbKeys.key
    override fun getCertificate(): Certificate = AdbKeys.cert
    override fun getDeviceName(): String = "AK Home Installer"

    /** Runs a command on the TV and returns everything it printed (stdout + stderr), trimmed. */
    fun sh(cmd: String): String = openStream("shell:($cmd) 2>&1; echo $END").use { s ->
        // libadb 3.1.1 can wait forever after the TV closes a stream whose last data was already queued,
        // so the command prints an end marker and we stop reading at it instead of waiting for the close.
        val input = s.openInputStream()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n)
                if (out.toString("UTF-8").contains(END)) break
            }
        } catch (e: java.io.IOException) { if (!s.isClosed && !out.toString("UTF-8").contains(END)) throw e }
        out.toString("UTF-8").substringBefore(END).replace("\r", "").trim()
    }

    private companion object { const val END = "__AKHOME_END__" }

    /**
     * Copies a file to the TV byte for byte. `exec:` (Android 5+) has no terminal in between, so binary data
     * arrives intact, unlike `shell:` on old TVs.
     */
    fun push(src: InputStream, size: Long, path: String, progress: (Int) -> Unit) {
        openStream("exec:cat > $path").use { s ->
            val out = s.openOutputStream()
            val buf = ByteArray(64 * 1024)
            var sent = 0L; var last = -1
            while (true) {
                val n = src.read(buf); if (n < 0) break
                out.write(buf, 0, n); sent += n
                val pct = if (size > 0) (sent * 100 / size).toInt() else 0
                if (pct != last) { progress(pct); last = pct }
            }
            out.flush()
        }
        // closing the stream ends cat's input; wait until the whole file is on the TV's disk
        val deadline = System.currentTimeMillis() + 30_000
        while (sh("wc -c < $path").trim().toLongOrNull() != size) {
            if (System.currentTimeMillis() > deadline) throw java.io.IOException("copy to TV incomplete")
            Thread.sleep(200)
        }
    }

    fun prop(name: String): String = sh("getprop $name")
}
