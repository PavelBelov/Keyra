package app.keyra

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri

/** Public information only. The installed package supplies version and application ID. */
internal fun AppCompatActivity.showAppInfo(show: (AlertDialog.Builder) -> AlertDialog) {
    val activity = this
    @Suppress("DEPRECATION")
    val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
    } else packageManager.getPackageInfo(packageName, 0)
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, dp(12), dp(12))
    }
    fun text(title: Int, value: String) {
        if (value.isBlank()) return
        body.addView(TextView(activity).apply {
            this.text = settingsText(getString(title), value)
            textSize = 16f
            setTextColor(getColor(R.color.settings_text))
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        })
    }
    fun link(title: Int, url: String) {
        if (url.isBlank()) return
        body.addView(settingsAction(getString(title), url) {
            try { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.about_link_unavailable, Toast.LENGTH_LONG).show()
            }
        })
    }
    text(R.string.about_version, "${info.versionName} (${info.longVersionCode})")
    text(R.string.about_application_id, packageName)
    text(R.string.about_developer, getString(R.string.app_developer_name))
    text(R.string.about_license, getString(R.string.app_license_spdx))
    val website = getString(R.string.app_project_website)
    val source = getString(R.string.app_source_url)
    link(R.string.about_project, website)
    if (source != website) link(R.string.about_source, source)
    link(R.string.about_report_issue, getString(R.string.app_issues_url))
    val email = getString(R.string.app_support_email)
    if (email.isNotBlank()) link(R.string.about_support, "mailto:${android.net.Uri.encode(email, "@")}")
    link(R.string.about_privacy, getString(R.string.app_privacy_url))
    body.addView(settingsAction(getString(R.string.about_licenses)) {
        val files = assets.list("licenses").orEmpty().sorted()
        show(AlertDialog.Builder(activity).setTitle(R.string.about_licenses)
            .setItems(files.toTypedArray()) { _, index ->
                val licenseText = assets.open("licenses/${files[index]}").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val content = TextView(activity).apply {
                    text = licenseText; textSize = 14f
                    setTextColor(getColor(R.color.settings_text))
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    setTextIsSelectable(true)
                }
                show(AlertDialog.Builder(activity).setTitle(files[index])
                    .setView(ScrollView(activity).apply { addView(content) })
                    .setPositiveButton(android.R.string.ok, null))
            }.setNegativeButton(android.R.string.cancel, null))
    })
    body.addView(TextView(activity).apply {
        setText(R.string.about_warranty)
        textSize = 13f; setTextColor(getColor(R.color.settings_secondary))
        setPadding(dp(16), dp(12), dp(16), dp(12))
    })
    show(AlertDialog.Builder(activity).setTitle(R.string.about_title)
        .setView(ScrollView(activity).apply { addView(body) })
        .setPositiveButton(android.R.string.ok, null))
}
