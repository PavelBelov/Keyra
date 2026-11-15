package app.keyra

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import app.keemobile.kotpass.models.EntryValue

/** Uses the same cards, field rows and controls as the data and entry forms. */
// Constructed in code with its review and callbacks, never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
internal class ConflictResolutionView(
    context: Context,
    draft: ConflictDraft,
    busy: Boolean,
    showDialog: (AlertDialog.Builder) -> Unit,
    modeChanged: () -> Unit,
    registerField: (EntryFieldView) -> Unit,
    copy: (String) -> Unit,
    onApply: () -> Unit
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setPadding(context.dp(14), context.dp(24), context.dp(14), context.dp(32))
        isSaveEnabled = false
        fun text(id: Int) = L10n.text(context, id)
        fun info(value: String) = TextView(context).apply {
            text = value; textSize = 13f
            setTextColor(context.getColor(R.color.settings_secondary))
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
            isSaveEnabled = false
        }
        val modes = listOf(ConflictMode.LOCAL, ConflictMode.REMOTE, ConflictMode.MANUAL)
        val titles = listOf(text(R.string.merge_priority_local), text(R.string.merge_priority_remote), text(R.string.merge_manual))
        context.settingsCard(this, text(R.string.merge_resolution_method), 0).addView(
            context.settingsAction(text(R.string.merge_resolution_method), titles[modes.indexOf(draft.mode)]) {
                showDialog(AlertDialog.Builder(context).setTitle(R.string.merge_resolution_method)
                    .setSingleChoiceItems(titles.toTypedArray(), modes.indexOf(draft.mode)) { dialog, position ->
                        draft.mode = modes[position]; dialog.dismiss(); modeChanged()
                    }.setNegativeButton(R.string.ui_cancel_82, null))
            }.apply { isEnabled = !busy })
        addView(info(text(R.string.merge_independent_changes)))
        if (draft.review.pendingWrite != null) addView(info(text(R.string.merge_uncertain_write)))
        if (draft.issues.isEmpty()) addView(info(text(R.string.merge_no_object_conflicts)))

        val applyButton = Button(context).apply {
            text = text(if (busy) R.string.merge_applying else R.string.merge_apply)
            isAllCaps = false; textSize = 16f
            backgroundTintList = null; setBackgroundResource(R.drawable.bg_create_database)
            setTextColor(context.getColor(R.color.on_accent))
            isEnabled = !busy && draft.complete
            setOnClickListener { onApply() }
        }
        if (draft.mode == ConflictMode.MANUAL) {
            draft.issues.forEachIndexed { index, issue ->
                val description = ConflictReview.reason(context, issue.reason) +
                    (issue.fieldName?.let { " · ${fieldLabel(context, it)}" } ?: "")
                val card = context.settingsCard(this, "${index + 1}. $description", 16)
                val options = RadioGroup(context).apply {
                    orientation = RadioGroup.VERTICAL
                    isSaveEnabled = false
                    dividerDrawable = card.dividerDrawable
                    showDividers = SHOW_DIVIDER_MIDDLE
                }
                listOf(false, true).forEach { external ->
                    val (path, status) = draft.review.side(context, issue, external)
                    val side = text(if (external) R.string.merge_remote_database else R.string.merge_local_database)
                    val version = LinearLayout(context).apply { orientation = VERTICAL }
                    val row = LinearLayout(context).apply {
                        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(context.dp(8), 0, context.dp(4), 0)
                    }
                    val radio = RadioButton(context).apply {
                        id = View.generateViewId(); isSaveEnabled = false
                        text = context.settingsText(side, "$path\n$status")
                        textSize = 14f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        setTextColor(context.getColor(R.color.settings_text))
                        minHeight = context.dp(72); isEnabled = !busy
                        setPadding(context.dp(4), context.dp(10), context.dp(8), context.dp(10))
                        // Version rows also contain a preview button, so RadioGroup cannot
                        // attach its direct-child listener. Route changes through check(id).
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) {
                                options.check(id)
                                draft.choices[issue.key] = external
                                applyButton.isEnabled = !busy && draft.complete
                            }
                        }
                    }
                    val preview = LinearLayout(context).apply { orientation = VERTICAL; visibility = View.GONE }
                    val fields = mutableListOf<EntryFieldView>()
                    val versionLabel = "$side · ${issue.fieldName?.let { fieldLabel(context, it) } ?: path}"
                    val toggle = ImageButton(context).apply {
                        setImageResource(R.drawable.ic_settings_chevron)
                        imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.settings_control))
                        val highlight = android.util.TypedValue()
                        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, highlight, true)
                        setBackgroundResource(highlight.resourceId)
                        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
                        contentDescription = L10n.text(context, R.string.show_field, versionLabel)
                        tooltipText = contentDescription
                        isSaveEnabled = false
                    }
                    toggle.setOnClickListener {
                        if (preview.visibility == View.VISIBLE) {
                            fields.forEach { it.hideValue() }; preview.visibility = View.GONE
                        } else {
                            if (preview.childCount == 0) {
                                val iconId = issue.objectId
                                if (issue.reason == "CUSTOM_ICON" && iconId != null && draft.review.iconImageDiffers(iconId)) {
                                    val db = if (external) draft.review.remote else draft.review.local
                                    val icon = VaultIcons(db.content.meta.customIcons).resolve(
                                        app.keemobile.kotpass.models.Entry(iconId, customIconUuid = iconId))
                                    icon.bitmap?.let { bitmap ->
                                        preview.addView(ImageView(context).apply {
                                            setImageBitmap(bitmap); contentDescription = text(R.string.merge_custom_icon)
                                        }, LayoutParams(context.dp(96), context.dp(96)))
                                    }
                                }
                                draft.review.details(context, issue, external).forEach { (name, value) ->
                                    val field = EntryFieldView(context, fieldLabel(context, name), value,
                                        name == "Password" || value is EntryValue.Encrypted, copy)
                                    fields += field; registerField(field)
                                    preview.addView(field, LayoutParams(-1, -2))
                                }
                                if (preview.childCount == 0) preview.addView(info(status))
                            }
                            preview.visibility = View.VISIBLE
                        }
                        val expanded = preview.visibility == View.VISIBLE
                        toggle.rotation = if (expanded) 90f else 0f
                        toggle.contentDescription = L10n.text(context,
                            if (expanded) R.string.hide_field else R.string.show_field, versionLabel)
                        toggle.tooltipText = toggle.contentDescription
                    }
                    row.addView(radio, LayoutParams(0, -2, 1f))
                    row.addView(toggle, LayoutParams(context.dp(48), context.dp(48)))
                    version.addView(row); version.addView(preview)
                    options.addView(version, RadioGroup.LayoutParams(-1, -2))
                    if (draft.choices[issue.key] == external) options.check(radio.id)
                }
                card.addView(options)
            }
            addView(info(text(R.string.merge_choose_all)))
        }
        if (draft.error.isNotEmpty()) addView(info(draft.error).apply {
            setTextColor(context.getColor(R.color.settings_error))
        })
        addView(applyButton, LayoutParams(-1, context.dp(56)).apply { topMargin = context.dp(24) })
    }
}
