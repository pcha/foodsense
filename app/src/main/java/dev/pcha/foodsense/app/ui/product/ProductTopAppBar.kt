package dev.pcha.foodsense.app.ui.product

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.pcha.foodsense.app.R
import dev.pcha.foodsense.app.data.auth.User
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductTopAppBar(
    currentUser: User?,
    onNavigateToLogin: () -> Unit,
    onSignOut: () -> Unit,
) {
    var showAccountMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            UserAccountAction(
                currentUser = currentUser,
                onClick = {
                    if (currentUser != null) showAccountMenu = true else onNavigateToLogin()
                },
            )
            AccountMenu(
                currentUser = currentUser,
                expanded = showAccountMenu,
                onDismiss = { showAccountMenu = false },
                onSignOut = {
                    showAccountMenu = false
                    onSignOut()
                },
            )
        },
    )
}

@Composable
private fun UserAccountAction(
    currentUser: User?,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        if (currentUser != null) {
            UserAvatar(user = currentUser, size = 32.dp)
        } else {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = stringResource(R.string.cd_account),
            )
        }
    }
}

@Composable
private fun AccountMenu(
    currentUser: User?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSignOut: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        currentUser?.email?.let { email ->
            DropdownMenuItem(
                text = { Text(email, style = MaterialTheme.typography.bodySmall) },
                onClick = {},
                enabled = false,
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.account_sign_out)) },
            onClick = onSignOut,
        )
    }
}

@Composable
private fun UserAvatar(user: User, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val photoUrl = user.photoUrl
    if (photoUrl != null) {
        UserPhotoAvatar(photoUrl = photoUrl, size = size, modifier = modifier)
    } else {
        UserInitialAvatar(user = user, size = size, modifier = modifier)
    }
}

@Composable
private fun UserPhotoAvatar(photoUrl: String, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(model = photoUrl, contentDescription = null, modifier = modifier.size(size).clip(CircleShape))
}

@Composable
private fun UserInitialAvatar(user: User, size: Dp, modifier: Modifier = Modifier) {
    val initial = (user.displayName?.firstOrNull() ?: user.email?.firstOrNull() ?: '?').uppercaseChar()
    val avatarColors = listOf(0xFF1976D2, 0xFF388E3C, 0xFF7B1FA2, 0xFFF57C00, 0xFF0097A7)
    val bgColor = remember(user.uid) {
        Color(avatarColors[user.uid.hashCode().absoluteValue % avatarColors.size])
    }
    Box(
        modifier = modifier.size(size).background(bgColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial.toString(),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
