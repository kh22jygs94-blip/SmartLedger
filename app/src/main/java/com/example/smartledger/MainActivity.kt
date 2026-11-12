package com.example.smartledger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartledger.service.ScreenStateReceiver
import com.example.smartledger.ui.LedgerApp
import com.example.smartledger.ui.LedgerViewModel
import com.example.smartledger.ui.rememberWindowTier
import com.example.smartledger.ui.theme.SmartLedgerTheme
import com.example.smartledger.util.FrameRateControl
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 读屏服务的常驻通知需要通知权限（Android 13+），没有权限服务照样跑，只是看不到提示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val container = (application as SmartLedgerApp).container
        // 常驻服务被杀时亮屏广播没人接，打开 App 也补一次自检
        ScreenStateReceiver.checkInBackground(this)

        // 锁帧率：设置页切换后立刻作用到当前窗口
        FrameRateControl.init(this)
        lifecycleScope.launch {
            FrameRateControl.rate.collect { hz -> FrameRateControl.apply(this@MainActivity, hz) }
        }

        setContent {
            SmartLedgerTheme {
                val ledgerViewModel: LedgerViewModel = viewModel(
                    factory = LedgerViewModel.factory(container.repository)
                )
                // 只在最外层算一次窗口档位，页面只认 Compact / Medium / Expanded
                val tier = rememberWindowTier()
                LedgerApp(ledgerViewModel, tier)
            }
        }
    }
}
