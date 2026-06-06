package vn.aiview.nvr.gatewayonboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import vn.aiview.nvr.gatewayonboard.ui.OnboardApp
import vn.aiview.nvr.gatewayonboard.ui.OnboardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OnboardTheme {
                OnboardApp()
            }
        }
    }
}
