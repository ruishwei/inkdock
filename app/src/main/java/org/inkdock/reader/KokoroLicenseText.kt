package org.inkdock.reader
/** Deterministic recursive asset traversal: directories are never opened as files. */
internal object KokoroLicenseText {
    fun collect(root:String,list:(String)->List<String>,read:(String)->String):String = buildString {
        fun visit(path:String) {
            val children=list(path).sorted()
            if(children.isNotEmpty())children.forEach { visit("$path/$it") }
            else try { append("\n\n=== ").append(path).append(" ===\n").append(read(path)) }
            catch (_:java.io.FileNotFoundException) { /* absent optional directory in unbuilt source */ }
        }
        visit(root)
    }
}
