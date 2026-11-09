package app.keyra

import android.content.SharedPreferences

/** Durability boundaries must report failure before encrypted files/state are committed. */
// KTX edit(commit = true) discards the commit result required by these callers.
@android.annotation.SuppressLint("UseKtx")
internal fun SharedPreferences.commitChanges(change: SharedPreferences.Editor.() -> Unit): Boolean =
    edit().apply(change).commit()
