package cl.iqs.fotosap

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Escritor mínimo de Excel .xlsx (Office Open XML) sin dependencias externas.
 * Cada hoja: fila 1 = encabezado (negrita, fondo azul claro, fija y con filtro).
 * Las celdas pueden ser String, Number o null. Los números con decimales usan formato 0.00.
 */
class Xlsx {
    class Hoja(val nombre: String, val encabezado: List<String>, val anchos: List<Int>) {
        val filas = mutableListOf<List<Any?>>()
        fun fila(vararg celdas: Any?) { filas.add(celdas.toList()) }
    }

    private val hojas = mutableListOf<Hoja>()

    fun hoja(nombre: String, encabezado: List<String>, anchos: List<Int>): Hoja {
        var base = nombre.replace(Regex("[\\\\/?*\\[\\]:]"), "-").trim().ifEmpty { "Hoja" }.take(31)
        var n = 2
        val usados = hojas.map { it.nombre.lowercase() }
        val original = base
        while (base.lowercase() in usados) { val suf = " ($n)"; base = original.take(31 - suf.length) + suf; n++ }
        return Hoja(base, encabezado, anchos).also { hojas.add(it) }
    }

    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(nombre: String, contenido: String) {
                z.putNextEntry(ZipEntry(nombre)); z.write(contenido.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            put("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
                append("""<Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                hojas.indices.forEach {
                    append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
                }
                append("</Types>")
            })
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
                "</Relationships>")
            put("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                hojas.forEachIndexed { i, h -> append("""<sheet name="${esc(h.nombre)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
                append("</sheets><definedNames>")
                hojas.forEachIndexed { i, h ->
                    val ultima = maxOf(2, h.filas.size + 1)
                    append("""<definedName name="_xlnm._FilterDatabase" localSheetId="$i" hidden="1">""")
                    append(esc("'${h.nombre.replace("'", "''")}'!\$A\$1:\$${col(h.encabezado.size - 1)}\$$ultima"))
                    append("</definedName>")
                }
                append("</definedNames></workbook>")
            })
            put("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                hojas.indices.forEach {
                    append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""")
                }
                append("""<Relationship Id="rId${hojas.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
                append("</Relationships>")
            })
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                """<numFmts count="1"><numFmt numFmtId="164" formatCode="0.00"/></numFmts>""" +
                """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FF0E3A5F"/><name val="Calibri"/></font></fonts>""" +
                """<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""" +
                """<fill><patternFill patternType="solid"><fgColor rgb="FFE3ECF5"/><bgColor indexed="64"/></patternFill></fill></fills>""" +
                """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
                """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
                """<cellXfs count="4">""" +
                """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top"/></xf>""" +
                """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>""" +
                """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="top"/></xf>""" +
                """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>""" +
                "</cellXfs>" +
                """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
                "</styleSheet>")
            hojas.forEachIndexed { i, h -> put("xl/worksheets/sheet${i + 1}.xml", hojaXml(h)) }
        }
        return out.toByteArray()
    }

    private fun hojaXml(h: Hoja) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("<cols>")
        h.encabezado.indices.forEach {
            val w = h.anchos.getOrElse(it) { 14 }
            append("""<col min="${it + 1}" max="${it + 1}" width="$w" customWidth="1"/>""")
        }
        append("</cols><sheetData>")
        (listOf<List<Any?>>(h.encabezado) + h.filas).forEachIndexed { r, fila ->
            append("""<row r="${r + 1}">""")
            fila.forEachIndexed { c, v ->
                val ref = "${col(c)}${r + 1}"
                when {
                    v == null -> {}
                    v is Number -> {
                        val d = v.toDouble()
                        val estilo = if (d % 1.0 != 0.0) 2 else 0
                        append("""<c r="$ref" s="$estilo"><v>${if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()}</v></c>""")
                    }
                    else -> {
                        val s = v.toString()
                        if (s.isEmpty()) return@forEachIndexed
                        val estilo = if (r == 0) 1 else if (s.length > 40 || s.contains('\n')) 3 else 0
                        append("""<c r="$ref" t="inlineStr" s="$estilo"><is><t xml:space="preserve">${esc(s)}</t></is></c>""")
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        append("""<autoFilter ref="A1:${col(h.encabezado.size - 1)}${maxOf(2, h.filas.size + 1)}"/>""")
        append("</worksheet>")
    }

    companion object {
        fun col(i: Int): String {
            var n = i + 1
            val sb = StringBuilder()
            while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }
            return sb.toString()
        }

        fun esc(s: String) = s.filter { it == '\n' || it == '\t' || it >= ' ' }
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
