package com.kachat.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.services.CrashRecorder
import com.kachat.app.ui.KaChatApp
import com.kachat.app.ui.theme.KaChatTheme
import com.kachat.app.viewmodels.WalletViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

/**
 * Single Activity — all navigation is handled in Compose via NavHost. KaChat's own also routed
 * notifications, links, the share sheet and calls; none of that is here (iOS KaChatChessApp).
 *
 * Extends [AppCompatActivity] (rather than plain `FragmentActivity`, which it's a superset of)
 * because the per-app language API (`AppCompatDelegate.setApplicationLocales`, used by the
 * Language setting in Settings) relies on `AppCompatActivity`'s `attachBaseContext` override to
 * apply the locale override on API levels below 33.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phones lock to portrait; tablets (sw>=600dp, see res/values-sw600dp/bools.xml) rotate
        // freely - mirroring iOS (iPhone portrait-only, iPad any orientation).
        requestedOrientation = if (resources.getBoolean(R.bool.lock_portrait)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        // The balance and the seed screen used to sit in the task switcher's thumbnail of the app
        // in plain view. This is Android's own switch for exactly that, and unlike FLAG_SECURE it
        // does not also block screenshots - and it cannot fight the FLAG_SECURE the seed and
        // private-key screens set and clear for themselves. Below API 33 there is no equivalent
        // that leaves screenshots alone. iOS covers the same exposure with a blur.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        enableEdgeToEdge()
        setContent {
            val walletViewModel: WalletViewModel = hiltViewModel()
            // Settings > Customization > Appearance: System follows the device, as on iOS.
            val appearance by walletViewModel.appearance.collectAsState()
            KaChatTheme(darkTheme = appearance.isDark(androidx.compose.foundation.isSystemInDarkTheme())) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KaChatApp(walletViewModel)
                    CrashNotice()
                }
            }
        }
    }

    /**
     * Shown once on the launch after the app crashed: the previous process died before anyone
     * could see why, and its record is the one thing that turns "it will not open" into a fix.
     * Share sends the newest report to a share sheet (mail, Telegram, anything).
     */
    @androidx.compose.runtime.Composable
    private fun CrashNotice() {
        val crashFile = remember { CrashRecorder.unseenCrash(this) }
        var show by remember { mutableStateOf(crashFile != null) }
        if (!show || crashFile == null) return
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { show = false; CrashRecorder.markSeen(this) },
            title = { Text("KaPlay closed unexpectedly") },
            text = { Text("The app did not close normally the last time it ran. A report of what happened was saved. Sharing it with the developer is the quickest way to get it fixed - it contains no keys or addresses.") },
            confirmButton = {
                TextButton(onClick = {
                    show = false
                    CrashRecorder.markSeen(this)
                    shareCrashReport(crashFile)
                }) { Text("Share report") }
            },
            dismissButton = {
                TextButton(onClick = { show = false; CrashRecorder.markSeen(this) }) { Text("Not now") }
            }
        )
    }

    private fun shareCrashReport(file: File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "KaPlay crash report")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share crash report"))
        }.onFailure { android.util.Log.w("MainActivity", "Could not share crash report", it) }
    }
}
