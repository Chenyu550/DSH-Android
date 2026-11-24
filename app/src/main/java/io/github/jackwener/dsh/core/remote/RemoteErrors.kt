package io.github.jackwener.dsh.core.remote

import android.content.Context
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.remote.RemoteException.Kind

/** What a remote-mode failure means and what to do about it, in the user's language (#51). */
object RemoteErrors {
    /** The `app/error` kind of a remote-mode failure; its code is the [RemoteException.Kind] name. */
    const val KIND = "remote"

    fun message(context: Context, e: RemoteException): String = when (e.kind) {
        Kind.Offline -> context.getString(R.string.remote_err_offline)
        Kind.Dns -> context.getString(R.string.remote_err_dns)
        Kind.Timeout -> context.getString(R.string.remote_err_timeout)
        Kind.Refused -> context.getString(R.string.remote_err_refused)
        Kind.Unreachable -> context.getString(R.string.remote_err_unreachable)
        Kind.HostKeyUnknown -> context.getString(R.string.remote_err_hostkey_unknown)
        Kind.HostKeyChanged -> context.getString(R.string.remote_err_hostkey_changed, e.presentedFingerprint ?: "?")
        Kind.Auth -> context.getString(R.string.remote_err_auth)
        Kind.KeyInvalid -> context.getString(R.string.remote_err_key_invalid, e.message ?: "")
        Kind.KeyPassphrase -> context.getString(R.string.remote_err_key_passphrase)
        Kind.Workdir -> context.getString(R.string.remote_err_workdir, e.message ?: "")
        Kind.NoDsh -> context.getString(R.string.remote_err_no_dsh, e.message ?: "dsh")
        Kind.DshExited -> context.getString(R.string.remote_err_dsh_exited, e.message ?: "")
        Kind.Initialize -> context.getString(R.string.remote_err_initialize, e.message ?: "")
        Kind.Disconnected -> context.getString(R.string.err_remote_lost)
        Kind.HostMissing -> context.getString(R.string.remote_err_host_missing)
        Kind.Other -> context.getString(R.string.remote_err_other, e.message ?: "")
    }
}
