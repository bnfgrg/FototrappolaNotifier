package com.fototrappola.notifier

import android.app.Application

class FototrappolaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (CredentialStore.isConfigured(this)) {
            ImapListenerService.start(this)
        }
    }
}
