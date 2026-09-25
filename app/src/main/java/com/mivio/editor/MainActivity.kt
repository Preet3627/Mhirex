package com.mivio.editor

import com.mivio.editor.utils.uriExtraCompat

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Parcelable
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mivio.editor.databinding.ActivityMainBinding
import com.mivio.editor.utils.ErrorCode
import com.mivio.editor.utils.setBounceClickListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var aboutAmbientAnimator: Animator? = null
    private val selectVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    Log.e("VideoSelection", "Could not take persistable permission", e)
                }
                Log.d("VideoSelection", "Video selected: $uri")
                navigateToEditingScreen(uri)
            } else {
                Log.e("VideoSelectionError", "No video selected")
            }
        }

    private val openProjectLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    Log.e("ProjectSelection", "Could not take persistable permission for project URI", e)
                }
                Log.d("ProjectSelection", "Project selected: $uri")
                val intent = Intent(this, ProjectImportActivity::class.java).apply {
                    putExtra("PROJECT_URI", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                startActivity(intent)
            } else {
                Log.e("ProjectSelectionError", "No project selected")
            }
        }

    private val selectFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences(Branding.PREFS_NAME, MODE_PRIVATE)
                    prefs.edit().putString("export_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri, binding.tvCurrentExportFolder, R.string.str_default_movies_mhirex)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    private val selectAudioFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences(Branding.PREFS_NAME, MODE_PRIVATE)
                    prefs.edit().putString("export_audio_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri, binding.tvCurrentAudioExportFolder, R.string.str_default_music_mhirex)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    private val selectSnapshotFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences(Branding.PREFS_NAME, MODE_PRIVATE)
                    prefs.edit().putString("export_snapshot_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri, binding.tvCurrentSnapshotExportFolder, R.string.str_default_pictures_mhirex)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnImport.setBounceClickListener {
            Log.d("ButtonClick", "Launching video selection.")
            selectVideo()
        }

        binding.btnOpenProject.setBounceClickListener {
            Log.d("ButtonClick", "Launching project selection.")
            openProjectLauncher.launch(arrayOf("*/*"))
        }

        // Initialize bottom navigation tab backgrounds
        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val ta = obtainStyledAttributes(attrs)
        val inactiveBg = ta.getDrawable(0)
        ta.recycle()
        binding.tabSettings.background = inactiveBg
        binding.tabAbout.background = inactiveBg

        // Setup bottom navigation tab switching
        binding.tabHome.setBounceClickListener {
            switchTab(0)
        }
        
        binding.tabSettings.setBounceClickListener {
            switchTab(1)
        }

        binding.tabAbout.setBounceClickListener {
            switchTab(2)
        }
        
        // Setup Settings Actions
        binding.btnChangeExportFolder.setBounceClickListener {
            selectFolderLauncher.launch(null)
        }
        binding.btnChangeAudioExportFolder.setBounceClickListener {
            selectAudioFolderLauncher.launch(null)
        }
        binding.btnChangeSnapshotExportFolder.setBounceClickListener {
            selectSnapshotFolderLauncher.launch(null)
        }
        binding.btnChangeLanguage.setBounceClickListener {
            showLanguageDialog()
        }
        
        binding.btnCheckForUpdates.setBounceClickListener {
            checkForUpdates()
        }
        
        binding.btnOpenSourceLicenses.setBounceClickListener {
            com.mikepenz.aboutlibraries.LibsBuilder()
                .withActivityTitle(getString(R.string.str_open_source_licenses))
                .withSearchEnabled(true)
                .start(this)
        }
        
        // Initialize Settings UI
        val prefs = getSharedPreferences(Branding.PREFS_NAME, MODE_PRIVATE)
        val savedUriString = prefs.getString("export_directory_uri", null)
        if (savedUriString != null) {
            updateExportFolderUI(Uri.parse(savedUriString), binding.tvCurrentExportFolder, R.string.str_default_movies_mhirex)
        } else {
            updateExportFolderUI(null, binding.tvCurrentExportFolder, R.string.str_default_movies_mhirex)
        }

        val savedAudioUriString = prefs.getString("export_audio_directory_uri", null)
        if (savedAudioUriString != null) {
            updateExportFolderUI(Uri.parse(savedAudioUriString), binding.tvCurrentAudioExportFolder, R.string.str_default_music_mhirex)
        } else {
            updateExportFolderUI(null, binding.tvCurrentAudioExportFolder, R.string.str_default_music_mhirex)
        }

        val savedSnapshotUriString = prefs.getString("export_snapshot_directory_uri", null)
        if (savedSnapshotUriString != null) {
            updateExportFolderUI(Uri.parse(savedSnapshotUriString), binding.tvCurrentSnapshotExportFolder, R.string.str_default_pictures_mhirex)
        } else {
            updateExportFolderUI(null, binding.tvCurrentSnapshotExportFolder, R.string.str_default_pictures_mhirex)
        }

        updateLanguageUI()

        // Initialize Haptic Feedback preference
        updateHapticFeedbackUI()

        binding.btnToggleHapticFeedback.setBounceClickListener {
            val current = prefs.getBoolean("haptic_feedback", true)
            prefs.edit().putBoolean("haptic_feedback", !current).apply()
            updateHapticFeedbackUI()
        }

        // Initialize Fullscreen Editor preference
        updateFullscreenEditorUI()

        binding.btnToggleFullscreenEditor.setBounceClickListener {
            val current = prefs.getBoolean("fullscreen_editor", true)
            prefs.edit().putBoolean("fullscreen_editor", !current).apply()
            updateFullscreenEditorUI()
        }

        // Initialize Default Encoder preference
        updateEncoderUI()

        binding.btnChangeDefaultEncoder.setBounceClickListener {
            showEncoderDialog()
        }

        // Set dynamic About version tag
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvAboutVersion.text = "v${pInfo.versionName}"
        } catch (e: Exception) {
            binding.tvAboutVersion.text = "v1.0-beta7"
        }

        // Setup About, social and support actions
        binding.btnStarGithub.setBounceClickListener {
            openUrl(Branding.REPO_URL)
        }
        binding.btnReportBug.setBounceClickListener {
            openUrl(Branding.REPO_ISSUES)
        }
        binding.layoutIdeaInstagram.setBounceClickListener {
            openUrl(Branding.INSTAGRAM_IDEA_URL)
        }
        binding.layoutMehulInstagram.setBounceClickListener {
            openUrl(Branding.INSTAGRAM_MEHUL_URL)
        }

        // A short welcome appears each time MhireX starts so the people behind
        // the app are always visible without taking away the main workspace.
        binding.root.post { showOnboardingDialog() }

        // Handle shared/intent videos
        handleIntent(intent)
    }

    private fun updateExportFolderUI(uri: Uri?, textView: TextView, defaultStringResId: Int) {
        if (uri == null) {
            textView.text = getString(defaultStringResId)
        } else {
            try {
                val path = uri.lastPathSegment?.split(":")?.lastOrNull()
                if (!path.isNullOrEmpty()) {
                    textView.text = path
                } else {
                    textView.text = getString(R.string.str_custom_directory)
                }
            } catch (e: Exception) {
                textView.text = getString(R.string.str_custom_directory)
            }
        }
    }

    private data class LanguageItem(
        val tag: String,
        val displayName: String
    )

    private fun getAvailableLanguages(): List<LanguageItem> {
        val result = mutableListOf<LanguageItem>()
        result.add(LanguageItem("", getString(R.string.str_system_default)))

        val tags = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeConfig = android.app.LocaleConfig(this)
                val locales = localeConfig.supportedLocales
                if (locales != null) {
                    for (i in 0 until locales.size()) {
                        val locale = locales.get(i)
                        if (locale != null) {
                            tags.add(locale.toLanguageTag())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "LocaleConfig error", e)
            }
        }

        if (tags.isEmpty()) {
            try {
                val resId = resources.getIdentifier("_generated_res_locale_config", "xml", packageName)
                if (resId != 0) {
                    val parser = resources.getXml(resId)
                    var eventType = parser.eventType
                    while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "locale") {
                            val name = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                            if (!name.isNullOrEmpty()) {
                                tags.add(name)
                            }
                        }
                        eventType = parser.next()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "XmlParser _generated_res_locale_config error", e)
            }
        }

        if (tags.isEmpty()) {
            tags.addAll(listOf("en", "de", "et", "sk", "pt-BR"))
        }

        val items = tags.map { tag ->
            val locale = java.util.Locale.forLanguageTag(tag)
            val name = when (tag.lowercase()) {
                "pt-br" -> "Português (Brasil)"
                "zh-cn" -> "中文 (简体)"
                "zh-tw" -> "中文 (繁體)"
                else -> locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            }
            LanguageItem(tag, name)
        }.sortedBy { it.displayName.lowercase() }

        result.addAll(items)
        return result
    }

    private fun updateLanguageUI() {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (currentLocales.isEmpty) {
            binding.tvCurrentLanguage.text = getString(R.string.str_system_default)
        } else {
            val locale = currentLocales.get(0)
            val tag = locale?.toLanguageTag() ?: ""
            val name = when (tag.lowercase()) {
                "pt-br" -> "Português (Brasil)"
                else -> locale?.getDisplayName(locale)?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            }
            binding.tvCurrentLanguage.text = name ?: getString(R.string.str_system_default)
        }
    }

    private fun updateHapticFeedbackUI() {
        val prefs = getSharedPreferences(Branding.PREFS_NAME, MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("haptic_feedback", true)
        binding.tvCurrentHapticFeedback.text = if (isEnabled) {
            getString(R.string.str_haptic_enabled_desc)
        } else {
            getString(R.string.str_haptic_disabled_desc)
        }
    }

    private fun updateFullscreenEditorUI() {
        val prefs = getSharedPreferences(Branding.PREFS_NAME, MODE_PRIVATE)
        val isFullscreen = prefs.getBoolean("fullscreen_editor", true)
        binding.tvCurrentFullscreenEditor.text = if (isFullscreen) {
            getString(R.string.str_fullscreen_enabled_desc)
        } else {
            getString(R.string.str_fullscreen_disabled_desc)
        }
    }

    private fun updateEncoderUI() {
        val prefs = getSharedPreferences(Branding.PREFS_NAME, MODE_PRIVATE)
        val defaultEncoder = prefs.getString("default_encoder", "hardware") ?: "hardware"
        if (defaultEncoder == "software") {
            binding.tvCurrentDefaultEncoder.text = getString(R.string.str_encoder_software)
        } else {
            binding.tvCurrentDefaultEncoder.text = getString(R.string.str_encoder_hardware)
        }
    }

    private fun showEncoderDialog() {
        val prefs = getSharedPreferences(Branding.PREFS_NAME, MODE_PRIVATE)
        val currentEncoder = prefs.getString("default_encoder", "hardware") ?: "hardware"
        val options = arrayOf(
            getString(R.string.str_encoder_hardware),
            getString(R.string.str_encoder_software)
        )
        val selectedIndex = if (currentEncoder == "software") 1 else 0

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.str_default_encoder)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val chosenEncoder = if (which == 1) "software" else "hardware"
                prefs.edit().putString("default_encoder", chosenEncoder).apply()
                updateEncoderUI()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.language_bottom_sheet_dialog, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.btnCloseSheet)?.setBounceClickListener {
            dialog.dismiss()
        }

        val container = view.findViewById<android.widget.LinearLayout>(R.id.layoutLanguageContainer)
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (currentLocales.isEmpty) "" else (currentLocales.get(0)?.toLanguageTag() ?: "")

        val availableLanguages = getAvailableLanguages()

        for (item in availableLanguages) {
            val itemView = layoutInflater.inflate(R.layout.item_language_selection, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tvLanguageName)
            val ivCheck = itemView.findViewById<android.widget.ImageView>(R.id.ivCheckLanguage)

            tvName.text = item.displayName

            val isSelected = if (item.tag.isEmpty()) {
                currentTag.isEmpty()
            } else {
                currentTag.equals(item.tag, ignoreCase = true) ||
                (item.tag.length == 2 && currentTag.startsWith(item.tag, ignoreCase = true))
            }

            ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            itemView.setBounceClickListener {
                val appLocale = if (item.tag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(item.tag)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)
                updateLanguageUI()
                dialog.dismiss()
            }

            container?.addView(itemView)
        }

        dialog.show()
    }

    private fun switchTab(tabIndex: Int) {
        if (tabIndex != 2) {
            stopAboutAnimations()
        }

        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_nav_active_pill)
        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val ta = obtainStyledAttributes(attrs)
        val inactiveBg = ta.getDrawable(0)
        ta.recycle()

        val activeColor = ContextCompat.getColor(this, R.color.colorPrimary)
        val inactiveColor = ContextCompat.getColor(this, R.color.inactiveTool)

        // Reset all tabs to inactive
        binding.layoutHomeContent.visibility = View.GONE
        binding.layoutSettingsContent.visibility = View.GONE
        binding.layoutAboutContent.visibility = View.GONE

        binding.tabHome.background = inactiveBg
        binding.ivHome.setColorFilter(inactiveColor)
        binding.tvHomeLabel.setTextColor(inactiveColor)

        binding.tabSettings.background = inactiveBg
        binding.ivSettings.setColorFilter(inactiveColor)
        binding.tvSettingsLabel.setTextColor(inactiveColor)

        binding.tabAbout.background = inactiveBg
        binding.ivAbout.setColorFilter(inactiveColor)
        binding.tvAboutLabel.setTextColor(inactiveColor)

        when (tabIndex) {
            0 -> {
                binding.layoutHomeContent.visibility = View.VISIBLE
                binding.tabHome.background = activeBg
                binding.ivHome.setColorFilter(activeColor)
                binding.tvHomeLabel.setTextColor(activeColor)
            }
            1 -> {
                binding.layoutSettingsContent.visibility = View.VISIBLE
                binding.tabSettings.background = activeBg
                binding.ivSettings.setColorFilter(activeColor)
                binding.tvSettingsLabel.setTextColor(activeColor)
            }
            2 -> {
                binding.layoutAboutContent.visibility = View.VISIBLE
                binding.tabAbout.background = activeBg
                binding.ivAbout.setColorFilter(activeColor)
                binding.tvAboutLabel.setTextColor(activeColor)
                binding.layoutAboutContent.post { animateAboutContent() }
            }
        }
    }

    private fun animateAboutContent() {
        stopAboutAnimations()

        val entranceViews = listOf(
            binding.aboutHeroCard,
            binding.layoutIdeaInstagram,
            binding.layoutMehulInstagram,
            binding.aboutSupportCard
        )
        entranceViews.forEach { view ->
            view.alpha = 0f
            view.translationY = 40f
            view.scaleX = 0.97f
            view.scaleY = 0.97f
        }
        entranceViews.forEachIndexed { index, view ->
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(520)
                .setStartDelay(index * 85L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                .start()
        }

        binding.aboutHeroLogo.animate()
            .scaleX(1.08f)
            .scaleY(1.08f)
            .setDuration(950)
            .setInterpolator(android.view.animation.OvershootInterpolator())
            .withEndAction {
                binding.aboutHeroLogo.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .start()
            }
            .start()

        val pinkDriftAnimators = listOf(
            ObjectAnimator.ofFloat(binding.aboutOrbOne, View.TRANSLATION_X, 0f, 34f),
            ObjectAnimator.ofFloat(binding.aboutOrbOne, View.TRANSLATION_Y, 0f, 24f),
            ObjectAnimator.ofFloat(binding.aboutOrbOne, View.SCALE_X, 0.9f, 1.12f),
            ObjectAnimator.ofFloat(binding.aboutOrbOne, View.SCALE_Y, 0.9f, 1.12f)
        ).onEach {
            it.duration = 3600
            it.repeatCount = ValueAnimator.INFINITE
            it.repeatMode = ValueAnimator.REVERSE
        }
        val pinkDrift = AnimatorSet().apply { playTogether(*pinkDriftAnimators.toTypedArray()) }

        val cyanDriftAnimators = listOf(
            ObjectAnimator.ofFloat(binding.aboutOrbTwo, View.TRANSLATION_X, 0f, -28f),
            ObjectAnimator.ofFloat(binding.aboutOrbTwo, View.TRANSLATION_Y, 0f, 32f),
            ObjectAnimator.ofFloat(binding.aboutOrbTwo, View.SCALE_X, 1.05f, 0.88f),
            ObjectAnimator.ofFloat(binding.aboutOrbTwo, View.SCALE_Y, 1.05f, 0.88f)
        ).onEach {
            it.duration = 4300
            it.repeatCount = ValueAnimator.INFINITE
            it.repeatMode = ValueAnimator.REVERSE
        }
        val cyanDrift = AnimatorSet().apply { playTogether(*cyanDriftAnimators.toTypedArray()) }
        aboutAmbientAnimator = AnimatorSet().apply {
            playTogether(pinkDrift, cyanDrift)
            start()
        }
    }

    private fun stopAboutAnimations() {
        aboutAmbientAnimator?.cancel()
        aboutAmbientAnimator = null
        listOf(
            binding.aboutHeroCard,
            binding.layoutIdeaInstagram,
            binding.layoutMehulInstagram,
            binding.aboutSupportCard,
            binding.aboutHeroLogo
        ).forEach { it.animate().cancel() }
    }

    override fun onDestroy() {
        stopAboutAnimations()
        super.onDestroy()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            showToast("Unable to open link")
        }
    }





    private fun selectVideo() {
        Log.d("VideoSelection", "Launching video picker.")
        val picker = com.mivio.editor.customviews.MediaPickerBottomSheet().apply {
            initialMediaType = com.mivio.editor.customviews.MediaPickerBottomSheet.MediaType.VIDEO
            showCategoryTabs = true
            showAudioTab = false
            onMediaSelectedListener = { uri ->
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    Log.d("VideoSelection", "Could not take persistable permission: ${e.message}")
                }
                navigateToEditingScreen(uri)
            }
            onBrowseSystemFoldersRequested = {
                selectVideoLauncher.launch(arrayOf("video/*", "image/*"))
            }
        }
        picker.show(supportFragmentManager, "MediaPickerBottomSheet")
    }

    private fun navigateToEditingScreen(videoUri: Uri) {
        Log.d("Navigation", "Navigating to editing screen with URI: $videoUri")
        val intent = Intent(this, VideoEditingActivity::class.java).apply {
            putExtra("VIDEO_URI", videoUri)
            data = videoUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("video/") || type.startsWith("image/")) {
                (intent.uriExtraCompat(Intent.EXTRA_STREAM))?.let { uri ->
                    Log.d("SharedVideo", "Received SEND intent with media URI: $uri")
                    navigateToEditingScreen(uri)
                }
            }
        } else if ((Intent.ACTION_VIEW == action || Intent.ACTION_EDIT == action) && type != null) {
            if (type.startsWith("video/") || type.startsWith("image/")) {
                intent.data?.let { uri ->
                    Log.d("SharedVideo", "Received VIEW/EDIT intent with media URI: $uri")
                    navigateToEditingScreen(uri)
                }
            }
        }
    }

    private fun showOnboardingDialog() {
        val dialog = android.app.Dialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_welcome_onboarding, null)
        dialog.setContentView(view)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.78f)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = 48
                }
            }

            window.attributes = window.attributes.apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        val tvVersion = view.findViewById<TextView>(R.id.tvOnboardingVersion)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "Version ${pInfo.versionName}"
        } catch (e: Exception) {
            tvVersion.text = "Version 1.0-beta7"
        }

        view.findViewById<View>(R.id.layoutIdeaInstagram)?.setBounceClickListener {
            openUrl(Branding.INSTAGRAM_IDEA_URL)
        }
        view.findViewById<View>(R.id.layoutMehulInstagram)?.setBounceClickListener {
            openUrl(Branding.INSTAGRAM_MEHUL_URL)
        }
        view.findViewById<View>(R.id.layoutStarGithub)?.setBounceClickListener {
            openUrl(Branding.REPO_URL)
        }
        view.findViewById<View>(R.id.btnOnboardingGetStarted)?.setBounceClickListener {
            dialog.dismiss()
        }

        val animatedCards = listOf(
            view.findViewById<View>(R.id.layoutIdeaInstagram),
            view.findViewById<View>(R.id.layoutMehulInstagram),
            view.findViewById<View>(R.id.layoutStarGithub)
        )
        animatedCards.forEachIndexed { index, card ->
            card.alpha = 0f
            card.translationY = 28f
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(440)
                .setStartDelay(180L + index * 80L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f))
                .start()
        }

        val logo = view.findViewById<View>(R.id.ivOnboardingLogo)
        logo.scaleX = 0.72f
        logo.scaleY = 0.72f
        logo.rotation = -8f
        logo.animate()
            .scaleX(1f)
            .scaleY(1f)
            .rotation(0f)
            .setDuration(720)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
            .start()

        val orbOne = view.findViewById<View>(R.id.onboardingOrbOne)
        val orbTwo = view.findViewById<View>(R.id.onboardingOrbTwo)
        val onboardingDrift = listOf(
            ObjectAnimator.ofFloat(orbOne, View.TRANSLATION_X, 0f, 22f),
            ObjectAnimator.ofFloat(orbOne, View.TRANSLATION_Y, 0f, 16f),
            ObjectAnimator.ofFloat(orbTwo, View.TRANSLATION_X, 0f, -20f),
            ObjectAnimator.ofFloat(orbTwo, View.TRANSLATION_Y, 0f, 22f)
        ).onEachIndexed { index, animator ->
            animator.duration = if (index < 2) 3200 else 3800
            animator.repeatCount = ValueAnimator.INFINITE
            animator.repeatMode = ValueAnimator.REVERSE
        }
        val ambientAnimation = AnimatorSet().apply {
            playTogether(*onboardingDrift.toTypedArray())
        }
        ambientAnimation.start()
        dialog.setOnDismissListener { ambientAnimation.cancel() }
    }

    private fun showToast(message: String) {
        Log.d("ToastMessage", "Showing toast: $message")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun checkForUpdates() {
        showToast("Checking for updates in browser...")
        openUrl(Branding.REPO_RELEASES)
    }
}
