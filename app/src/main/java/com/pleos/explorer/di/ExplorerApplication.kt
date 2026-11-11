package com.pleos.explorer.di

import android.app.Application

class ExplorerApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
