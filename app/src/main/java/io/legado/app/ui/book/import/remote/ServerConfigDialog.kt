package io.legado.app.ui.book.import.remote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.data.entities.Server
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.GSON

class ServerConfigDialog() : ComposeDialogFragment() {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val viewModel by viewModels<ServerConfigViewModel>()

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    var name by remember { mutableStateOf("") }
                    var type by remember { mutableStateOf(Server.TYPE.WEBDAV) }
                    var url by remember { mutableStateOf("") }
                    var username by remember { mutableStateOf("") }
                    var password by remember { mutableStateOf("") }
                    var path by remember { mutableStateOf("") }
                    LaunchedEffect(Unit) {
                        viewModel.init(arguments?.getLong("id")) {
                            val server = viewModel.mServer
                            name = server?.name.orEmpty()
                            type = server?.type ?: Server.TYPE.WEBDAV
                            val config = server?.getConfigJsonObject()
                            url = config?.optString("url").orEmpty()
                            username = config?.optString("username").orEmpty()
                            password = config?.optString("password").orEmpty()
                            path = config?.optString("path").orEmpty()
                        }
                    }
                    ServerConfigContent(
                        name = name,
                        type = type,
                        url = url,
                        username = username,
                        password = password,
                        path = path,
                        onNameChange = { name = it },
                        onTypeChange = { type = it },
                        onUrlChange = { url = it },
                        onUsernameChange = { username = it },
                        onPasswordChange = { password = it },
                        onPathChange = { path = it },
                        onSave = {
                            viewModel.save(
                                buildServer(name, type, url, username, password, path)
                            ) {
                                dismissAllowingStateLoss()
                            }
                        },
                        onCancel = { dismiss() }
                    )
                }
            }
        }
    }

    private fun buildServer(
        name: String,
        type: Server.TYPE,
        url: String,
        username: String,
        password: String,
        path: String
    ): Server {
        val server = viewModel.mServer?.copy() ?: Server()
        server.name = name
        server.type = type
        server.config = GSON.toJson(
            hashMapOf(
                "url" to url,
                "username" to username,
                "password" to password,
                "path" to path
            )
        )
        return server
    }
}

@Composable
private fun ServerConfigContent(
    name: String,
    type: Server.TYPE,
    url: String,
    username: String,
    password: String,
    path: String,
    onNameChange: (String) -> Unit,
    onTypeChange: (Server.TYPE) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPathChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    AppDialogFrame(
        title = stringResource(R.string.web_dav_set),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ServerField(stringResource(R.string.name), name, onNameChange, style)
                Spacer(modifier = Modifier.height(10.dp))
                // 服务器类型(WebDAV/SMB)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TypeButton(
                        text = "WebDAV",
                        selected = type == Server.TYPE.WEBDAV,
                        style = style,
                        onClick = { onTypeChange(Server.TYPE.WEBDAV) },
                        modifier = Modifier.weight(1f)
                    )
                    TypeButton(
                        text = "SMB",
                        selected = type == Server.TYPE.SMB,
                        style = style,
                        onClick = { onTypeChange(Server.TYPE.SMB) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                ServerField(stringResource(R.string.address), url, onUrlChange, style)
                Spacer(modifier = Modifier.height(10.dp))
                ServerField(stringResource(R.string.account), username, onUsernameChange, style)
                Spacer(modifier = Modifier.height(10.dp))
                ServerField(
                    stringResource(R.string.password_label),
                    password,
                    onPasswordChange,
                    style,
                    password = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                ServerField(stringResource(R.string.directory), path, onPathChange, style)
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onCancel,
                cornerRadius = style.actionRadius
            )
            LegadoMiuixActionButton(
                text = stringResource(R.string.ok),
                palette = palette,
                onClick = onSave,
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun TypeButton(
    text: String,
    selected: Boolean,
    style: AppDialogStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(style.actionRadius))
            .background(if (selected) style.accent.copy(alpha = 0.18f) else style.fieldSurface)
            .clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) style.accent else style.primaryText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun ServerField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    style: AppDialogStyle,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        shape = RoundedCornerShape(style.actionRadius),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = style.primaryText,
            unfocusedTextColor = style.primaryText,
            focusedContainerColor = style.fieldSurface,
            unfocusedContainerColor = style.fieldSurface,
            cursorColor = style.accent,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedLabelColor = style.accent,
            unfocusedLabelColor = style.secondaryText
        ),
        textStyle = LocalTextStyle.current.copy(
            color = style.primaryText,
            fontFamily = style.bodyFontFamily
        )
    )
}
