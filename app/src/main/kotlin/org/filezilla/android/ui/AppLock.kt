package org.filezilla.android.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.filezilla.android.R

/** The FragmentActivity behind a composable's context, or null. */
fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Whether the phone can actually lock the app -- a fingerprint enrolled, or a
 * screen lock set. Without either there is nothing to check against, so the
 * setting offers nothing and is not allowed on.
 */
fun appLockAvailable(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) ==
        BiometricManager.BIOMETRIC_SUCCESS

/**
 * Wraps [content] behind the app lock.
 *
 * When [enabled], the app opens covered and asks for a fingerprint or the
 * device PIN, and covers itself again each time it returns from the background
 * -- so the saved servers and their passwords are not one tap away for whoever
 * picks the phone up. The content stays composed underneath (its state is kept)
 * but an opaque cover hides it until the check passes. If the phone has no way
 * to authenticate after all -- a lock screen removed while the setting was on
 * -- it opens rather than trapping the user out.
 */
@Composable
fun AppLockGate(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val available = remember(context) { appLockAvailable(context) }

    // Nothing to check against, or no activity to host the prompt: open rather
    // than lock the owner out of their own app.
    if (activity == null || !available) {
        content()
        return
    }

    // Locked at launch, and saved so a rotation does not reveal the app behind
    // the cover for the frame before it locks again.
    var locked by rememberSaveable { mutableStateOf(true) }
    // Bumped to ask again: once at launch, and once each time the app comes
    // back to the front while still locked.
    var attempt by rememberSaveable { mutableIntStateOf(0) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> locked = true
                Lifecycle.Event.ON_RESUME -> if (locked) attempt++
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val title = stringResource(R.string.lock_title)
    val subtitle = stringResource(R.string.lock_subtitle)
    LaunchedEffect(attempt) {
        if (locked) {
            promptUnlock(activity, title, subtitle, onSuccess = { locked = false })
        }
    }

    content()
    if (locked) {
        LockCover(onUnlock = { attempt++ })
    }
}

/** The opaque cover shown while the app is locked, with a way to ask again. */
@Composable
private fun LockCover(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallow every tap, so nothing behind the cover can be touched.
            .pointerInput(Unit) {}
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_tile_locked),
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.size(72.dp),
        )
        Text(
            stringResource(R.string.lock_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 20.dp),
        )
        Button(onClick = onUnlock, modifier = Modifier.padding(top = 20.dp)) {
            Text(stringResource(R.string.lock_unlock))
        }
    }
}

/** Shows the system fingerprint / device-credential prompt. */
private fun promptUnlock(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            // A cancel or an error leaves the app locked; the cover's button
            // asks again. Nothing is logged -- a failed unlock is the user's
            // business, not the log's.
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        .build()
    runCatching { prompt.authenticate(info) }
}
