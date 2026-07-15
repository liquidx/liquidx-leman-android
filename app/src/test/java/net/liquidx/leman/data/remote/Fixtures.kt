package net.liquidx.leman.data.remote

object Fixtures {
    fun load(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$path")) {
            "missing fixture fixtures/$path"
        }.bufferedReader().readText()
}
