package cl.iqs.fotosap

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Escritor mínimo de Word .docx (Office Open XML) sin dependencias externas: títulos, párrafos,
 * tablas (encabezado con fondo) e imágenes JPEG/PNG. Hoja A4 vertical con márgenes de 2 cm.
 */
class Docx {
    private val cuerpo = StringBuilder()
    private val imagenes = mutableListOf<Pair<String, ByteArray>>()   // nombre en media/, bytes

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun run(t: String, negrita: Boolean = false, tam: Int = 0, color: String = ""): String {
        val pr = buildString {
            if (negrita) append("<w:b/>")
            if (color.isNotEmpty()) append("<w:color w:val=\"$color\"/>")
            if (tam > 0) append("<w:sz w:val=\"${tam * 2}\"/><w:szCs w:val=\"${tam * 2}\"/>")
        }
        // Saltos de línea dentro del texto → <w:br/>
        return t.split('\n').joinToString("<w:br/>") { "<w:r>${if (pr.isNotEmpty()) "<w:rPr>$pr</w:rPr>" else ""}<w:t xml:space=\"preserve\">${esc(it)}</w:t></w:r>" }
            .let { if (t.isEmpty()) "" else it }
    }

    private fun parrafo(contenido: String, centrado: Boolean = false, antes: Int = 0, despues: Int = 120, saltoAntes: Boolean = false) =
        "<w:p><w:pPr>${if (saltoAntes) "<w:pageBreakBefore/>" else ""}<w:spacing w:before=\"$antes\" w:after=\"$despues\"/>" +
        "${if (centrado) "<w:jc w:val=\"center\"/>" else ""}</w:pPr>$contenido</w:p>"

    fun titulo(t: String, tam: Int = 16, saltoPagina: Boolean = false) {
        cuerpo.append(parrafo(run(t, true, tam, "0E3A5F"), antes = if (saltoPagina) 0 else 200, despues = 120, saltoAntes = saltoPagina))
    }

    fun texto(t: String, tam: Int = 10, negrita: Boolean = false, color: String = "", centrado: Boolean = false) {
        cuerpo.append(parrafo(run(t, negrita, tam, color), centrado))
    }

    /** Tabla con [anchos] en cm; la primera fila va en negrita con fondo azul claro si [encabezado]. */
    fun tabla(filas: List<List<String>>, anchos: List<Double>, encabezado: Boolean = true, primeraColNegrita: Boolean = false) {
        val tw = anchos.map { (it * 567).toInt() }
        cuerpo.append("<w:tbl><w:tblPr><w:tblW w:w=\"${tw.sum()}\" w:type=\"dxa\"/><w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach {
            cuerpo.append("<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"C9D2DC\"/>")
        }
        cuerpo.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:left w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
        tw.forEach { cuerpo.append("<w:gridCol w:w=\"$it\"/>") }
        cuerpo.append("</w:tblGrid>")
        filas.forEachIndexed { r, fila ->
            val cab = encabezado && r == 0
            cuerpo.append("<w:tr>")
            if (cab) cuerpo.append("<w:trPr><w:tblHeader/></w:trPr>")
            fila.forEachIndexed { k, celda ->
                cuerpo.append("<w:tc><w:tcPr><w:tcW w:w=\"${tw.getOrElse(k) { 1000 }}\" w:type=\"dxa\"/>")
                if (cab) cuerpo.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"D6E3F0\"/>")
                else if (primeraColNegrita && k == 0) cuerpo.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EEF1F5\"/>")
                cuerpo.append("</w:tcPr>")
                cuerpo.append(parrafo(run(celda, cab || (primeraColNegrita && k == 0), 9), despues = 40))
                cuerpo.append("</w:tc>")
            }
            cuerpo.append("</w:tr>")
        }
        cuerpo.append("</w:tbl>")
        cuerpo.append(parrafo("", despues = 60))
    }

    /** Imagen centrada de [anchoCm] de ancho (alto proporcional a [anchoPx] × [altoPx]), limitada a [maxAltoCm]. */
    fun imagen(bytes: ByteArray, png: Boolean, anchoPx: Int, altoPx: Int, anchoCm: Double, maxAltoCm: Double = 24.0) {
        val n = imagenes.size + 1
        val nombre = "img$n.${if (png) "png" else "jpg"}"
        imagenes.add(nombre to bytes)
        var w = anchoCm; var h = anchoCm * altoPx / anchoPx.coerceAtLeast(1)
        if (h > maxAltoCm) { w *= maxAltoCm / h; h = maxAltoCm }
        val cx = (w * 360000).toLong(); val cy = (h * 360000).toLong()
        val dib = "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$n\" name=\"Imagen $n\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
            "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"$nombre\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"rIdImg$n\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
        cuerpo.append(parrafo(dib, centrado = true))
    }

    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(nombre: String, contenido: String) {
                z.putNextEntry(ZipEntry(nombre)); z.write(contenido.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
            put("[Content_Types].xml", xml +
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                """<Default Extension="xml" ContentType="application/xml"/>""" +
                """<Default Extension="jpg" ContentType="image/jpeg"/><Default Extension="png" ContentType="image/png"/>""" +
                """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
                """</Types>""")
            put("_rels/.rels", xml +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
                """</Relationships>""")
            put("word/_rels/document.xml.rels", xml +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                imagenes.mapIndexed { i, (n, _) ->
                    """<Relationship Id="rIdImg${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/$n"/>"""
                }.joinToString("") + """</Relationships>""")
            put("word/document.xml", xml +
                """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
                """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
                """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
                """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
                """xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""" +
                cuerpo.toString() +
                """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""" +
                """<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="567" w:footer="567" w:gutter="0"/></w:sectPr>""" +
                """</w:body></w:document>""")
            imagenes.forEach { (n, b) -> z.putNextEntry(ZipEntry("word/media/$n")); z.write(b); z.closeEntry() }
        }
        return out.toByteArray()
    }
}
