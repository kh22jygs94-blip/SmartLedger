package com.example.smartledger

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.smartledger.diag.DiagnosticsMonitor
import com.example.smartledger.util.AppLog
import kotlinx.coroutines.launch

class SmartLedgerApp : Application() {

    lateinit var container: AppContainer
        private set

    /** 记录处于前台的 Activity 数量，0 表示应用已经退到后台 */
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        DiagnosticsMonitor.init(this)
        container = AppContainer(this)
        container.applicationScope.launch {
            container.repository.ensureSeeded()
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) DiagnosticsMonitor.setForeground(true)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) DiagnosticsMonitor.setForeground(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
