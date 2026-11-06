package co.antcloud.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import co.antcloud.AppState
import co.antcloud.Config
import co.antcloud.Log
import com.limelight.R
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun LoginScreen() {
    val activity = LocalContext.current as ComponentActivity
    val auth = AppState.auth
    val scope = rememberCoroutineScope()
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()

    var phoneMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(if (activity.intent.getStringExtra("preview") == "login") "player@example.com" else "") }
    var password by remember { mutableStateOf(if (activity.intent.getStringExtra("preview") == "login") "password" else "") }
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var otpSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val shake = remember { Animatable(0f) }

    fun run(action: suspend () -> Unit) {
        error = null; info = null; busy = true
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                Log.info("[login] ${e.javaClass.simpleName}: ${e.message}")
                error = if (e is IOException) "Couldn't reach AntCloud. Check your internet connection." else e.message
                shake.animateTo(0f, keyframes { durationMillis = 450; -9f at 60; 9f at 120; -7f at 180; 7f at 240; -4f at 300; 4f at 360 })
            }
            busy = false
        }
    }

    val tv = LocalIsTv.current
    // Sign in is the first stop when it can be used; with empty fields the method picker is, so no keyboard pops up unasked.
    val signInFocus = remember { FocusRequester() }
    val pickerFocus = remember { FocusRequester() }
    if (tv) LaunchedEffect(Unit) { runCatching { (if (!phoneMode && email.isNotBlank() && password.isNotEmpty()) signInFocus else pickerFocus).requestFocus() } }

    val hero: @Composable () -> Unit = {
        // Hero
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Image(painterResource(R.drawable.antcloud_logo), null,
                Modifier.padding(top = 12.dp).size(64.dp).shadow(18.dp, RoundedCornerShape(15.dp), spotColor = AntBlue, ambientColor = AntBlue).clip(RoundedCornerShape(15.dp)))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your gaming PC,\nin the cloud.", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
                Text(if (tv) "Start it, stream it, play. AntCloud on your TV, one click at a time." else "Start it, stream it, play. AntCloud on your phone, one tap at a time.",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Feature(AcIcons.Power, "One tap to start your cloud PC")
                Feature(AcIcons.Link, "Pairs with Moonlight on its own")
                Feature(AcIcons.Tv, "Up to 4K and 120 FPS")
            }
        }
    }

    val card: @Composable () -> Unit = {
        // Card
        Surface(
            Modifier.fillMaxWidth().graphicsLayer { translationX = shake.value * density }
                .shadow(24.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black.copy(alpha = 0.2f)),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        ) {
            Column(Modifier.padding(if (tv) 18.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(if (tv) 8.dp else 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                UpstreamBanner()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sign in", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    if (!tv) Text("Use your AntCloud account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tv) Segments(listOf("Email" to "email", "Phone" to "phone"), if (phoneMode) "phone" else "email", busy, Modifier.focusRequester(pickerFocus)) { v ->
                    phoneMode = v == "phone"; error = null; info = null
                } else SingleChoiceSegmentedButtonRow {
                    listOf("Email", "Phone").forEachIndexed { i, label ->
                        SegmentedButton(selected = phoneMode == (i == 1), onClick = { phoneMode = i == 1; error = null; info = null },
                            shape = SegmentedButtonDefaults.itemShape(i, 2), enabled = !busy) { Text(label) }
                    }
                }

                if (!phoneMode) {
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth().tvEditable(), label = { Text("Email") }, singleLine = true, enabled = !busy,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().tvEditable(), label = { Text("Password") }, singleLine = true, enabled = !busy,
                        shape = RoundedCornerShape(14.dp), visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (email.isNotBlank() && password.isNotEmpty()) run { auth.login(email.trim(), password) } }))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ run { auth.forgotPassword(email.trim()); info = "Password reset link sent to ${email.trim()}." } }, enabled = email.isNotBlank() && !busy,
                        modifier = Modifier.tvFocus(50.dp).heightIn(min = tvOr(52.dp))) {
                            Text("Forgot password?")
                        }
                    }
                    GlowButton("Sign In", busy, Modifier.focusRequester(signInFocus), enabled = email.isNotBlank() && password.isNotEmpty()) { run { auth.login(email.trim(), password) } }
                } else {
                    OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth().tvEditable(), label = { Text("Phone number") }, singleLine = true,
                        enabled = !busy && !otpSent, shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    if (otpSent) {
                        OutlinedTextField(otp, { otp = it }, Modifier.fillMaxWidth().tvEditable(), label = { Text("One-time code") }, singleLine = true, enabled = !busy,
                            shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton({ otpSent = false; otp = ""; info = null }, Modifier.tvFocus(50.dp).heightIn(min = tvOr(52.dp))) { Text("Use a different number") }
                        }
                        GlowButton("Verify & Sign In", busy, Modifier, enabled = otp.isNotBlank()) { run { auth.verifyOtp(phone, otp.trim()) } }
                    } else {
                        Text("Indian numbers can skip the +91.", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        GlowButton("Send Code", busy, Modifier, enabled = phone.isNotBlank()) { run { auth.sendOtp(phone); otpSent = true; info = "Code sent to $phone." } }
                    }
                }

                AnimatedVisibility(error != null || info != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Icon(if (error != null) AcIcons.AlertCircle else AcIcons.CheckCircle, null, Modifier.padding(top = 2.dp).size(16.dp),
                            tint = if (error != null) BadRed else GoodGreen)
                        Spacer(Modifier.size(8.dp))
                        Text(error ?: info.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                            color = if (error != null) BadRed else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("New to AntCloud?", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    TextButton({ activity.openUrl(Config.SIGNUP_URL) }, Modifier.tvFocus(50.dp).heightIn(min = tvOr(52.dp))) { Text("Create an account") }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Aurora(dark)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding().imePadding()
                .padding(horizontal = if (tv) 48.dp else 20.dp, vertical = if (tv) 27.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (tv) Arrangement.Center else Arrangement.Top,
        ) {
            if (tv) Row(Modifier.widthIn(max = 880.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { hero() }
                Box(Modifier.width(430.dp)) { card() }
            } else Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                hero()
                card()
            }
        }
    }
}

/**
 * Text fields on a TV. Focusing a field by moving the D-pad would pop up the keyboard, which then swallows the D-pad, so a field
 * is a normal focus stop (ring, scale) and OK starts editing it: only then does the inner field take focus and open the keyboard.
 * Moving the D-pad away from it (or closing the keyboard and pressing a direction) ends editing and navigation carries on.
 */
@Composable
private fun Modifier.tvEditable(): Modifier {
    if (!LocalIsTv.current) return this
    var editing by remember { mutableStateOf(false) }
    val inner = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing) runCatching { inner.requestFocus() } }
    return this.tvFocus(14.dp).heightIn(min = 64.dp)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { editing = true }
        .focusRequester(inner)
        .focusProperties { canFocus = editing }
        .onFocusChanged { if (!it.isFocused) editing = false }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, 24)
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/** Brand-gradient pill with a soft blue glow, for the sign-in action. Stays blue when disabled, just dimmer. */
@Composable
private fun GlowButton(title: String, busy: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val active = enabled && !busy
    Box(
        modifier.fillMaxWidth().tvFocus(100.dp).height(tvOr(60.dp, 50.dp)).alpha(if (enabled) 1f else 0.45f)
            .shadow(if (enabled) 12.dp else 0.dp, CircleShape, ambientColor = AntBlue, spotColor = AntBlue)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF5C9EFF), AntBlue, Color(0xFF5C5CE6))))
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(enabled = active, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

private fun Color.luminanceIsDark() = (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5
