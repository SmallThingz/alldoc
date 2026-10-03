package org.smallthingz.alldoc

import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.FormEditRecord
import android.graphics.pdf.models.FormWidgetInfo
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi

/** Platform-only AcroForm support. Call exclusively from the isolated PDF service. */
@RequiresApi(35)
internal object PdfEditor {
    /** Preserve the descriptor's offset; scanning a duplicate still shares its open-file offset. */
    fun formRestrictionMarker(source: ParcelFileDescriptor): PdfFormRestriction? {
        require(source.statSize in 1..PdfTools.MAX_FILE_BYTES) {
            "PDF exceeds the 32 MiB safety limit or is not a regular file"
        }
        val previousOffset = Os.lseek(source.fileDescriptor, 0, OsConstants.SEEK_CUR)
        try {
            Os.lseek(source.fileDescriptor, 0, OsConstants.SEEK_SET)
            return ParcelFileDescriptor.AutoCloseInputStream(
                    ParcelFileDescriptor.dup(source.fileDescriptor)
                )
                .use { PdfTools.formRestrictionMarker(it) }
        } finally {
            Os.lseek(source.fileDescriptor, previousOffset, OsConstants.SEEK_SET)
        }
    }

    /**
     * Cache this result only for this renderer. A signature anywhere makes the whole PDF read-only.
     */
    fun formBlockReason(renderer: PdfRenderer): String? {
        if (renderer.pageCount > PdfTools.MAX_FORM_PAGES) {
            return "Form filling is limited to PDFs with at most ${PdfTools.MAX_FORM_PAGES} pages."
        }
        if (renderer.pdfFormType != PdfRenderer.PDF_FORM_TYPE_ACRO_FORM) {
            return "This PDF has no supported AcroForm text fields. ${PdfTools.FORM_LIMITATION}"
        }
        repeat(renderer.pageCount) { pageIndex ->
            renderer.openPage(pageIndex).use { page ->
                val widgets = page.formWidgetInfos
                if (widgets.size > PdfTools.MAX_FIELDS_PER_PAGE) {
                    return "This document has too many fields on one page to fill safely."
                }
                if (widgets.any { it.widgetType == FormWidgetInfo.WIDGET_TYPE_SIGNATURE }) {
                    return "This PDF contains a signature field. To protect signatures, fill it in a dedicated PDF editor."
                }
            }
        }
        return null
    }

    fun fields(renderer: PdfRenderer, pageIndex: Int): PdfFormPage {
        require(pageIndex in 0 until renderer.pageCount) { "Invalid PDF page" }
        if (renderer.pageCount > PdfTools.MAX_FORM_PAGES) {
            return PdfFormPage(
                emptyList(),
                "Form filling is limited to PDFs with at most ${PdfTools.MAX_FORM_PAGES} pages.",
            )
        }
        if (renderer.pdfFormType != PdfRenderer.PDF_FORM_TYPE_ACRO_FORM) {
            return PdfFormPage(
                emptyList(),
                "This PDF has no supported AcroForm text fields. ${PdfTools.FORM_LIMITATION}",
            )
        }
        return renderer.openPage(pageIndex).use { page ->
            val widgets = page.formWidgetInfos
            require(widgets.size <= PdfTools.MAX_FIELDS_PER_PAGE) {
                "This page has too many form fields to edit safely"
            }
            if (widgets.any { it.widgetType == FormWidgetInfo.WIDGET_TYPE_SIGNATURE }) {
                return@use PdfFormPage(
                    emptyList(),
                    "This page contains a signature field. To protect signatures, this document cannot be filled in alldoc.",
                )
            }
            val fields = widgets.mapNotNull { widget -> toField(pageIndex, widget) }
            PdfFormPage(
                fields,
                when {
                    fields.isEmpty() ->
                        "No editable PDF text fields on this page. ${PdfTools.FORM_LIMITATION}"
                    fields.size < widgets.size ->
                        "Only supported text fields are shown. Other form controls are read-only."
                    else -> null
                },
            )
        }
    }

    /**
     * Uses a fresh renderer, validates every requested edit before applying any, and only writes a
     * separate empty staging file. No native mutation reaches the currently displayed PDF or
     * source. Caller owns the supplied descriptors; PdfRenderer gets duplicates and closes only
     * those.
     */
    fun writeCopy(
        source: ParcelFileDescriptor,
        destination: ParcelFileDescriptor,
        payload: String,
    ) {
        val edits = PdfTools.decodeEdits(payload)
        require(edits.isNotEmpty()) {
            "No PDF form edits to export; copy the original bytes instead"
        }
        val inputStat = Os.fstat(source.fileDescriptor)
        val outputStat = Os.fstat(destination.fileDescriptor)
        require(
            OsConstants.S_ISREG(inputStat.st_mode) &&
                inputStat.st_size in 1..PdfTools.MAX_FILE_BYTES
        ) {
            "PDF must be a regular file no larger than 32 MiB"
        }
        require(OsConstants.S_ISREG(outputStat.st_mode) && outputStat.st_size == 0L) {
            "PDF form export requires a new empty staging file"
        }
        require(inputStat.st_dev != outputStat.st_dev || inputStat.st_ino != outputStat.st_ino) {
            "PDF form export cannot overwrite the original"
        }
        val sourceMode =
            Os.fcntlInt(source.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE
        require(sourceMode == OsConstants.O_RDONLY) { "PDF source must be opened read-only" }
        val restriction = formRestrictionMarker(source)
        require(restriction == null) { restriction!!.message }
        val duplicate = ParcelFileDescriptor.dup(source.fileDescriptor)
        val renderer =
            try {
                PdfRenderer(duplicate)
            } catch (failure: Exception) {
                duplicate.close()
                throw failure
            }
        renderer.use { current ->
            require(current.pageCount in 1..PdfTools.MAX_FORM_PAGES) {
                "PDF has too many pages for form filling"
            }
            require(current.pdfFormType == PdfRenderer.PDF_FORM_TYPE_ACRO_FORM) {
                "Only AcroForm text fields can be filled"
            }
            val byPage = edits.entries.groupBy { PdfTools.address(it.key).first }
            require(byPage.keys.all { it in 0 until current.pageCount }) { "Invalid PDF form page" }
            // Inspect all pages before changing anything. Signed documents must stay with a full
            // editor;
            // even writing an unrelated field can invalidate a digital signature.
            repeat(current.pageCount) { pageIndex ->
                current.openPage(pageIndex).use { page ->
                    val widgets = page.formWidgetInfos
                    require(widgets.size <= PdfTools.MAX_FIELDS_PER_PAGE) {
                        "PDF has too many fields on one page"
                    }
                    require(
                        widgets.none { it.widgetType == FormWidgetInfo.WIDGET_TYPE_SIGNATURE }
                    ) {
                        "PDFs with signature fields cannot be filled safely here. Use a dedicated PDF editor."
                    }
                    byPage[pageIndex].orEmpty().forEach { edit ->
                        val index = PdfTools.address(edit.key).second
                        val widget =
                            widgets.firstOrNull { it.widgetIndex == index }
                                ?: error("PDF form field no longer exists")
                        val field =
                            toField(pageIndex, widget)
                                ?: error("This PDF form field is not editable")
                        PdfTools.validateValue(field, edit.value)
                    }
                }
            }
            byPage.forEach { (pageIndex, pageEdits) ->
                current.openPage(pageIndex).use { page ->
                    pageEdits.forEach { edit ->
                        page.applyEdit(
                            FormEditRecord.Builder(
                                    FormEditRecord.EDIT_TYPE_SET_TEXT,
                                    pageIndex,
                                    PdfTools.address(edit.key).second,
                                )
                                .setText(edit.value)
                                .build()
                        )
                    }
                }
            }
            // The platform closes its destination FD. Keep the caller's FD for size/fsync
            // validation.
            val output = ParcelFileDescriptor.dup(destination.fileDescriptor)
            try {
                current.write(output, false)
            } finally {
                output.close()
            }
            require(Os.fstat(destination.fileDescriptor).st_size in 1..PdfTools.MAX_FILE_BYTES) {
                "Edited PDF exceeds the 32 MiB export limit"
            }
            Os.fsync(destination.fileDescriptor)
            Os.lseek(destination.fileDescriptor, 0, OsConstants.SEEK_SET)
            val verificationFd = ParcelFileDescriptor.dup(destination.fileDescriptor)
            val verified =
                try {
                    PdfRenderer(verificationFd)
                } catch (failure: Exception) {
                    verificationFd.close()
                    throw failure
                }
            verified.use { saved ->
                require(saved.pageCount == current.pageCount) {
                    "Saved PDF page count could not be verified"
                }
                byPage.forEach { (pageIndex, pageEdits) ->
                    saved.openPage(pageIndex).use { page ->
                        pageEdits.forEach { edit ->
                            val widget =
                                page.getFormWidgetInfoAtIndex(PdfTools.address(edit.key).second)
                            require(widget.textValue.orEmpty() == edit.value) {
                                "A PDF field rejected its value or conflicts with a linked field. Check the form values and try again."
                            }
                        }
                    }
                }
            }
        }
    }

    private fun toField(page: Int, widget: FormWidgetInfo): PdfFormField? {
        if (widget.widgetType != FormWidgetInfo.WIDGET_TYPE_TEXTFIELD || widget.isReadOnly)
            return null
        val value = widget.textValue.orEmpty()
        if (value.length > PdfTools.MAX_FIELD_CHARACTERS || '\u0000' in value) return null
        return PdfFormField(
            page,
            widget.widgetIndex,
            widget.accessibilityLabel.orEmpty().take(200).ifBlank {
                "Field ${widget.widgetIndex + 1}"
            },
            value,
            if (widget.maxLength < 0) PdfTools.MAX_FIELD_CHARACTERS
            else widget.maxLength.coerceAtMost(PdfTools.MAX_FIELD_CHARACTERS),
            widget.isMultiLineText,
        )
    }
}
