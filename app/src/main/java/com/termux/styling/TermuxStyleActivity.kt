package com.termux.styling

import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    /** Whether the file picker was opened from the color list or from the font list. */
    private var importingColor = true

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri, importingColor)
    }

    private lateinit var previewCard: MaterialCardView
    private lateinit var previewLabel: TextView
    private lateinit var previewText: TextView
    private lateinit var previewBar: ColorBarView
    private lateinit var typographyLabel: TextView
    private lateinit var typographyText: TextView
    private lateinit var colorsButton: MaterialButton
    private lateinit var fontsButton: MaterialButton
    private lateinit var applyButton: MaterialButton
    private lateinit var optionsList: RecyclerView
    private val adapter = OptionAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Use the wallpaper colors on Android 12 and newer, as the Termux app does.
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_style)

        previewCard = findViewById(R.id.preview_card)
        previewLabel = findViewById(R.id.preview_label)
        previewText = findViewById(R.id.preview_text)
        previewBar = findViewById(R.id.preview_bar)
        typographyLabel = findViewById(R.id.typography_label)
        typographyText = findViewById(R.id.typography_text)
        colorsButton = findViewById(R.id.mode_colors)
        fontsButton = findViewById(R.id.mode_fonts)
        applyButton = findViewById(R.id.apply_button)
        optionsList = findViewById(R.id.options_list)

        reloadChoices()

        selectedColor = defaultColor()
        appliedColor = selectedColor
        selectedFont = defaultFont()
        appliedFont = selectedFont

        // Without a layout manager the list shows nothing at all.
        optionsList.layoutManager = LinearLayoutManager(this)
        optionsList.adapter = adapter
        showMode(colors = true)

        colorsButton.setOnClickListener { showMode(colors = true) }
        fontsButton.setOnClickListener { showMode(colors = false) }
        applyButton.setOnClickListener { applySelection() }
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
        adapter.submit(if (colors) colorChoices else fontChoices)
        styleModeButton(colorsButton, colors)
        styleModeButton(fontsButton, !colors)
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

            fun read(key: String, fallback: Int): Int =
                props.getProperty(key)?.trim()?.let { parseColor(it) } ?: fallback

            Palette(
                background = read("background", DEFAULT_PALETTE.background),
                foreground = read("foreground", DEFAULT_PALETTE.foreground),
                ansi = IntArray(16) { read("color$it", DEFAULT_PALETTE.ansi[it]) },
            )
        } catch (e: IOException) {
            DEFAULT_PALETTE
        }
    }

    private fun parseColor(value: String): Int? = try {
        Color.parseColor(value)
    } catch (e: IllegalArgumentException) {
        null
    }

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

    /** Write the color scheme and the font that changed, Termux reloads each one when it is written. */
    private fun applySelection() {
        if (selectedColor != appliedColor && copyFile(selectedColor, true)) appliedColor = selectedColor
        if (selectedFont != appliedFont && copyFile(selectedFont, false)) appliedFont = selectedFont
        updateApplyButton()
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
            val context = createPackageContext("com.termux", Context.CONTEXT_IGNORE_SECURITY)
            val homeDir = File(context.filesDir, "home")
            val termuxDir = File(homeDir, ".termux")
            if (!(termuxDir.isDirectory || termuxDir.mkdirs()))
                throw RuntimeException("Cannot create termux dir=" + termuxDir.absolutePath)

            // Use canonicalFile to follow a possible symlink:
            val destinationFile = File(termuxDir, outputFile).canonicalFile
            // Fix for if the user has messed up with chmod:
            destinationFile.setWritable(true)
            destinationFile.parentFile?.setWritable(true)
            destinationFile.parentFile?.setExecutable(true)

            val atomicFile = AtomicFile(destinationFile)
            val out = atomicFile.startWrite()
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

    private fun displayNameOf(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "imported"
    }

    /** A color scheme needs a background and a foreground, and any color0 to color15 must be a hex color. */
    private fun isValidColors(file: File): Boolean = try {
        val props = Properties()
        file.inputStream().use { props.load(it) }
        val keys = listOf("background", "foreground") + (0..15).map { "color$it" }
        keys.all { key ->
            val value = props.getProperty(key)
            if (value == null) key != "background" && key != "foreground"
            else parseColor(value.trim()) != null
        }
    } catch (e: IOException) {
        false
    }

    /** A font is valid when Android can load it. */
    private fun isValidFont(file: File): Boolean = try {
        Typeface.createFromFile(file.path) != null
    } catch (e: RuntimeException) {
        false
    }

    /** Copy the picked file into the app, check it, then show it in the list and select it. */
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

            val valid = if (colors) isValidColors(temp) else isValidFont(temp)
            if (!valid) {
                val message = if (colors) R.string.import_invalid_colors else R.string.import_invalid_font
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return
            }

            val dir = importDir(colors)
            if (!(dir.isDirectory || dir.mkdirs())) throw IOException("Cannot create ${dir.absolutePath}")
            val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            temp.copyTo(target, overwrite = true)

            // A file with the same name may have been imported before, so drop the cached copy.
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

    /** One row per choice. Tapping a row previews it, a long press shows its license or deletes an import. */
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
            val surface = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorSurfaceContainerLow, Color.DKGRAY)
            val onSurface = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)

            holder.title.text = choice.title
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
                if (choice.imported) confirmDelete(choice) else showLicense(choice)
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
            val bar: ColorBarView = view.findViewById(R.id.option_bar)
            val sample: TextView = view.findViewById(R.id.option_sample)
        }
    }
}
