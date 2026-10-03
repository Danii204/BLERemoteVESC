package xyz.danilab.bleremotevesc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Actualizaciones desde GitHub Releases.
 *
 * Consulta la API publica de GitHub de forma anonima, sin cuentas ni
 * telemetria. Durante la beta se tienen en cuenta tambien las pre-releases.
 * Cada release debe llevar `BLERemoteVESC.apk` y su `.sha256`; el APK se
 * descarga, se comprueba su SHA-256 y se entrega al instalador de Android,
 * que pide confirmacion al usuario. Android solo acepta la actualizacion si
 * esta firmada con la misma clave que la version instalada.
 */
class Updater(private val context: Context) {

    companion object {
        const val REPO = "Danii204/BLERemoteVESC"
        private const val API = "https://api.github.com/repos/$REPO/releases?per_page=10"
        private const val APK = "BLERemoteVESC.apk"
        private const val TIMEOUT_MS = 15_000
    }

    data class Release(val tag: String, val nombre: String, val notas: String, val apkUrl: String, val shaUrl: String)

    sealed class Resultado {
        data class Disponible(val release: Release) : Resultado()
        object AlDia : Resultado()
        data class Error(val motivo: String) : Resultado()
    }

    private val main = Handler(Looper.getMainLooper())

    /** Version instalada, tal como la declara el propio paquete. */
    val versionInstalada: String by lazy {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    }

    /** Busca una version mas nueva que la instalada. Responde en el hilo principal. */
    fun buscar(incluirBetas: Boolean, alTerminar: (Resultado) -> Unit) {
        thread(name = "update-check") {
            val r = try {
                val lista = JSONArray(leer(API))
                var mejor: Release? = null
                for (i in 0 until lista.length()) {
                    val o = lista.getJSONObject(i)
                    if (o.optBoolean("draft")) continue
                    if (o.optBoolean("prerelease") && !incluirBetas) continue
                    val tag = o.getString("tag_name")
                    if (!esMasNueva(tag, versionInstalada)) continue
                    if (mejor != null && !esMasNueva(tag, mejor.tag)) continue
                    val assets = o.getJSONArray("assets")
                    var apk: String? = null
                    var sha: String? = null
                    for (j in 0 until assets.length()) {
                        val a = assets.getJSONObject(j)
                        when (a.getString("name")) {
                            APK -> apk = a.getString("browser_download_url")
                            "$APK.sha256" -> sha = a.getString("browser_download_url")
                        }
                    }
                    if (apk != null && sha != null) {
                        mejor = Release(tag, o.optString("name", tag), o.optString("body", ""), apk, sha)
                    }
                }
                if (mejor != null) Resultado.Disponible(mejor) else Resultado.AlDia
            } catch (e: Exception) {
                Resultado.Error("No se pudo consultar GitHub (${e.javaClass.simpleName})")
            }
            main.post { alTerminar(r) }
        }
    }

    /**
     * Descarga el APK, verifica el SHA-256 y abre el instalador. Si Android
     * aun no permite a la app instalar paquetes, abre esa pantalla de ajustes.
     */
    fun descargarEInstalar(release: Release, progreso: (Int) -> Unit, alTerminar: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            alTerminar("Permite instalar apps desde BLERemoteVESC y vuelve a pulsar Actualizar.")
            return
        }
        thread(name = "update-download") {
            val error = try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val destino = File(dir, APK)

                val esperado = leer(release.shaUrl).trim().split(Regex("\\s+")).first().lowercase()
                descargar(release.apkUrl, destino) { p -> main.post { progreso(p) } }
                val real = sha256(destino)
                if (real != esperado) {
                    destino.delete()
                    "El archivo descargado no coincide con su SHA-256. No se instala."
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", destino)
                    main.post {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    null
                }
            } catch (e: Exception) {
                "La descarga ha fallado (${e.javaClass.simpleName})"
            }
            main.post { alTerminar(error) }
        }
    }

    private fun abrir(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "BLERemoteVESC/${versionInstalada}")
        }

    private fun leer(url: String): String {
        val c = abrir(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun descargar(url: String, destino: File, progreso: (Int) -> Unit) {
        val c = abrir(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var leidos = 0L
            var ultimo = -1
            c.inputStream.use { i ->
                destino.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        leidos += n
                        if (total > 0) {
                            val p = (leidos * 100 / total).toInt()
                            if (p != ultimo) { ultimo = p; progreso(p) }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Compara versiones `vX.Y.Z` con sufijo opcional `-beta` o `-beta.N`. Una
 * version sin sufijo es mas nueva que su beta: 0.2.0 > 0.2.0-beta.3.
 */
fun esMasNueva(candidata: String, actual: String): Boolean {
    fun partes(v: String): List<Int> {
        val limpio = v.trim().removePrefix("v")
        val (base, pre) = limpio.split("-", limit = 2).let { it[0] to it.getOrNull(1) }
        val nums = base.split(".").map { it.toIntOrNull() ?: 0 }.let { it + List(3 - it.size.coerceAtMost(3)) { 0 } }
        // Sin sufijo pesa mas que cualquier beta.
        val preNum = when {
            pre == null -> Int.MAX_VALUE
            else -> pre.substringAfter(".", "0").toIntOrNull() ?: 0
        }
        return nums.take(3) + preNum
    }
    val a = partes(candidata)
    val b = partes(actual)
    for (i in a.indices) {
        if (a[i] != b[i]) return a[i] > b[i]
    }
    return false
}
