package com.akhome.installer

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android 11+ TVs speak ADB over TLS 1.3; the bundled Conscrypt gives every phone the same TLS stack
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        Thread { AdbKeys.load(this) }.start()
    }
}
