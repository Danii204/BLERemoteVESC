package xyz.danilab.bleremotevesc

/**
 * Colores de la app en sus tres modos, con los roles de Material 3: claro
 * (por defecto, para pleno sol), oscuro y AMOLED (negro puro, ahorra bateria
 * en pantallas OLED). Los modos de potencia tienen color propio: Eco verde,
 * Crucero naranja y Sport rojo.
 */
data class Palette(
    val id: String,
    val claro: Boolean,
    val surface: Int,
    val s1: Int,
    val s2: Int,
    val s3: Int,
    val onSurface: Int,
    val onVar: Int,
    val outline: Int,
    val outlineVar: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryC: Int,
    val onPrimaryC: Int,
    val rev: Int,
    val revC: Int,
    val error: Int,
    val onError: Int,
    val ok: Int,
    val okC: Int,
    val eco: Int,
    val ecoC: Int,
    val cru: Int,
    val cruC: Int,
    val spo: Int,
    val spoC: Int,
    /** Boton Parar: rojo fuerte en todos los temas, que se vea sin dudar. */
    val stopBg: Int = 0xFFD93025.toInt(),
    val onStop: Int = 0xFFFFFFFF.toInt(),
) {
    companion object {
        private fun c(v: Long) = v.toInt()

        val CLARO = Palette(
            "claro", true,
            surface = c(0xFFF8FAFD), s1 = c(0xFFF0F4F9), s2 = c(0xFFE9EEF6), s3 = c(0xFFDDE3EA),
            onSurface = c(0xFF1F1F1F), onVar = c(0xFF444746), outline = c(0xFF747775), outlineVar = c(0xFFC4C7C5),
            primary = c(0xFF0B57D0), onPrimary = c(0xFFFFFFFF), primaryC = c(0xFFD3E3FD), onPrimaryC = c(0xFF041E49),
            rev = c(0xFFB3541E), revC = c(0xFFFFDBCB),
            error = c(0xFFB3261E), onError = c(0xFFFFFFFF),
            ok = c(0xFF146C2E), okC = c(0xFFC4EED0),
            eco = c(0xFF146C2E), ecoC = c(0xFFC4EED0),
            cru = c(0xFF9A4500), cruC = c(0xFFFFDBCB),
            spo = c(0xFFB3261E), spoC = c(0xFFF9DEDC),
            stopBg = c(0xFFB3261E),
        )

        val OSCURO = Palette(
            "oscuro", false,
            surface = c(0xFF131314), s1 = c(0xFF1B1B1C), s2 = c(0xFF1E1F20), s3 = c(0xFF282A2C),
            onSurface = c(0xFFE3E3E3), onVar = c(0xFFC4C7C5), outline = c(0xFF8E918F), outlineVar = c(0xFF444746),
            primary = c(0xFFA8C7FA), onPrimary = c(0xFF062E6F), primaryC = c(0xFF0842A0), onPrimaryC = c(0xFFD3E3FD),
            rev = c(0xFFFFB693), revC = c(0xFF7A2E00),
            error = c(0xFFF2B8B5), onError = c(0xFF601410),
            ok = c(0xFF6DD58C), okC = c(0xFF0F5223),
            eco = c(0xFF6DD58C), ecoC = c(0xFF0F5223),
            cru = c(0xFFFFB693), cruC = c(0xFF7A2E00),
            spo = c(0xFFF2B8B5), spoC = c(0xFF8C1D18),
        )

        val AMOLED = OSCURO.copy(
            id = "amoled",
            surface = c(0xFF000000), s1 = c(0xFF0E0E0F), s2 = c(0xFF141415), s3 = c(0xFF1F2021),
            outlineVar = c(0xFF333537),
        )

        fun byId(id: String?) = when (id) {
            "oscuro" -> OSCURO
            "amoled" -> AMOLED
            else -> CLARO
        }
    }
}
