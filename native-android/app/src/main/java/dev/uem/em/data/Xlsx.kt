package dev.uem.em.data

import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Minimal spreadsheet files: writes .xlsx workbooks (opened by Google Sheets, Excel, LibreOffice)
 * row by row, and reads .xlsx (including files downloaded from Google Sheets) and .csv back. Only
 * cell values matter here: text and numbers; formulas come back as their last value.
 */
object Xlsx {
    /** A sheet written row by row: `rows` is called with an emitter. */
    class SheetOut(val name: String, val header: List<String>, val rows: ((List<Any?>) -> Unit) -> Unit)

    private fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }; return sb.toString() }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;"); ch == '<' -> sb.append("&lt;"); ch == '>' -> sb.append("&gt;"); ch == '"' -> sb.append("&quot;")
            ch < ' ' && ch != '\t' && ch != '\n' -> {}
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    fun write(sheets: List<SheetOut>, out: OutputStream) {
        ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                sheets.withIndex().joinToString("") { (i, s) -> """<sheet name="${esc(s.name.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" } + "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                """<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf/><xf fontId="1" applyFont="1"/></cellXfs></styleSheet>""")
            for ((i, s) in sheets.withIndex()) {
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${i + 1}.xml"))
                val w = zip.bufferedWriter()
                w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetData>""")
                var r = 0
                fun row(cells: List<Any?>, bold: Boolean) {
                    r++
                    w.write("<row r=\"$r\">")
                    for ((c, v) in cells.withIndex()) {
                        if (v == null || (v is String && v.isEmpty())) continue
                        val ref = col(c) + r
                        val st = if (bold) " s=\"1\"" else ""
                        when (v) {
                            is Number -> { val d = v.toDouble(); w.write("<c r=\"$ref\"$st><v>${if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()}</v></c>") }
                            else -> w.write("<c r=\"$ref\" t=\"inlineStr\"$st><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>")
                        }
                    }
                    w.write("</row>")
                }
                row(s.header, true)
                s.rows { row(it, false) }
                w.write("</sheetData></worksheet>")
                w.flush()
                zip.closeEntry()
            }
        }
    }

    // ------------------------------------------------------------------------------ reading

    private val ENT = Regex("&(amp|lt|gt|quot|apos|#x[0-9a-fA-F]+|#[0-9]+);")
    private fun unesc(s: String): String = if ('&' !in s) s else ENT.replace(s) { m ->
        when (val e = m.groupValues[1]) { "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; else -> if (e.startsWith("#x")) e.drop(2).toInt(16).toChar().toString() else e.drop(1).toInt().toChar().toString() }
    }
    private val T_TAG = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
    private val T_EMPTY = Regex("<t(?:\\s[^>]*)?/>")
    private val CELL = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val V_TAG = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
    private val ATTR_R = Regex("\\br=\"([A-Z]+)\\d+\"")
    private val ATTR_T = Regex("\\bt=\"([a-zA-Z]+)\"")
    private val SHEET = Regex("<sheet\\b[^>]*?name=\"([^\"]*)\"[^>]*?r:id=\"([^\"]*)\"")
    private val REL = Regex("<Relationship\\b[^>]*?Id=\"([^\"]*)\"[^>]*?Target=\"([^\"]*)\"")
    private val REL2 = Regex("<Relationship\\b[^>]*?Target=\"([^\"]*)\"[^>]*?Id=\"([^\"]*)\"")

    private fun colIndex(letters: String): Int { var n = 0; for (ch in letters) n = n * 26 + (ch - 'A' + 1); return n - 1 }

    /** Read chunks ending with `endTag` from a stream (one row or shared string at a time). */
    private fun chunks(r: Reader, endTag: String, each: (String) -> Unit) {
        val buf = CharArray(1 shl 16)
        val sb = StringBuilder()
        while (true) {
            val n = r.read(buf)
            if (n < 0) break
            sb.append(buf, 0, n)
            var idx = sb.indexOf(endTag)
            var start = 0
            while (idx >= 0) {
                each(sb.substring(start, idx + endTag.length))
                start = idx + endTag.length
                idx = sb.indexOf(endTag, start)
            }
            if (start > 0) sb.delete(0, start)
        }
    }

    /** Sheet name → rows (cell text, blank for empty). Reads the whole workbook. */
    fun read(input: InputStream): LinkedHashMap<String, List<List<String>>> {
        // Keep the small parts in memory, sheets as raw bytes (compressed in the zip they are small).
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory && (e.name.startsWith("xl/") || e.name.startsWith("/xl/"))) parts[e.name.removePrefix("/")] = z.readBytes()
                e = z.nextEntry
            }
        }
        val shared = ArrayList<String>()
        parts["xl/sharedStrings.xml"]?.let { b -> chunks(b.inputStream().reader(), "</si>") { si -> shared.add(unesc(T_TAG.findAll(si).joinToString("") { it.groupValues[1] })) } }
        val wb = String(parts["xl/workbook.xml"] ?: throw IllegalArgumentException("Not a spreadsheet (.xlsx)"))
        val relsText = parts["xl/_rels/workbook.xml.rels"]?.let { String(it) } ?: ""
        val rels = HashMap<String, String>()
        for (m in REL.findAll(relsText)) rels[m.groupValues[1]] = m.groupValues[2]
        for (m in REL2.findAll(relsText)) rels.putIfAbsent(m.groupValues[2], m.groupValues[1])
        val out = LinkedHashMap<String, List<List<String>>>()
        for (m in SHEET.findAll(wb)) {
            val name = unesc(m.groupValues[1])
            val target = rels[m.groupValues[2]] ?: continue
            val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
            val bytes = parts[path] ?: continue
            val rows = ArrayList<List<String>>()
            chunks(bytes.inputStream().reader(), "</row>") { rowXml ->
                val cells = ArrayList<String>()
                var next = 0
                for (c in CELL.findAll(rowXml.substring(rowXml.indexOf("<row").coerceAtLeast(0)))) {
                    val attrs = c.groupValues[1]
                    val body = c.groupValues[2]
                    val ci = ATTR_R.find(attrs)?.groupValues?.get(1)?.let { colIndex(it) } ?: next
                    val type = ATTR_T.find(attrs)?.groupValues?.get(1) ?: "n"
                    val v = when (type) {
                        "s" -> V_TAG.find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        "inlineStr" -> unesc(T_TAG.findAll(body).joinToString("") { it.groupValues[1] }).let { if (it.isEmpty() && T_EMPTY.containsMatchIn(body)) "" else it }
                        else -> unesc(V_TAG.find(body)?.groupValues?.get(1) ?: "")
                    }
                    while (cells.size < ci) cells.add("")
                    if (cells.size == ci) cells.add(v) else cells[ci] = v
                    next = ci + 1
                }
                rows.add(cells)
            }
            out[name] = rows
        }
        return out
    }

    /** A CSV file (one sheet), with quoted fields. */
    fun readCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val f = StringBuilder()
        var q = false
        var i = 0
        val s = text.removePrefix("\uFEFF")
        while (i < s.length) {
            val ch = s[i]
            if (q) {
                if (ch == '"') { if (i + 1 < s.length && s[i + 1] == '"') { f.append('"'); i++ } else q = false } else f.append(ch)
            } else when (ch) {
                '"' -> q = true
                ',' -> { row.add(f.toString()); f.clear() }
                '\r' -> {}
                '\n' -> { row.add(f.toString()); f.clear(); rows.add(row); row = ArrayList() }
                else -> f.append(ch)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return rows
    }
}
