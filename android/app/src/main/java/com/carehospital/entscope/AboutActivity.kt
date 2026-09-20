package com.carehospital.entscope

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.carehospital.entscope.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    companion object {
        const val SUPPORT_PHONE = "+91 9370111449"
        const val WEBSITE = "https://www.carehospital.in"
    }

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "3.0.0"
        }

        binding.versionLine.text = "Android scope viewer   -   v$version"

        binding.technicalText.text = buildString {
            appendLine("Camera transport")
            appendLine(
                "Bebird-type Wi-Fi ENT scope stream received over UDP. " +
                    "Default endpoint: 192.168.10.123 : 8030."
            )
            appendLine()
            appendLine("Network")
            appendLine(
                "The app binds its socket to the Wi-Fi network so that scope " +
                    "traffic is not routed to mobile data. If there is no picture, " +
                    "confirm the phone has joined the scope's Wi-Fi access point."
            )
            appendLine()
            appendLine("Media")
            appendLine(
                "Snapshots are saved as JPEG and recordings as H.264 MP4, both in " +
                    "the phone's media library under CARE ENT Scope."
            )
            appendLine()
            appendLine("Support")
            append("$SUPPORT_PHONE   -   www.carehospital.in")
        }

        binding.btnWebsite.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)))
            } catch (e: Exception) {
                Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCopySupport.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("support", SUPPORT_PHONE))
            Toast.makeText(this, R.string.support_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnCloseAbout.setOnClickListener { finish() }
    }
}
