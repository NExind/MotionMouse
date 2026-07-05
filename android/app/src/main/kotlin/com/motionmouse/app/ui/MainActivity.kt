package com.motionmouse.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.motionmouse.app.ui.screens.MainScreen
import com.motionmouse.app.ui.screens.SettingsScreen
import com.motionmouse.app.ui.theme.MotionMouseTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity — hosts all Compose screens via NavController.
 *
 * Responsibilities:
 *   - Start the foreground service on launch
 *   - Request Bluetooth permissions on first launch
 *   - Set up navigation graph
 *   - Pass ViewModel state and callbacks to composables
 *
 * Navigation graph (V1 — two destinations):
 *   main → settings
 *   settings → back to main
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Permission launcher for Bluetooth (Android 12+)
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            // Permissions granted — restart discovery to pick up BT hosts
            viewModel.startDiscovery()
        }
        // If denied, app still works via Wi-Fi — no crash, no repeated prompts
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure the foreground service is running before we render anything
        viewModel.ensureServiceRunning()

        // Request Bluetooth permissions if not already granted
        requestBluetoothPermissionsIfNeeded()

        setContent {
            MotionMouseTheme {
                val navController = rememberNavController()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                NavHost(
                    navController = navController,
                    startDestination = "main"
                ) {
                    composable("main") {
                        MainScreen(
                            uiState = uiState,
                            onConnectTo = viewModel::connectTo,
                            onDisconnect = viewModel::disconnect,
                            onLeftDown = viewModel::onLeftButtonDown,
                            onLeftUp = viewModel::onLeftButtonUp,
                            onRightDown = viewModel::onRightButtonDown,
                            onRightUp = viewModel::onRightButtonUp,
                            onToggleLock = viewModel::toggleLock,
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    /**
     * Request Bluetooth permissions appropriate for the API level.
     *
     * Android 12+ needs BLUETOOTH_CONNECT and BLUETOOTH_SCAN.
     * Older versions need BLUETOOTH (already declared in manifest,
     * automatically granted as normal permissions pre-API 23).
     *
     * We only ask once — if the user denies, we don't pester them.
     * Wi-Fi still works without Bluetooth permissions.
     */
    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf<String>()

            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }

            if (needed.isNotEmpty()) {
                bluetoothPermissionLauncher.launch(needed.toTypedArray())
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // On Android 6 to 11, Bluetooth discovery requires location permission
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                bluetoothPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Intercept volume buttons when the "Volume Button Clicks" setting is on.
     *
     * We override dispatchKeyEvent instead of onKeyDown/onKeyUp because
     * Compose intercepts key events before they reach onKeyDown, so the
     * volume buttons would be silently consumed by the Compose window.
     * dispatchKeyEvent is called first, before any view processes the event.
     *
     * Volume Up   → Left click
     * Volume Down → Right click
     * repeatCount == 0 guard ensures we only fire once per physical press,
     * not once per repeat while held.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val settings = viewModel.currentSettings.value
        if (settings.useVolumeButtons && viewModel.uiState.value.isConnected) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) viewModel.onLeftButtonDown()
                        KeyEvent.ACTION_UP   -> viewModel.onLeftButtonUp()
                    }
                    return true // consume — don't change volume
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) viewModel.onRightButtonDown()
                        KeyEvent.ACTION_UP   -> viewModel.onRightButtonUp()
                    }
                    return true // consume — don't change volume
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
