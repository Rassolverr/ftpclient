package com.example.ftpclient

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream

data class FtpEntry(val name: String, val isDir: Boolean, val size: Long)

class FtpManager {
    private var ftp: FTPClient? = null

    suspend fun connect(host: String, port: Int, user: String, pass: String, tls: Boolean, passive: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                disconnect()
                val client = if (tls) FTPSClient() else FTPClient()
                client.connectTimeout = 10000
                client.defaultTimeout = 10000
                client.connect(host, port)
                if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                    client.disconnect(); return@withContext Result.failure(Exception("Refusé"))
                }
                if (!client.login(user, pass)) {
                    client.disconnect(); return@withContext Result.failure(Exception("Identifiants incorrects"))
                }
                if (tls) { (client as FTPSClient).execPBSZ(0); client.execPROT("P") }
                if (passive) client.enterLocalPassiveMode() else client.enterLocalActiveMode()
                client.setFileType(FTP.BINARY_FILE_TYPE)
                client.setControlKeepAliveTimeout(60)
                ftp = client
                Result.success("OK")
            } catch (e: Exception) { Result.failure(e) }
        }

    suspend fun list(): Result<Pair<String, List<FtpEntry>>> = withContext(Dispatchers.IO) {
        try {
            val client = ftp ?: return@withContext Result.failure(Exception("Non connecté"))
            val path = client.printWorkingDirectory()
            val entries = mutableListOf<FtpEntry>()
            try {
                client.mlistDir()?.forEach { f -> entries.add(FtpEntry(f.name, f.type == FTPFile.DIRECTORY_TYPE, f.size)) }
            } catch (e: Exception) {
                client.listNames()?.forEach { n -> entries.add(FtpEntry(n, false, 0L)) }
            }
            entries.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            Result.success(path to entries)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun cd(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try { if (ftp?.changeWorkingDirectory(path) == true) Result.success(Unit) else Result.failure(Exception("Introuvable")) } catch (e: Exception) { Result.failure(e) }
    }
    suspend fun mkdir(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        try { if (ftp?.makeDirectory(name) == true) Result.success(Unit) else Result.failure(Exception("Refusé")) } catch (e: Exception) { Result.failure(e) }
    }
    suspend fun delete(name: String, isDir: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try { if ((if (isDir) ftp?.removeDirectory(name) else ftp?.deleteFile(name)) == true) Result.success(Unit) else Result.failure(Exception("Refusé")) } catch (e: Exception) { Result.failure(e) }
    }
    suspend fun rename(oldName: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try { if (ftp?.rename(oldName, newName) == true) Result.success(Unit) else Result.failure(Exception("Refusé")) } catch (e: Exception) { Result.failure(e) }
    }
    suspend fun download(name: String, out: OutputStream): Result<Unit> = withContext(Dispatchers.IO) {
        try { if (ftp?.retrieveFile(name, out) == true) Result.success(Unit) else Result.failure(Exception("Échec")) } catch (e: Exception) { Result.failure(e) }
    }
    suspend fun upload(name: String, input: InputStream): Result<Unit> = withContext(Dispatchers.IO) {
        try { if (ftp?.storeFile(name, input) == true) Result.success(Unit) else Result.failure(Exception("Échec")) } catch (e: Exception) { Result.failure(e) }
    }

    fun disconnect() { try { ftp?.logout(); ftp?.disconnect() } catch (_: Exception) {}; ftp = null }
    companion object {
        fun humanSize(n: Long): String {
            if (n <= 0) return ""
            val units = arrayOf("o", "Ko", "Mo", "Go", "To"); var size = n.toDouble(); var i = 0
            while (size >= 1024 && i < units.size - 1) { size /= 1024; i++ }
            return if (i == 0) "$n o" else String.format("%.1f %s", size, units[i])
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { AppRoot(FtpManager()) } }
    }
}

@Composable
fun AppRoot(ftp: FtpManager) {
    var loggedIn by remember { mutableStateOf(false) }
    if (!loggedIn) LoginScreen(ftp) { loggedIn = true } else BrowserScreen(ftp) { loggedIn = false }
}

@Composable
fun LoginScreen(ftp: FtpManager, onSuccess: () -> Unit) {
    var host by remember { mutableStateOf("test.rebex.net") }
    var port by remember { mutableStateOf("21") }
    var user by remember { mutableStateOf("demo") }
    var pass by remember { mutableStateOf("password") }
    var tls by remember { mutableStateOf(false) }
    var passive by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
        Text("Connexion FTP", fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(host, { host = it }, label = { Text("Hôte") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(user, { user = it }, label = { Text("Utilisateur") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(port, { port = it }, label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.5f), singleLine = true)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("Mot de passe") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(tls, { tls = it }); Text("FTPS (TLS)", color = Color.White) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(passive, { passive = it }); Text("Passif", color = Color.White) }
        }
        
        Spacer(Modifier.height(16.dp))
        
        Button(
            onClick = {
                loading = true; status = "Connexion…"
                scope.launch {
                    val r = ftp.connect(host.trim(), port.toIntOrNull() ?: 21, user, pass, tls, passive)
                    loading = false
                    if (r.isSuccess) onSuccess() else status = "Erreur : ${r.exceptionOrNull()?.message}"
                }
            },
            enabled = !loading && host.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text(if (loading) "Patientez…" else "Se connecter", fontSize = 16.sp) }
        
        if (status.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(status, color = Color.Red) }
    }
}

@Composable
fun BrowserScreen(ftp: FtpManager, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<FtpEntry>>(emptyList()) }
    var logMsg by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var showMkdir by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf("") }
    var actionEntry by remember { mutableStateOf<FtpEntry?>(null) }
    var renameTargetName by remember { mutableStateOf("") }
    var showRename by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            loading = true
            ftp.list().onSuccess { (p, es) -> path = p; entries = es }.onFailure { logMsg = "Erreur : ${it.message}"; isError = true }
            loading = false
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val name = queryFileName(context, uri) ?: "upload_${System.currentTimeMillis()}"
            context.contentResolver.openInputStream(uri)?.use { stream ->
                logMsg = "Envoi de $name…"; isError = false; loading = true
                ftp.upload(name, stream).onSuccess { logMsg = "$name envoyé !"; refresh() }.onFailure { logMsg = "Échec : ${it.message}"; isError = true }
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().background(Color(0xFF121212))) {
        // BARRE D'OUTILS INTUITIVE
        Row(Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { scope.launch { ftp.cd(".."); refresh() } }, modifier = Modifier.weight(1f)) { Text("⬅️", fontSize = 18.sp) }
            Button(onClick = { refresh() }, modifier = Modifier.weight(1f)) { Text("🔄", fontSize = 18.sp) }
            // BOUTON ENVOYER AVEC ICÔNE
            Button(onClick = { launcher.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1.5f)) { Text("📄⬆️", fontSize = 16.sp) }
            Button(onClick = { showMkdir = true }, modifier = Modifier.weight(1f)) { Text("📁+", fontSize = 18.sp) }
            Button(onClick = { ftp.disconnect(); onLogout() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)), modifier = Modifier.weight(1f)) { Text("🚪", fontSize = 18.sp) }
        }
        
        Text(path, color = Color.Gray, modifier = Modifier.padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (logMsg.isNotEmpty()) Text(logMsg, color = if (isError) Color.Red else Color.Green, modifier = Modifier.padding(8.dp), fontSize = 12.sp)

        LazyColumn(Modifier.weight(1f)) {
            items(entries) { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (e.isDir) "📁" else "📄", fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).clickable { 
                        if (e.isDir) scope.launch { ftp.cd(e.name); refresh() }
                    }) {
                        Text(e.name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!e.isDir) Text(FtpManager.humanSize(e.size), color = Color.Gray, fontSize = 12.sp)
                    }
                    
                    if (!e.isDir) {
                        // BOUTON TÉLÉCHARGER
                        Text("⬇️", fontSize = 20.sp, modifier = Modifier.clickable {
                            scope.launch {
                                logMsg = "Téléchargement…"; isError = false; loading = true
                                val file = File(context.getExternalFilesDir(null), "downloads/${e.name}")
                                file.parentFile?.mkdirs()
                                file.outputStream().use { out -> 
                                    ftp.download(e.name, out).onSuccess { logMsg = "${e.name} téléchargé !" }.onFailure { logMsg = "Erreur : ${it.message}"; isError = true } 
                                }
                                loading = false
                            }
                        }.padding(8.dp))
                        
                        // BOUTON PARTAGER
                        Text("📤", fontSize = 20.sp, modifier = Modifier.clickable {
                            val file = File(context.getExternalFilesDir(null), "downloads/${e.name}")
                            if (file.exists()) {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "*/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Partager via"))
                            } else {
                                logMsg = "Téléchargez le fichier d'abord pour le partager."; isError = true
                            }
                        }.padding(8.dp))
                    }
                    
                    // MENU OPTIONS
                    Text("⋮", fontSize = 24.sp, color = Color.Gray, modifier = Modifier.clickable { actionEntry = e }.padding(8.dp))
                }
                Divider(color = Color.DarkGray)
            }
        }
    }

    if (actionEntry != null) {
        AlertDialog(
            onDismissRequest = { actionEntry = null },
            title = { Text(actionEntry!!.name) },
            text = { Text("Options pour ce fichier/dossier") },
            confirmButton = { TextButton(onClick = { renameTargetName = actionEntry!!.name; newName = actionEntry!!.name; showRename = true; actionEntry = null }) { Text("Renommer") } },
            dismissButton = { TextButton(onClick = { val t = actionEntry!!; scope.launch { loading = true; ftp.delete(t.name, t.isDir).onSuccess { logMsg = "${t.name} supprimé"; isError = false; refresh() }.onFailure { logMsg = "Erreur : ${it.message}"; isError = true }; loading = false }; actionEntry = null }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) { Text("Supprimer") } }
        )
    }

    if (showMkdir) {
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text("Nouveau dossier") },
            text = { OutlinedTextField(newFolder, { newFolder = it }, label = { Text("Nom") }) },
            confirmButton = { TextButton(onClick = { if (newFolder.isNotBlank()) { scope.launch { loading = true; ftp.mkdir(newFolder).onSuccess { logMsg = "Dossier créé"; isError = false; refresh() }.onFailure { logMsg = "Erreur : ${it.message}"; isError = true }; loading = false } }; showMkdir = false; newFolder = "" }) { Text("Créer") } },
            dismissButton = { TextButton(onClick = { showMkdir = false }) { Text("Annuler") } }
        )
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Renommer") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("Nouveau nom") }) },
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank() && newName != renameTargetName) { scope.launch { loading = true; ftp.rename(renameTargetName, newName).onSuccess { logMsg = "Renommé !"; isError = false; refresh() }.onFailure { logMsg = "Erreur : ${it.message}"; isError = true }; loading = false } }; showRename = false }) { Text("Valider") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Annuler") } }
        )
    }
}

private fun queryFileName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (idx >= 0) c.getString(idx) else null } else null }
} catch (_: Exception) { null }
