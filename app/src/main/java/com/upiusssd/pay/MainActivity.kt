package com.upiusssd.pay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    private val requestCallPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initiateUssdDial()
        } else {
            Toast.makeText(this, "CALL_PHONE permission required for USSD", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("upi_ussd_prefs", Context.MODE_PRIVATE)

        setContent {
            UpiUssdApp(
                onPayClicked = { vpa, amount, remarks, sendOpt, upiOpt, mobOpt ->
                    handlePayClick(vpa, amount, remarks, sendOpt, upiOpt, mobOpt)
                },
                isAccessibilityEnabled = isAccessibilityServiceEnabled(),
                onQrScanned = { rawQr ->
                    handleAutoQrDetected(rawQr)
                }
            )
        }
    }

    /**
     * Automatically adjusts the 3-box bank configurator based on scanned VPA handle
     */
    fun handleAutoQrDetected(rawQr: String) {
        val uri = Uri.parse(rawQr)
        val vpa = uri.getQueryParameter("pa") ?: rawQr
        val lower = vpa.lowercase()

        // Auto-Adjust Bank Menu based on handle
        when {
            lower.contains("okhdfcbank") || lower.contains("hdfcbank") -> {
                saveBankConfig("1", "3", "2") // HDFC
                Toast.makeText(this, "Auto-Adjusted for HDFC Bank [1, 3, 2]", Toast.LENGTH_SHORT).show()
            }
            lower.contains("oksbi") || lower.contains("sbi") -> {
                saveBankConfig("1", "3", "1") // SBI
                Toast.makeText(this, "Auto-Adjusted for SBI [1, 3, 1]", Toast.LENGTH_SHORT).show()
            }
            lower.contains("barodampay") || lower.contains("bob") -> {
                saveBankConfig("1", "4", "1") // BoB
                Toast.makeText(this, "Auto-Adjusted for Bank of Baroda [1, 4, 1]", Toast.LENGTH_SHORT).show()
            }
            else -> {
                saveBankConfig("1", "3", "1") // Standard NPCI
            }
        }
    }

    private fun saveBankConfig(send: String, upi: String, mob: String) {
        prefs.edit().apply {
            putString("send_menu", send)
            putString("upi_menu", upi)
            putString("mobile_menu", mob)
            apply()
        }
    }

    private fun handlePayClick(
        vpa: String,
        amount: String,
        remarks: String,
        sendOpt: String,
        upiOpt: String,
        mobOpt: String
    ) {
        // 1. Accessibility Check
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable 'UPI USSD Pay' in Accessibility Settings", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        saveBankConfig(sendOpt, upiOpt, mobOpt)

        // Arm accessibility service
        UpiAccessibilityService.isPaymentActive = true
        UpiAccessibilityService.targetVpa = vpa
        UpiAccessibilityService.targetAmount = amount
        UpiAccessibilityService.targetRemarks = remarks.ifEmpty { "1" }
        UpiAccessibilityService.isMobileMode = vpa.matches(Regex("^\\d{10}$"))
        UpiAccessibilityService.menuSendMoney = sendOpt
        UpiAccessibilityService.menuUpiId = upiOpt
        UpiAccessibilityService.menuMobile = mobOpt
        UpiAccessibilityService.currentStep = 0

        // 2. Call permission and dial
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            initiateUssdDial()
        } else {
            requestCallPermission.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun initiateUssdDial() {
        val ussdUri = Uri.parse("tel:" + Uri.encode("*99#"))
        val intent = Intent(Intent.ACTION_CALL, ussdUri)
        startActivity(intent)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedServiceName = "${packageName}/${UpiAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expectedServiceName)
    }
}
