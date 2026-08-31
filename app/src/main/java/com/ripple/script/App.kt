package com.ripple.script

import android.app.Application
import com.ripple.script.data.RewardsStore

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        store = RewardsStore(this)
    }

    companion object {
        lateinit var store: RewardsStore
            private set
    }
}