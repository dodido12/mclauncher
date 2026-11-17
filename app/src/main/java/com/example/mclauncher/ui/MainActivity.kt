package com.example.mclauncher.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.mclauncher.LauncherApp
import com.example.mclauncher.auth.Account
import com.example.mclauncher.auth.MicrosoftAuth
import com.example.mclauncher.instance.GameEngine
import com.example.mclauncher.instance.HdrSettings
import com.example.mclauncher.instance.InstanceConfig
import com.example.mclauncher.instance.LoaderType
import com.example.mclauncher.instance.RendererType
import com.example.mclauncher.launch.MinecraftLauncher
import com.example.mclauncher.net.Http
import com.example.mclauncher.net.ModrinthClient
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** UI mínima de referencia: cuentas, instancias, Modrinth y lanzamiento. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LauncherApp
        val launcher = MinecraftLauncher(this, Http.client, app.instances, app.jre, app.mojang)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { LauncherScreen(app, launcher) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherScreen(app: LauncherApp, launcher: MinecraftLauncher) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accounts by app.accounts.accounts.collectAsState()
    var selectedAccount by remember { mutableStateOf<Account?>(null) }
    var instances by remember { mutableStateOf<List<InstanceConfig>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var modrinthFor by remember { mutableStateOf<InstanceConfig?>(null) }
    var logFor by remember { mutableStateOf<InstanceConfig?>(null) }
    var deleteFor by remember { mutableStateOf<InstanceConfig?>(null) }
    var hdrFor by remember { mutableStateOf<InstanceConfig?>(null) }
    var pendingPkce by remember { mutableStateOf<MicrosoftAuth.Pkce?>(null) }

    LaunchedEffect(Unit) {
        instances = app.instances.list()
        app.accounts.refreshAllQuietly()          // renueva la sesión sola, sin pedir nada
    }
    LaunchedEffect(accounts) {
        if (selectedAccount == null || accounts.none { it.id == selectedAccount?.id })
            selectedAccount = accounts.firstOrNull { it is Account.Microsoft } ?: accounts.firstOrNull()
    }

    // Resultado de la página de Microsoft → verificación automática de la licencia
    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val pkce = pendingPkce ?: return@rememberLauncherForActivityResult
        pendingPkce = null
        val code = res.data?.getStringExtra(MicrosoftLoginActivity.RESULT_CODE)
        if (code == null) {
            status = res.data?.getStringExtra(MicrosoftLoginActivity.RESULT_ERROR) ?: "Inicio de sesión cancelado"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true; status = "Verificando tu cuenta y la licencia de Minecraft…"
            runCatching { app.accounts.completeWebLogin(code, pkce) }
                .onSuccess { selectedAccount = it; status = "✓ ${it.username} verificado. Ya no tendrás que iniciar sesión otra vez." }
                .onFailure { status = it.message ?: "Error al verificar" }
            busy = false
        }
    }

    fun startMicrosoftLogin() {
        if (app.msaClientId.isBlank()) { status = "Este APK no tiene Client ID de Azure"; return }
        runCatching { app.accounts.newWebLogin() }
            .onSuccess { (pkce, url) ->
                pendingPkce = pkce
                loginLauncher.launch(Intent(context, MicrosoftLoginActivity::class.java)
                    .putExtra(MicrosoftLoginActivity.EXTRA_URL, url)
                    .putExtra(MicrosoftLoginActivity.EXTRA_STATE, pkce.state))
            }
            .onFailure { status = it.message ?: "Error" }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("MC Launcher") })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---- Cuenta ----
            Text("Cuenta", style = MaterialTheme.typography.titleMedium)
            val ms = accounts.filterIsInstance<Account.Microsoft>()
            if (ms.isEmpty()) {
                Button(onClick = ::startMicrosoftLogin, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Iniciar sesión con Microsoft")
                }
                Text("Solo la primera vez. Después la sesión se recuerda y puedes jugar sin internet.",
                    style = MaterialTheme.typography.bodySmall)
            } else ms.forEach { acc ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(acc.username, style = MaterialTheme.typography.titleMedium)
                            Text("✓ Cuenta verificada · Minecraft: Java Edition", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton({ scope.launch { app.accounts.remove(acc.id) } }) { Text("Cerrar sesión") }
                    }
                }
            }

            // ---- Perfiles offline (solo tras verificar la cuenta) ----
            var offName by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(offName, { offName = it }, label = { Text("Nombre para perfil offline") }, singleLine = true,
                    enabled = ms.isNotEmpty(), modifier = Modifier.weight(1f))
                OutlinedButton(enabled = ms.isNotEmpty() && offName.isNotBlank(), onClick = {
                    scope.launch {
                        runCatching { app.accounts.addOffline(offName.trim()) }
                            .onSuccess { selectedAccount = it; offName = ""; status = "Perfil offline ${it.username} creado" }
                            .onFailure { status = it.message ?: "Error" }
                    }
                }) { Text("Añadir") }
            }
            if (accounts.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEach { a ->
                    FilterChip(selected = selectedAccount?.id == a.id, onClick = { selectedAccount = a },
                        label = { Text(a.username + if (a is Account.Offline) " (offline)" else "") })
                }
            }

            // ---- Instancias ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Instancias", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton({ showCreate = true }) { Text("+ Nueva") }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(instances, key = { it.id }) { inst ->
                    Card { Column(Modifier.padding(12.dp)) {
                        Text(inst.name, style = MaterialTheme.typography.titleSmall)
                        Text("${inst.minecraftVersion} · ${inst.loader.name.lowercase()} · ${inst.renderer} · ${inst.ramMb} MB" +
                            (if (inst.hdr.enabled && inst.renderer != RendererType.GL4ES) " · HDR" else ""),
                            style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = selectedAccount != null && !busy, onClick = {
                            scope.launch {
                                busy = true
                                val instDir = app.instances.instanceDir(inst.id)
                                LaunchLog.clear(instDir)
                                var lastStage = ""
                                runCatching {
                                    LaunchLog.stage(instDir, "Jugar: ${inst.minecraftVersion} · ${inst.loader} · ${inst.renderer} · ${inst.ramMb} MB")
                                    val session = app.accounts.sessionFor(selectedAccount!!.id)
                                    if (session.offline) status = "Sin internet: jugando en modo un jugador con tu cuenta verificada"
                                    launcher.prepare(inst, session) { s, p ->
                                        status = s; progress = p
                                        val key = s.substringBefore(':')
                                        if (key != lastStage) { lastStage = key; LaunchLog.stage(instDir, s) }
                                    }
                                    LaunchLog.stage(instDir, "Preparación completa: abriendo GameActivity")
                                    launcher.start(inst)
                                    instances = app.instances.list()   // el renderizador pudo cambiar a Zink
                                    status = "Abriendo el juego… Si no abre, toca \"Registro\" en la instancia."
                                }.onFailure {
                                    LaunchLog.error(instDir, it)
                                    status = "Error: ${it.message ?: it.javaClass.simpleName}"
                                }
                                busy = false
                            }
                        }) { Text("Jugar") }
                        Spacer(Modifier.width(4.dp))
                        TextButton({ modrinthFor = inst }) { Text("Mods") }
                        TextButton({ logFor = inst }) { Text("Registro") }
                        TextButton({ hdrFor = inst }, enabled = !busy && inst.renderer != RendererType.GL4ES) { Text("HDR") }
                        TextButton({ deleteFor = inst }, enabled = !busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Borrar") }
                    } } }
                }
            }
            if (status.isNotEmpty()) {
                if (busy) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showSettings) SettingsDialog(app.msaClientId, onDismiss = { showSettings = false }) {
        app.msaClientId = it; showSettings = false; status = "Client ID guardado. Toca \"Iniciar sesión con Microsoft\"."
    }
    if (showCreate) CreateInstanceDialog(
        recommendedRam = app.instances.recommendedRamMb(), maxRam = app.instances.maxRamMb(),
        onDismiss = { showCreate = false }
    ) { name, ver, loader, renderer, hdr, ram, engine ->
        scope.launch {
            val c = app.instances.create(name, ver, loader, renderer = renderer, ramMb = ram)
            app.instances.update(c.id) { it.copy(engine = GameEngine.ZALITH, hdr = it.hdr.copy(enabled = hdr && renderer != RendererType.GL4ES)) }
            instances = app.instances.list(); showCreate = false
        }
    }
    modrinthFor?.let { inst -> ModrinthDialog(app, inst) { modrinthFor = null } }
    logFor?.let { inst -> LogDialog(app, inst) { logFor = null } }
    hdrFor?.let { inst ->
        HdrCalibrationDialog(inst.hdr, onDismiss = { hdrFor = null }) { newHdr ->
            scope.launch {
                app.instances.update(inst.id) { it.copy(hdr = newHdr) }
                instances = app.instances.list()
                status = "HDR de ${inst.name}: " + if (newHdr.enabled) "blanco ${newHdr.paperWhiteNits.toInt()} nits, pico ${newHdr.maxLuminanceNits.toInt()} nits" else "desactivado"
            }
            hdrFor = null
        }
    }
    deleteFor?.let { inst ->
        AlertDialog(onDismissRequest = { deleteFor = null }, title = { Text("¿Borrar \"${inst.name}\"?") },
            text = { Text("Se borran sus mundos, mods, resourcepacks, shaders y ajustes. No se puede deshacer.") },
            confirmButton = {
                Button({
                    deleteFor = null
                    scope.launch {
                        runCatching { app.instances.delete(inst.id) }
                            .onSuccess { status = "Instancia \"${inst.name}\" borrada" }
                            .onFailure { status = "No se pudo borrar: ${it.message}" }
                        instances = app.instances.list()
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Borrar") }
            },
            dismissButton = { TextButton({ deleteFor = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun LogDialog(app: LauncherApp, inst: InstanceConfig, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("Leyendo registro…") }
    LaunchedEffect(inst.id) {
        text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { LaunchLog.report(app.instances.instanceDir(inst.id), app.instances.gameDir(inst.id)) }
                .getOrElse { "No se pudo leer el registro: $it" }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Registro · ${inst.name}") },
        text = {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = {
            Button({
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Registro MC Launcher", text.takeLast(100_000)))
            }) { Text("Copiar") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cerrar") } })
}

@Composable
private fun SettingsDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var id by remember { mutableStateOf(current) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ajustes") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Client ID de Azure (Application ID)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(id, { id = it }, singleLine = true, placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") },
                modifier = Modifier.fillMaxWidth())
            Text("Se crea gratis en portal.azure.com → App registrations. Tipo de cuenta: \"cuentas personales de Microsoft\". " +
                "En Autenticación agrega la plataforma \"Aplicaciones móviles y de escritorio\" con el redirect:\n" +
                MicrosoftAuth.REDIRECT_URI, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button({ onSave(id) }, enabled = Regex("^[0-9a-fA-F-]{36}$").matches(id.trim())) { Text("Guardar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}

@Composable
private fun CreateInstanceDialog(
    recommendedRam: Int, maxRam: Int, onDismiss: () -> Unit,
    onCreate: (String, String, LoaderType, RendererType, Boolean, Int, GameEngine) -> Unit
) {
    var hdrOn by remember { mutableStateOf(true) }
    val minRam = 1024
    val topRam = maxRam.coerceAtLeast(minRam + 512)
    var ram by remember { mutableFloatStateOf(recommendedRam.coerceIn(minRam, topRam).toFloat()) }
    var name by remember { mutableStateOf("Mi mundo") }
    var version by remember { mutableStateOf("1.21.1") }
    var loader by remember { mutableStateOf(LoaderType.FABRIC) }
    var renderer by remember { mutableStateOf(RendererType.ZINK_VULKAN) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nueva instancia") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre") })
            OutlinedTextField(version, { version = it }, label = { Text("Versión de Minecraft") })
            Text("Loader"); Row { LoaderType.entries.forEach { l -> FilterChip(loader == l, { loader = l }, { Text(l.name.lowercase()) }) } }
            Text("Renderizador"); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(renderer == RendererType.ZINK_VULKAN, { renderer = RendererType.ZINK_VULKAN }, { Text("Zink (Vulkan)") })
                FilterChip(renderer == RendererType.GL4ES, { renderer = RendererType.GL4ES }, { Text("gl4es (≤1.16)") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HDR", modifier = Modifier.weight(1f))
                Switch(checked = hdrOn && renderer != RendererType.GL4ES, onCheckedChange = { hdrOn = it }, enabled = renderer != RendererType.GL4ES)
            }
            val ramMb = (ram / 256f).roundToInt() * 256
            Text("RAM: $ramMb MB (${"%.1f".format(ramMb / 1024f)} GB)")
            Slider(value = ram, onValueChange = { ram = it }, valueRange = minRam.toFloat()..topRam.toFloat())
            Text("Recomendado: $recommendedRam MB · Máximo para tu teléfono: $topRam MB. " +
                "Más RAM no siempre es mejor: deja memoria para Android y la GPU.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button({
            onCreate(name, version, loader, renderer, hdrOn, (ram / 256f).roundToInt() * 256, GameEngine.ZALITH)
        }) { Text("Crear") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}

@Composable
private fun ModrinthDialog(app: LauncherApp, inst: InstanceConfig, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("sodium") }
    var type by remember { mutableStateOf(ModrinthClient.ProjectType.MOD) }
    var hits by remember { mutableStateOf<List<ModrinthClient.SearchHit>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Modrinth · ${inst.name}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { ModrinthClient.ProjectType.entries.take(3).forEach { t -> FilterChip(type == t, { type = t }, { Text(t.api) }) } }
            OutlinedTextField(query, { query = it }, label = { Text("Buscar") }, singleLine = true)
            Button({ scope.launch { runCatching {
                hits = app.modrinth.search(query, type, inst.minecraftVersion, app.modrinth.loaderFacetFor(type, inst)).hits
            }.onFailure { msg = it.message ?: "" } } }) { Text("Buscar") }
            LazyColumn(Modifier.heightIn(max = 320.dp)) { items(hits, key = { it.projectId }) { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) { Text(h.title); Text(h.description, maxLines = 2, style = MaterialTheme.typography.bodySmall) }
                    TextButton({ scope.launch { runCatching {
                        val files = app.modrinth.install(h.projectId, type, inst, app.instances.gameDir(inst.id)) { msg = it }
                        msg = "Instalado (SHA-512 OK): " + files.joinToString { it.name }
                    }.onFailure { msg = it.message ?: "" } } }) { Text("Instalar") }
                }
            } }
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onDismiss) { Text("Cerrar") } })
}

/** Calibración HDR (también se puede ajustar en vivo dentro del juego con el botón "HDR"). */
@Composable
private fun HdrCalibrationDialog(initial: HdrSettings, onDismiss: () -> Unit, onSave: (HdrSettings) -> Unit) {
    val context = LocalContext.current
    val panelPeak = remember {
        @Suppress("DEPRECATION")
        (context as? android.app.Activity)?.display?.hdrCapabilities?.desiredMaxLuminance?.takeIf { it > 0 } ?: 1000f
    }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var white by remember { mutableFloatStateOf(initial.paperWhiteNits.coerceIn(80f, 500f)) }
    var peak by remember { mutableFloatStateOf(initial.maxLuminanceNits.coerceIn(200f, panelPeak.coerceAtLeast(400f))) }
    var knee by remember { mutableFloatStateOf(initial.knee.coerceIn(0.3f, 0.9f)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Calibración HDR") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HDR activado", modifier = Modifier.weight(1f)); Switch(enabled, { enabled = it })
            }
            Text("Tu pantalla: hasta ${panelPeak.toInt()} nits", style = MaterialTheme.typography.bodySmall)
            Text("Blanco de referencia: ${white.toInt()} nits")
            Slider(white, { white = it }, valueRange = 80f..500f, enabled = enabled)
            Text("Brillo de menús, texto y objetos blancos normales. 203 = estándar; súbelo si el juego se ve oscuro.",
                style = MaterialTheme.typography.bodySmall)
            Text("Brillo máximo: ${peak.toInt()} nits")
            Slider(peak, { peak = it.coerceAtLeast(white) }, valueRange = 200f..panelPeak.coerceAtLeast(400f), enabled = enabled)
            Text("Hasta dónde pueden brillar el sol, el cielo, la lava y las antorchas.", style = MaterialTheme.typography.bodySmall)
            Text("Inicio de las altas luces: ${(knee * 100).toInt()} %")
            Slider(knee, { knee = it }, valueRange = 0.3f..0.9f, enabled = enabled)
            Text("Más bajo = más partes de la imagen brillan por encima del blanco; más alto = solo lo más brillante.",
                style = MaterialTheme.typography.bodySmall)
            TextButton({ white = 203f; peak = minOf(1000f, panelPeak); knee = 0.6f }, enabled = enabled) { Text("Restablecer") }
            Text("Dentro del juego, el botón HDR (arriba) abre estos mismos ajustes y se ven al instante.",
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button({ onSave(initial.copy(enabled = enabled, paperWhiteNits = white, maxLuminanceNits = maxOf(peak, white), knee = knee)) }) { Text("Guardar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}
