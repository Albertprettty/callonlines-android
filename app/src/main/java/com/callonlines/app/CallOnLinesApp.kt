package com.callonlines.app

import android.app.Application
import com.google.android.material.color.DynamicColors

class CallOnLinesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
