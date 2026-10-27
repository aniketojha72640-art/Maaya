package com.gesturecursor.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gesturecursor.app.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: CursorSettings
    private var testClickCount = 0

    // Runtime permission launcher for Camera (and Android 13+ foreground notification)
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshPermissionStatusUI()
        GestureCursorService.requestPipelineRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = CursorSettings(this)

        setupPermissionButtons()
        setupSliders()
        setupActionButtons()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatusUI()
        GestureCursorService.requestPipelineRefresh()
    }

    /**
     * Wires the 3 permission buttons and the Android 13+ Restricted Settings helper link.
     */
    private fun setupPermissionButtons() {
        // 1. Camera Permission Button
        binding.btnGrantCamera.setOnClickListener {
            if (!hasCameraPermission()) {
                val perms = mutableListOf(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                cameraPermissionLauncher.launch(perms.toTypedArray())
            } else {
                Toast.makeText(this, "Camera permission is already granted", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. Display Over Other Apps (Overlay) Button
        binding.btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission is already active", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Accessibility Service Toggle Button
        binding.btnGrantAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Clicking the amber warning text jumps straight to App Info to unlock Restricted Settings
        binding.txtRestrictedHelp.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    /**
     * Binds the Sensitivity and 1-Euro Filter Smoothing sliders to CursorSettings.
     */
    private fun setupSliders() {
        // Sensitivity: progress 6..30 maps to 0.6x..3.0x
        val initialSensProgress = (settings.sensitivity * 10f).toInt().coerceIn(6, 30)
        binding.seekBarSensitivity.progress = initialSensProgress
        updateSensitivityLabel(settings.sensitivity)

        binding.seekBarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceAtLeast(6)
                val value = clamped / 10f
                settings.sensitivity = value
                updateSensitivityLabel(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Smoothing Cutoff: progress 2..40 maps to 0.2 Hz..4.0 Hz
        val initialSmoothProgress = (settings.smoothingCutoff * 10f).toInt().coerceIn(2, 40)
        binding.seekBarSmoothing.progress = initialSmoothProgress
        updateSmoothingLabel(settings.smoothingCutoff)

        binding.seekBarSmoothing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceAtLeast(2)
                val value = clamped / 10f
                settings.smoothingCutoff = value
                updateSmoothingLabel(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * Wires the Recenter (◎) button and interactive Pinch-Click test button.
     */
    private fun setupActionButtons() {
        binding.btnRecenter.setOnClickListener {
            if (GestureCursorService.isServiceActive()) {
                GestureCursorService.requestRecenter()
                Toast.makeText(
                    this,
                    "Cursor recentered to current hand posture",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Enable all 3 permissions above to start the cursor",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnTestClickTarget.setOnClickListener {
            testClickCount++
            binding.btnTestClickTarget.text = String.format(
                Locale.US,
                "Pinch Verified! (%d)",
                testClickCount
            )
        }
    }

    /**
     * Checks live OS permission states and updates button colors and labels.
     */
    private fun refreshPermissionStatusUI() {
        val cameraGranted = hasCameraPermission()
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        // Update Button 1: Camera
        if (cameraGranted) {
            binding.btnGrantCamera.text = "Granted ✓"
            binding.btnGrantCamera.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#065F46"))
            binding.btnGrantCamera.setTextColor(Color.parseColor("#34D399"))
        } else {
            binding.btnGrantCamera.text = "Grant"
            binding.btnGrantCamera.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#334155"))
            binding.btnGrantCamera.setTextColor(Color.parseColor("#38BDF8"))
        }

        // Update Button 2: Overlay
        if (overlayGranted) {
            binding.btnGrantOverlay.text = "Granted ✓"
            binding.btnGrantOverlay.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#065F46"))
            binding.btnGrantOverlay.setTextColor(Color.parseColor("#34D399"))
        } else {
            binding.btnGrantOverlay.text = "Grant"
            binding.btnGrantOverlay.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#334155"))
            binding.btnGrantOverlay.setTextColor(Color.parseColor("#38BDF8"))
        }

        // Update Button 3: Accessibility Service
        if (accessibilityEnabled) {
            binding.btnGrantAccessibility.text = "Active ✓"
            binding.btnGrantAccessibility.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#065F46"))
            binding.btnGrantAccessibility.setTextColor(Color.parseColor("#34D399"))
            binding.txtRestrictedHelp.visibility = View.GONE
        } else {
            binding.btnGrantAccessibility.text = "Enable"
            binding.btnGrantAccessibility.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#334155"))
            binding.btnGrantAccessibility.setTextColor(Color.parseColor("#38BDF8"))

            // Show Restricted Settings helper banner on Android 13+ until Accessibility is active
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                binding.txtRestrictedHelp.visibility = View.VISIBLE
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Verifies whether GestureCursorService is enabled in Android's Secure Settings
     * or currently bound in memory.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        if (GestureCursorService.isServiceActive()) return true

        val expectedComponent = ComponentName(this, GestureCursorService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponent) {
                return true
            }
        }
        return false
    }

    private fun updateSensitivityLabel(value: Float) {
        binding.lblSensitivity.text = String.format(
            Locale.US,
            "Sensitivity Multiplier: %.1fx",
            value
        )
    }

    private fun updateSmoothingLabel(value: Float) {
        binding.lblSmoothing.text = String.format(
            Locale.US,
            "Jitter Smoothing Cutoff: %.1f Hz (lower = smoother)",
            value
        )
    }
}
