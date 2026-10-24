package com.termux.styling

import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.util.Linkify
import android.util.AtomicFile
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * A color scheme or a font. [fileName] is the file in the assets, or in the imported folder when [imported] is set,
 * {@code null} for the default. [isImport] marks the row that opens the file picker to import a new file.
 */
data class Choice(
    val fileName: String?,
    val title: String,
    val isColor: Boolean,
    val imported: Boolean = false,
    val isImport: Boolean = false,
)

/** The colors of a color scheme that are shown in the preview. */
class Palette(val background: Int, val foreground: Int, val ansi: IntArray)

/** Which color schemes the list shows, by how dark their background is. */
private enum class Brightness { ALL, LIGHT, DARK }

/** The Termux default colors, used by the "Termux default" choice. */
private val DEFAULT_PALETTE = Palette(
    background = 0xff000000.toInt(),
    foreground = 0xffffffff.toInt(),
    ansi = intArrayOf(
        0xff000000.toInt(), 0xffcd0000.toInt(), 0xff00cd00.toInt(), 0xffcdcd00.toInt(),
        0xff6495ed.toInt(), 0xffcd00cd.toInt(), 0xff00cdcd.toInt(), 0xffe5e5e5.toInt(),
        0xff7f7f7f.toInt(), 0xffff0000.toInt(), 0xff00ff00.toInt(), 0xffffff00.toInt(),
        0xff5c5cff.toInt(), 0xffff00ff.toInt(), 0xff00ffff.toInt(), 0xffffffff.toInt(),
    ),
)

private const val PREFS_NAME = "style"
private const val PREF_COLOR = "color"
private const val PREF_FONT = "font"
private const val PREF_COLOR_TITLE = "color_title"
private const val PREF_FONT_TITLE = "font_title"
private const val STYLE_PROPERTIES_FILE = "style.properties"

/** Below this contrast between text and background an imported color scheme is hard to read. */
private const val LOW_CONTRAST = 3.0

fun capitalize(str: String): String {
    var lastWhitespace = true
    val chars = str.toCharArray()
    for (i in chars.indices) {
        if (Character.isLetter(chars[i])) {
            if (lastWhitespace) chars[i] = Character.toUpperCase(chars[i])
            lastWhitespace = false
        } else {
            lastWhitespace = Character.isWhitespace(chars[i])
        }
    }
    return String(chars)
}

class TermuxStyleActivity : AppCompatActivity() {

    private val colorChoices = ArrayList<Choice>()
    private val fontChoices = ArrayList<Choice>()
    private val paletteCache = HashMap<String, Palette>()
    private val typefaceCache = HashMap<String, Typeface>()

    /** What the user picked in the preview, and what was last written to Termux. */
    private var selectedColor = Choice(null, "", true)
    private var selectedFont = Choice(null, "", false)
    private var appliedColor = selectedColor
    private var appliedFont = selectedFont

    /** Whether the list shows color schemes or fonts, and the search text and brightness filter for colors. */
    private var colorsMode = true
    private var query = ""
    private var brightness = Brightness.ALL

    /** Whether the file picker was opened from the color list or from the font list. */
    private var importingColor = true

    /** The choice whose file is being saved, while the save dialog is open. */
    private var pendingExport: Choice? = null

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri, importingColor)
    }

    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val choice = pendingExport
        pendingExport = null
        if (uri != null && choice != null) exportChoice(choice, uri)
    }

    private lateinit var previewCard: MaterialCardView
    private lateinit var previewLabel: TextView
    private lateinit var previewText: TextView
    private lateinit var previewBar: ColorBarView
    private lateinit var typographyLabel: TextView
    private lateinit var typographyText: TextView
    private lateinit var colorsButton: MaterialButton
    private lateinit var fontsButton: MaterialButton
    private lateinit var searchField: TextInputEditText
    private lateinit var filterGroup: ChipGroup
    private lateinit var applyButton: MaterialButton
    private lateinit var optionsList: RecyclerView
    private val adapter = OptionAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Use the wallpaper colors on Android 12 and newer, as the Termux app does.
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)

        // Draw behind the status bar. The root view is padded by the system bars, so nothing sits under them.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_style)

        val root: View = findViewById(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        previewCard = findViewById(R.id.preview_card)
        previewLabel = findViewById(R.id.preview_label)
        previewText = findViewById(R.id.preview_text)
        previewBar = findViewById(R.id.preview_bar)
        typographyLabel = findViewById(R.id.typography_label)
        typographyText = findViewById(R.id.typography_text)
        colorsButton = findViewById(R.id.mode_colors)
        fontsButton = findViewById(R.id.mode_fonts)
        searchField = findViewById(R.id.search_field)
        filterGroup = findViewById(R.id.filter_group)
        applyButton = findViewById(R.id.apply_button)
        optionsList = findViewById(R.id.options_list)

        reloadChoices()

        // Start with the color scheme and font that were applied last time, checking ~/.termux/style.properties
        // first, then falling back to preferences.
        val styleProps = loadStyleProperties()
        val prefs = stylePrefs()
        val savedColorKey = styleProps?.getProperty(PREF_COLOR)?.trim() ?: prefs.getString(PREF_COLOR, null)
        val savedColorTitle = styleProps?.getProperty(PREF_COLOR_TITLE)?.trim() ?: prefs.getString(PREF_COLOR_TITLE, null)
        val savedFontKey = styleProps?.getProperty(PREF_FONT)?.trim() ?: prefs.getString(PREF_FONT, null)
        val savedFontTitle = styleProps?.getProperty(PREF_FONT_TITLE)?.trim() ?: prefs.getString(PREF_FONT_TITLE, null)

        selectedColor = choiceFor(colorChoices, savedColorKey, savedColorTitle) ?: defaultColor()
        appliedColor = selectedColor
        selectedFont = choiceFor(fontChoices, savedFontKey, savedFontTitle) ?: defaultFont()
        appliedFont = selectedFont
        // Ensure style.properties and preference titles are created if missing.
        val hasStyleProperties = styleProps?.containsKey(PREF_COLOR_TITLE) == true && styleProps.containsKey(PREF_FONT_TITLE)
        if (!hasStyleProperties || !prefs.contains(PREF_COLOR_TITLE) || !prefs.contains(PREF_FONT_TITLE)) saveApplied()

        // Without a layout manager the list shows nothing at all.
        optionsList.layoutManager = LinearLayoutManager(this)
        optionsList.adapter = adapter
        showMode(colors = true)

        colorsButton.setOnClickListener { showMode(colors = true) }
        fontsButton.setOnClickListener { showMode(colors = false) }
        applyButton.setOnClickListener { applySelection() }

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                applyFilters()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            brightness = when (checkedIds.firstOrNull()) {
                R.id.chip_light -> Brightness.LIGHT
                R.id.chip_dark -> Brightness.DARK
                else -> Brightness.ALL
            }
            applyFilters()
        }

        updatePreview()
    }

    private fun defaultColor() = Choice(null, getString(R.string.default_color), true)

    private fun defaultFont() = Choice(null, getString(R.string.default_font), false)

    /** Rebuild both lists: the import row, the imported files, then the built-in choices. */
    private fun reloadChoices() {
        colorChoices.clear()
        colorChoices.add(Choice(null, getString(R.string.import_color), true, isImport = true))
        colorChoices.addAll(listImported(true))
        colorChoices.add(defaultColor())
        colorChoices.addAll(listAssets("colors", ".properties", true))

        fontChoices.clear()
        fontChoices.add(Choice(null, getString(R.string.import_font), false, isImport = true))
        fontChoices.addAll(listImported(false))
        fontChoices.add(defaultFont())
        fontChoices.addAll(listAssets("fonts", ".ttf", false))
    }

    /** Show the color schemes or the fonts in the list, and mark the button of that row. */
    private fun showMode(colors: Boolean) {
        colorsMode = colors
        filterGroup.visibility = if (colors) View.VISIBLE else View.GONE
        styleModeButton(colorsButton, colors)
        styleModeButton(fontsButton, !colors)
        applyFilters()
    }

    /** Show the list for the current mode, narrowed by the search text and, for color schemes, by brightness. */
    private fun applyFilters() {
        val source = if (colorsMode) colorChoices else fontChoices
        val search = query.trim()
        val visible = source.filter { choice ->
            if (choice.isImport) return@filter true
            if (search.isNotEmpty() && !choice.title.contains(search, ignoreCase = true)) return@filter false
            if (choice.isColor && brightness != Brightness.ALL) {
                val dark = isDark(paletteFor(choice))
                return@filter (brightness == Brightness.DARK) == dark
            }
            true
        }
        adapter.submit(visible)
    }

    private fun styleModeButton(button: MaterialButton, selected: Boolean) {
        if (selected) {
            val container = MaterialColors.getColor(button, com.google.android.material.R.attr.colorSecondaryContainer, Color.DKGRAY)
            val onContainer = MaterialColors.getColor(button, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.WHITE)
            button.backgroundTintList = ColorStateList.valueOf(container)
            button.setTextColor(onContainer)
            button.strokeWidth = 0
        } else {
            val onSurface = MaterialColors.getColor(button, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
            val outline = MaterialColors.getColor(button, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
            button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            button.setTextColor(onSurface)
            button.strokeColor = ColorStateList.valueOf(outline)
            button.strokeWidth = (resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }
    }

    private fun listAssets(folder: String, extension: String, isColor: Boolean): List<Choice> {
        val files = try {
            assets.list(folder)?.filter { it.endsWith(extension) } ?: emptyList()
        } catch (e: IOException) {
            emptyList()
        }
        return files.sortedWith(String.CASE_INSENSITIVE_ORDER).map { Choice(it, displayName(it), isColor) }
    }

    /** The folder where imported color schemes or fonts are kept, inside the app's private storage. */
    private fun importDir(colors: Boolean): File =
        File(File(filesDir, "imported"), if (colors) "colors" else "fonts")

    private fun listImported(isColor: Boolean): List<Choice> {
        val names = importDir(isColor).list()?.filter { isAllowedName(it, isColor) } ?: return emptyList()
        return names.sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { Choice(it, displayName(it), isColor, imported = true) }
    }

    private fun isAllowedName(name: String, isColor: Boolean): Boolean {
        val lower = name.lowercase()
        return if (isColor) lower.endsWith(".properties") else lower.endsWith(".ttf") || lower.endsWith(".otf")
    }

    private fun displayName(fileName: String): String {
        val name = fileName.substringBeforeLast('.').replace('-', ' ').replace('_', ' ')
        return capitalize(name)
    }

    /** Open the file of a choice: the imported folder for imported files, the assets for the rest. */
    private fun openChoice(choice: Choice): InputStream {
        val name = choice.fileName ?: throw IOException("No file for the default choice")
        return if (choice.imported) {
            File(importDir(choice.isColor), name).inputStream()
        } else {
            assets.open((if (choice.isColor) "colors/" else "fonts/") + name)
        }
    }

    /** Cache key that keeps built-in and imported files with the same name apart. */
    private fun cacheKey(choice: Choice): String = (if (choice.imported) "imported:" else "asset:") + choice.fileName

    /** The colors of a color scheme. The default has no file and uses the Termux colors. */
    private fun paletteFor(choice: Choice): Palette {
        if (choice.fileName == null) return DEFAULT_PALETTE
        return paletteCache.getOrPut(cacheKey(choice)) { loadPalette(choice) }
    }

    private fun loadPalette(choice: Choice): Palette {
        return try {
            val props = Properties()
            openChoice(choice).use { props.load(it) }
            paletteFrom(props)
        } catch (e: IOException) {
            DEFAULT_PALETTE
        }
    }

    /** The palette described by the properties. A color that is missing or invalid uses the Termux default. */
    private fun paletteFrom(props: Properties): Palette {
        fun read(key: String, fallback: Int): Int =
            props.getProperty(key)?.trim()?.let { parseColor(it) } ?: fallback

        return Palette(
            background = read("background", DEFAULT_PALETTE.background),
            foreground = read("foreground", DEFAULT_PALETTE.foreground),
            ansi = IntArray(16) { read("color$it", DEFAULT_PALETTE.ansi[it]) },
        )
    }

    private fun parseColor(value: String): Int? = try {
        Color.parseColor(value)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Relative luminance of a color, from 0 (black) to 1 (white), as in the WCAG contrast formula. */
    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
    }

    /** The WCAG contrast ratio between two colors, from 1 (no contrast) to 21 (black on white). */
    private fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** A background is dark when white text has more contrast on it than black text. */
    private fun isDark(palette: Palette): Boolean = luminance(palette.background) < 0.179

    /** The typeface of a font, the system monospace for the default. */
    private fun typefaceFor(choice: Choice): Typeface {
        if (choice.fileName == null) return Typeface.MONOSPACE
        return typefaceCache.getOrPut(cacheKey(choice)) {
            try {
                if (choice.imported) {
                    Typeface.createFromFile(File(importDir(false), choice.fileName).path)
                } else {
                    Typeface.createFromAsset(assets, "fonts/${choice.fileName}")
                }
            } catch (e: RuntimeException) {
                Typeface.MONOSPACE
            }
        }
    }

    private fun updatePreview() {
        val palette = paletteFor(selectedColor)
        val typeface = typefaceFor(selectedFont)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)

        previewCard.setCardBackgroundColor(palette.background)
        previewLabel.text = getString(R.string.preview_label, selectedColor.title)
        previewText.typeface = typeface
        previewText.text = terminalSample(palette)
        previewBar.setColors(palette.ansi)

        typographyLabel.text = getString(R.string.typography_label, selectedFont.title)
        typographyText.typeface = typeface
        typographyText.text = getString(R.string.typography_sample)
        typographyText.setTextColor(onSurface)

        updateApplyButton()
    }

    /** A small sample of terminal output, colored with the palette. */
    private fun terminalSample(palette: Palette): CharSequence {
        val text = SpannableStringBuilder()

        fun add(value: String, color: Int, bold: Boolean = false) {
            val start = text.length
            text.append(value)
            text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val fg = palette.foreground
        add("user@termux", palette.ansi[2], bold = true)
        add(":", fg)
        add("~", palette.ansi[4], bold = true)
        add("$ ls -l\n", fg)
        add("drwxr-xr-x  ", fg)
        add("projects\n", palette.ansi[4], bold = true)
        add("-rw-r--r--  ", fg)
        add("notes.txt\n", fg)
        add("ok     ", palette.ansi[2])
        add("tests passed\n", fg)
        add("warning  ", palette.ansi[3])
        add("disk is almost full\n", fg)
        add("error    ", palette.ansi[1], bold = true)
        add("missing file\n", fg)
        add("info     ", palette.ansi[6])
        add("build finished in 4s", fg)
        return text
    }

    private fun updateApplyButton() {
        applyButton.isEnabled = selectedColor != appliedColor || selectedFont != appliedFont
    }

    private fun stylePrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The .termux directory in the Termux home folder, or null if Termux is not installed. */
    private fun termuxDir(): File? = try {
        val context = createPackageContext("com.termux", Context.CONTEXT_IGNORE_SECURITY)
        val homeDir = File(context.filesDir, "home")
        val dir = File(homeDir, ".termux")
        if (dir.isDirectory || dir.mkdirs()) dir else null
    } catch (e: Exception) {
        null
    }

    /** The style.properties file in ~/.termux, or null if ~/.termux cannot be accessed. */
    private fun stylePropertiesFile(): File? {
        val dir = termuxDir() ?: return null
        return File(dir, STYLE_PROPERTIES_FILE).canonicalFile
    }

    /** Read style.properties from ~/.termux, or null if the file does not exist or cannot be read. */
    private fun loadStyleProperties(): Properties? {
        val file = stylePropertiesFile() ?: return null
        if (!file.isFile) return null
        return try {
            file.inputStream().use { stream ->
                Properties().apply { load(stream) }
            }
        } catch (e: Exception) {
            Log.w("termux", "Failed to read $STYLE_PROPERTIES_FILE", e)
            null
        }
    }

    /** Write the applied color scheme and font metadata to ~/.termux/style.properties. */
    private fun saveStyleProperties() {
        try {
            val destinationFile = stylePropertiesFile() ?: return
            destinationFile.setWritable(true)
            destinationFile.parentFile?.setWritable(true)
            destinationFile.parentFile?.setExecutable(true)

            val props = Properties()
            if (destinationFile.isFile) {
                try {
                    destinationFile.inputStream().use { props.load(it) }
                } catch (e: Exception) {
                    Log.w("termux", "Failed to load existing $STYLE_PROPERTIES_FILE", e)
                }
            }
            props.setProperty(PREF_COLOR, storeKey(appliedColor))
            props.setProperty(PREF_COLOR_TITLE, appliedColor.title)
            props.setProperty(PREF_FONT, storeKey(appliedFont))
            props.setProperty(PREF_FONT_TITLE, appliedFont.title)

            val atomicFile = AtomicFile(destinationFile)
            val out = atomicFile.startWrite()
            try {
                props.store(out, "Termux style configuration")
                atomicFile.finishWrite(out)
            } catch (e: Exception) {
                atomicFile.failWrite(out)
                throw e
            }
        } catch (e: Exception) {
            Log.w("termux", "Failed to write $STYLE_PROPERTIES_FILE", e)
        }
    }

    /** The key saved for a choice. The default has an empty key, so it can't be mixed up with a file. */
    private fun storeKey(choice: Choice): String = if (choice.fileName == null) "" else cacheKey(choice)

    /**
     * The choice in [choices] matching [key] (store key, file name, or title) or [title],
     * or null if there is none, such as after an import was deleted.
     */
    private fun choiceFor(choices: List<Choice>, key: String?, title: String? = null): Choice? {
        if (key != null) {
            val byStoreKey = choices.firstOrNull { !it.isImport && storeKey(it) == key }
            if (byStoreKey != null) return byStoreKey

            val byFileName = choices.firstOrNull { !it.isImport && (it.fileName == key || it.fileName?.substringBeforeLast('.') == key) }
            if (byFileName != null) return byFileName

            val byTitle = choices.firstOrNull { !it.isImport && it.title.equals(key, ignoreCase = true) }
            if (byTitle != null) return byTitle
        }

        if (title != null) {
            val byTitle = choices.firstOrNull { !it.isImport && it.title.equals(title, ignoreCase = true) }
            if (byTitle != null) return byTitle
        }

        return null
    }

    /** Remember what was applied in preferences and ~/.termux/style.properties, so Termux and this screen can read it. */
    private fun saveApplied() {
        stylePrefs().edit()
            .putString(PREF_COLOR, storeKey(appliedColor))
            .putString(PREF_FONT, storeKey(appliedFont))
            .putString(PREF_COLOR_TITLE, appliedColor.title)
            .putString(PREF_FONT_TITLE, appliedFont.title)
            .apply()
        saveStyleProperties()
    }

    /** Write the color scheme and the font that changed, Termux reloads each one when it is written. */
    private fun applySelection() {
        val colorChanged = selectedColor != appliedColor
        val fontChanged = selectedFont != appliedFont
        if (!colorChanged && !fontChanged) return

        val previousColor = appliedColor
        val previousFont = appliedFont

        if (colorChanged) appliedColor = selectedColor
        if (fontChanged) appliedFont = selectedFont
        saveApplied()

        var colorFailed = false
        var fontFailed = false
        if (colorChanged && !copyFile(appliedColor, true)) {
            appliedColor = previousColor
            colorFailed = true
        }
        if (fontChanged && !copyFile(appliedFont, false)) {
            appliedFont = previousFont
            fontFailed = true
        }
        if (colorFailed || fontFailed) {
            saveApplied()
        }

        updateApplyButton()
        applyFilters()
        if (appliedColor == selectedColor && appliedFont == selectedFont) {
            Toast.makeText(this, R.string.applied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLicense(choice: Choice) {
        // Imported files have no license file in the assets.
        if (choice.imported) return
        val fileName = choice.fileName ?: return
        val folder = if (choice.isColor) "colors" else "fonts"
        val licenseName = "$folder/${fileName.substringBeforeLast('.')}.txt"
        try {
            val license = assets.open(licenseName).use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val message = SpannableStringBuilder(license)
            Linkify.addLinks(message, Linkify.ALL)
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(choice.title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            (dialog.findViewById<View>(android.R.id.message) as TextView).movementMethod = LinkMovementMethod.getInstance()
        } catch (e: IOException) {
            // No license file for this choice.
        }
    }

    /** Write a color scheme or a font to the Termux home. Returns whether it worked. */
    private fun copyFile(choice: Choice, colors: Boolean): Boolean {
        val outputFile = if (colors) "colors.properties" else "font.ttf"
        try {
            val dir = termuxDir() ?: throw RuntimeException("Cannot access ~/.termux")

            // Use canonicalFile to follow a possible symlink:
            val destinationFile = File(dir, outputFile).canonicalFile
            // Fix for if the user has messed up with chmod:
            destinationFile.setWritable(true)
            destinationFile.parentFile?.setWritable(true)
            destinationFile.parentFile?.setExecutable(true)

            val atomicFile = AtomicFile(destinationFile)
            val out = atomicFile.startWrite()
            try {
                if (choice.fileName == null) {
                    if (colors) {
                        out.write("# Using default color theme.".toByteArray(StandardCharsets.UTF_8))
                    } else {
                        // An empty font file is the marker for the default font.
                    }
                } else {
                    openChoice(choice).use { it.copyTo(out) }
                }
                atomicFile.finishWrite(out)
            } catch (e: Exception) {
                atomicFile.failWrite(out)
                throw e
            }

            // Note: Must match constant in Term#onCreate():
            val actionReload = "com.termux.app.reload_style"
            val executeIntent = Intent(actionReload)
            executeIntent.putExtra(actionReload, if (colors) "colors" else "font")
            sendBroadcast(executeIntent)
            return true
        } catch (e: Exception) {
            Log.w("termux", "Failed to write $outputFile", e)
            val message = resources.getString(R.string.writing_failed) + e.message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return false
        }
    }

    /** Long press menu of a choice: its license, saving it as a file, and deleting it if it was imported. */
    private fun showMenu(choice: Choice) {
        val actions = ArrayList<Pair<Int, () -> Unit>>()
        if (!choice.imported && choice.fileName != null) actions.add(R.string.menu_license to { showLicense(choice) })
        if (choice.fileName != null) actions.add(R.string.menu_export to { startExport(choice) })
        if (choice.imported) actions.add(R.string.menu_delete to { confirmDelete(choice) })
        if (actions.isEmpty()) return

        val labels = actions.map { getString(it.first) as CharSequence }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(choice.title)
            .setItems(labels) { _, which -> actions[which].second() }
            .show()
    }

    /** Ask where to save a color scheme or a font, then copy it there. */
    private fun startExport(choice: Choice) {
        val name = choice.fileName ?: return
        pendingExport = choice
        exportPicker.launch(name)
    }

    private fun exportChoice(choice: Choice, uri: Uri) {
        try {
            val output = contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open the file")
            openChoice(choice).use { input -> output.use { input.copyTo(it) } }
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.w("termux", "Failed to export ${choice.fileName}", e)
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun displayNameOf(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "imported"
    }

    /** Why a color scheme file can't be imported, or null if it is valid. A scheme needs a background and a foreground. */
    private fun colorProblem(file: File): String? {
        val props = Properties()
        try {
            file.inputStream().use { props.load(it) }
        } catch (e: Exception) {
            return getString(R.string.import_invalid_colors)
        }
        for (key in listOf("background", "foreground") + (0..15).map { "color$it" }) {
            val value = props.getProperty(key)?.trim()
            if (value == null) {
                if (key == "background" || key == "foreground") return getString(R.string.import_missing_key, key)
            } else if (parseColor(value) == null) {
                return getString(R.string.import_bad_color, key, value)
            }
        }
        return null
    }

    /** Why a font file can't be imported, or null if Android can load it. */
    private fun fontProblem(file: File): String? = try {
        if (Typeface.createFromFile(file.path) != null) null else getString(R.string.import_invalid_font)
    } catch (e: RuntimeException) {
        getString(R.string.import_invalid_font)
    }

    /** The contrast between the text and the background of a color scheme file, or null if it can't be read. */
    private fun contrastOf(file: File): Double? = try {
        val props = Properties()
        file.inputStream().use { props.load(it) }
        val palette = paletteFrom(props)
        contrastRatio(palette.foreground, palette.background)
    } catch (e: Exception) {
        null
    }

    /**
     * Copy the picked file to a temporary file and check it. Then keep it, after asking first if its
     * contrast is low or it would replace an imported file with the same name.
     */
    private fun importFile(uri: Uri, colors: Boolean) {
        val name = displayNameOf(uri)
        if (!isAllowedName(name, colors)) {
            Toast.makeText(this, if (colors) R.string.import_not_properties else R.string.import_not_font, Toast.LENGTH_LONG).show()
            return
        }

        val temp = File(cacheDir, "import-temp")
        try {
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
            input.use { source -> temp.outputStream().use { source.copyTo(it) } }
        } catch (e: IOException) {
            Log.w("termux", "Failed to read $name", e)
            temp.delete()
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
            return
        }

        val problem = if (colors) colorProblem(temp) else fontProblem(temp)
        if (problem != null) {
            temp.delete()
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }

        val dir = importDir(colors)
        dir.mkdirs()
        val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val lowRatio = (if (colors) contrastOf(temp) else null)?.takeIf { it < LOW_CONTRAST }
        val replaces = target.exists()

        fun keep() {
            try {
                temp.copyTo(target, overwrite = true)
                // The file may have replaced an earlier one with the same name, so drop the cached copy.
                paletteCache.clear()
                typefaceCache.clear()

                val choice = Choice(target.name, displayName(target.name), colors, imported = true)
                reloadChoices()
                if (colors) selectedColor = choice else selectedFont = choice
                showMode(colors)
                updatePreview()
            } catch (e: IOException) {
                Log.w("termux", "Failed to import $name", e)
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
            } finally {
                temp.delete()
            }
        }

        if (lowRatio == null && !replaces) {
            keep()
            return
        }

        val messages = ArrayList<String>()
        if (lowRatio != null) messages.add(getString(R.string.import_low_contrast, "%.1f".format(lowRatio)))
        if (replaces) messages.add(getString(R.string.import_replace, target.name))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(messages.joinToString("\n\n"))
            .setPositiveButton(R.string.import_anyway) { _, _ -> keep() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> temp.delete() }
            .setOnCancelListener { temp.delete() }
            .show()
    }

    private fun confirmDelete(choice: Choice) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_title, choice.title))
            .setPositiveButton(R.string.delete_action) { _, _ ->
                choice.fileName?.let { File(importDir(choice.isColor), it).delete() }
                if (choice.isColor) {
                    if (selectedColor == choice) selectedColor = defaultColor()
                } else if (selectedFont == choice) {
                    selectedFont = defaultFont()
                }
                paletteCache.clear()
                typefaceCache.clear()
                reloadChoices()
                showMode(choice.isColor)
                updatePreview()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** One row per choice. Tapping a row previews it, a long press opens its menu. */
    private inner class OptionAdapter : RecyclerView.Adapter<OptionAdapter.Holder>() {

        private var items: List<Choice> = emptyList()

        fun submit(newItems: List<Choice>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_option, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val choice = items[position]
            if (choice.isImport) {
                bindImportRow(holder, choice)
                return
            }

            val selected = if (choice.isColor) choice == selectedColor else choice == selectedFont
            val applied = if (choice.isColor) choice == appliedColor else choice == appliedFont
            val surface = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorSurfaceContainerLow, Color.DKGRAY)
            val onSurface = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)

            holder.title.text = choice.title
            holder.badge.visibility = if (applied) View.VISIBLE else View.GONE
            if (choice.isColor) {
                // Each color scheme shows its own background, with its palette below the name.
                val palette = paletteFor(choice)
                holder.card.setCardBackgroundColor(palette.background)
                holder.title.setTextColor(palette.foreground)
                holder.title.typeface = Typeface.DEFAULT
                holder.bar.visibility = View.VISIBLE
                holder.bar.setColors(palette.ansi)
                holder.sample.visibility = View.GONE
            } else {
                // Each font shows a sample written in that font.
                holder.card.setCardBackgroundColor(surface)
                holder.title.setTextColor(onSurface)
                holder.title.typeface = Typeface.DEFAULT
                holder.bar.visibility = View.GONE
                holder.sample.visibility = View.VISIBLE
                holder.sample.typeface = typefaceFor(choice)
                holder.sample.text = getString(R.string.font_row_sample)
                holder.sample.setTextColor(onSurface)
            }

            holder.card.strokeColor = onSurface
            holder.card.strokeWidth = if (selected) resources.displayMetrics.density.times(3).toInt() else 0

            holder.card.setOnClickListener {
                if (choice.isColor) selectedColor = choice else selectedFont = choice
                updatePreview()
                notifyDataSetChanged()
            }
            holder.card.setOnLongClickListener {
                showMenu(choice)
                true
            }
        }

        /** The first row of each list: tapping it opens the file picker for that list. */
        private fun bindImportRow(holder: Holder, choice: Choice) {
            val container = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorSecondaryContainer, Color.DKGRAY)
            val onContainer = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.WHITE)

            holder.card.setCardBackgroundColor(container)
            holder.card.strokeWidth = 0
            holder.title.text = choice.title
            holder.title.setTextColor(onContainer)
            holder.title.typeface = Typeface.DEFAULT_BOLD
            holder.badge.visibility = View.GONE
            holder.bar.visibility = View.GONE
            holder.sample.visibility = View.GONE

            holder.card.setOnClickListener {
                importingColor = choice.isColor
                importPicker.launch(arrayOf("*/*"))
            }
            holder.card.setOnLongClickListener(null)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.option_card)
            val title: TextView = view.findViewById(R.id.option_title)
            val badge: TextView = view.findViewById(R.id.option_badge)
            val bar: ColorBarView = view.findViewById(R.id.option_bar)
            val sample: TextView = view.findViewById(R.id.option_sample)
        }
    }
}
