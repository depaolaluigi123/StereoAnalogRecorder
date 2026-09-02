package com.stereoanalogrecorder.app.ui

import android.app.AlertDialog
import android.content.Context

/**
 * Generic utility for showing a simple modal alert dialog.
 *
 * This is a reusable helper — any caller can display an alert by providing
 * the title, message, and (optionally) a callback for the positive button.
 * The decision of *whether* to show the alert is left to the caller.
 */
object AlertDialogHelper {

    /**
     * Show a modal alert dialog that the user must dismiss.
     *
     * @param context    Context used to inflate the dialog (should be an Activity).
     * @param title      Dialog title text.
     * @param message    Dialog body text.
     * @param onDismiss  Optional callback invoked when the dialog is dismissed
     *                   for any reason — positive button, outside-tap, or
     *                   back-button. The dialog has been dismissed by the time
     *                   this runs.
     */
    fun show(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        onDismiss: (() -> Unit)? = null,
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { d, _ ->
                d.dismiss()
                onDismiss?.invoke()
            }
            .setOnDismissListener { onDismiss?.invoke() }
            .setCancelable(true)
            .show()
    }

    /**
     * Show a modal alert dialog with two buttons: a neutral "Cancel" button
     * and a positive "action" button.
     *
     * @param context      Context used to inflate the dialog (should be an Activity).
     * @param title        Dialog title text.
     * @param message      Dialog body text.
     * @param actionLabel  Text for the positive (action) button.
     * @param onAction     Callback invoked when the user presses the action button.
     *                     The dialog is dismissed before this is called.
     * @param onDismiss    Optional callback invoked when the dialog is dismissed
     *                     for any reason — action button, cancel button,
     *                     outside-tap, or back-button. The dialog has been
     *                     dismissed by the time this runs.
     */
    fun showWithAction(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        actionLabel: CharSequence,
        onAction: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setPositiveButton(actionLabel) { d, _ ->
                d.dismiss()
                onAction?.invoke()
            }
            .setOnDismissListener { onDismiss?.invoke() }
            .setCancelable(true)
            .show()
    }
}
