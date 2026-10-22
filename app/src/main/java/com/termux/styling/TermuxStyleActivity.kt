package com.termux.styling

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Properties

/** A color scheme or a font. [fileName] is the file in the assets, {@code null} for the default. */
data class Choice(val fileName: String?, val title: String, val isColor: Boolean)

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

    private lateinit var previewCard: MaterialCardView
    private lateinit var previewLabel: TextView
    private lateinit var previewText: TextView
    private lateinit var previewBar: ColorBarView
    private lateinit var typographyLabel: TextView
    private lateinit var typographyText: TextView
    private lateinit var modeGroup: MaterialButtonToggleGroup
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
        modeGroup = findViewById(R.id.mode_group)
        applyButton = findViewById(R.id.apply_button)
        optionsList = findViewById(R.id.options_list)

        colorChoices.add(Choice(null, getString(R.string.default_color), true))
        colorChoices.addAll(listAssets("colors", ".properties", true))
        fontChoices.add(Choice(null, getString(R.string.default_font), false))
        fontChoices.addAll(listAssets("fonts", ".ttf", false))

        selectedColor = colorChoices[0]
        appliedColor = selectedColor
        selectedFont = fontChoices[0]
        appliedFont = selectedFont

        optionsList.adapter = adapter
        adapter.submit(colorChoices)

        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            adapter.submit(if (checkedId == R.id.mode_colors) colorChoices else fontChoices)
        }

        applyButton.setOnClickListener { applySelection() }
        updatePreview()
    }

    private fun listAssets(folder: String, extension: String, isColor: Boolean): List<Choice> {
        val files = try {
            assets.list(folder)?.filter { it.endsWith(extension) } ?: emptyList()
        } catch (e: IOException) {
            emptyList()
        }
        return files.sortedWith(String.CASE_INSENSITIVE_ORDER).map { Choice(it, displayName(it), isColor) }
    }

    private fun displayName(fileName: String): String {
        val name = fileName.substringBeforeLast('.').replace('-', ' ').replace('_', ' ')
        return capitalize(name)
    }

    /** The colors of a color scheme, read from the properties file in the assets. */
    private fun paletteFor(fileName: String?): Palette {
        if (fileName == null) return DEFAULT_PALETTE
        return paletteCache.getOrPut(fileName) { loadPalette(fileName) }
    }

    private fun loadPalette(fileName: String): Palette {
        return try {
            val props = Properties()
            assets.open("colors/$fileName").use { props.load(it) }

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

    /** The typeface of a font in the assets, the system monospace for the default. */
    private fun typefaceFor(fileName: String?): Typeface {
        if (fileName == null) return Typeface.MONOSPACE
        return typefaceCache.getOrPut(fileName) {
            try {
                Typeface.createFromAsset(assets, "fonts/$fileName")
            } catch (e: RuntimeException) {
                Typeface.MONOSPACE
            }
        }
    }

    private fun updatePreview() {
        val palette = paletteFor(selectedColor.fileName)
        val typeface = typefaceFor(selectedFont.fileName)
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
        if (selectedColor != appliedColor && copyFile(selectedColor.fileName, true)) appliedColor = selectedColor
        if (selectedFont != appliedFont && copyFile(selectedFont.fileName, false)) appliedFont = selectedFont
        updateApplyButton()
        if (appliedColor == selectedColor && appliedFont == selectedFont) {
            Toast.makeText(this, R.string.applied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLicense(choice: Choice) {
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
    private fun copyFile(fileName: String?, colors: Boolean): Boolean {
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
            if (fileName == null) {
                if (colors) {
                    out.write("# Using default color theme.".toByteArray(StandardCharsets.UTF_8))
                } else {
                    // An empty font file is the marker for the default font.
                }
            } else {
                assets.open((if (colors) "colors/" else "fonts/") + fileName).use { it.copyTo(out) }
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

    /** One row per choice. Tapping a row previews it, a long press shows its license. */
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
            val selected = if (choice.isColor) choice == selectedColor else choice == selectedFont
            val surface = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorSurfaceContainerLow, Color.DKGRAY)
            val onSurface = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)

            holder.title.text = choice.title
            if (choice.isColor) {
                // Each color scheme shows its own background, with its palette below the name.
                val palette = paletteFor(choice.fileName)
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
                holder.sample.typeface = typefaceFor(choice.fileName)
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
                showLicense(choice)
                true
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.option_card)
            val title: TextView = view.findViewById(R.id.option_title)
            val bar: ColorBarView = view.findViewById(R.id.option_bar)
            val sample: TextView = view.findViewById(R.id.option_sample)
        }
    }
}
