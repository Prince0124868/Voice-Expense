package com.example.voiceexpense

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class Expense(val time: Long, val amount: Double, val category: String, val note: String)

class ExpenseStore(private val ctx: Context) {

    companion object {
        const val RETENTION_MONTHS = 6      // change this if you want to keep more/less
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }

    // Visible over USB on your laptop: Android/data/com.example.voiceexpense/files/ExpenseVoice
    private val root = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "ExpenseVoice").apply { mkdirs() }
    private val backups = File(root, "backups").apply { mkdirs() }
    private val db = File(ctx.filesDir, "expenses.tsv")
    private val prefs = ctx.getSharedPreferences("voice_expense", Context.MODE_PRIVATE)

    fun treeUri(): Uri? = prefs.getString("tree", null)?.let { Uri.parse(it) }
    fun setTree(u: Uri) { prefs.edit().putString("tree", u.toString()).apply() }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    @Synchronized
    fun add(e: Expense) {
        db.appendText("${e.time}\t${e.amount}\t${clean(e.category)}\t${clean(e.note)}\n")
        sync()
    }

    @Synchronized
    fun load(): List<Expense> {
        if (!db.exists()) return emptyList()
        return db.readLines().mapNotNull { line ->
            val p = line.split("\t")
            if (p.size < 4) null else try {
                Expense(p[0].toLong(), p[1].toDouble(), p[2], p[3])
            } catch (e: Exception) { null }
        }
    }

    /** Drops data older than 6 months, rebuilds the Excel file, saves monthly backup, copies to laptop folder. */
    @Synchronized
    fun sync() {
        val cutoff = Calendar.getInstance().apply { add(Calendar.MONTH, -RETENTION_MONTHS) }.timeInMillis
        val all = load()
        val kept = all.filter { it.time >= cutoff }.sortedBy { it.time }
        if (kept.size != all.size) {
            db.writeText(kept.joinToString("") { "${it.time}\t${it.amount}\t${it.category}\t${it.note}\n" })
        }
        val bytes = buildXlsx(kept)
        val monthName = "backup_" + SimpleDateFormat("yyyy-MM", Locale.US).format(Date()) + ".xlsx"

        File(root, "expenses.xlsx").writeBytes(bytes)
        File(backups, monthName).writeBytes(bytes)
        backups.listFiles()?.filter { it.name.startsWith("backup_") }
            ?.sortedBy { it.name }?.dropLast(RETENTION_MONTHS)?.forEach { it.delete() }

        treeUri()?.let { tree ->
            try {
                writeToTree(tree, "expenses.xlsx", bytes)
                writeToTree(tree, monthName, bytes)
                pruneTree(tree)
            } catch (e: Exception) { /* folder removed/unavailable: local copy is still safe */ }
        }
    }

    // ---------- laptop-sync folder (Storage Access Framework) ----------

    private fun listTree(tree: Uri): List<Pair<String, Uri>> {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = mutableListOf<Pair<String, Uri>>()
        ctx.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(c.getString(1) to DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
            }
        }
        return out
    }

    private fun writeToTree(tree: Uri, name: String, bytes: ByteArray) {
        val existing = listTree(tree).firstOrNull { it.first == name }?.second
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val target = existing ?: DocumentsContract.createDocument(ctx.contentResolver, parent, XLSX_MIME, name) ?: return
        ctx.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) }
    }

    private fun pruneTree(tree: Uri) {
        listTree(tree).filter { it.first.startsWith("backup_") }
            .sortedBy { it.first }.dropLast(RETENTION_MONTHS)
            .forEach { DocumentsContract.deleteDocument(ctx.contentResolver, it.second) }
    }

    // ---------- minimal .xlsx writer (no libraries needed) ----------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun sheetXml(rows: List<List<Any>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, v ->
                val ref = "${'A' + c}${r + 1}"
                if (v is Number) sb.append("<c r=\"$ref\"><v>$v</v></c>")
                else sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${esc(v.toString())}</t></is></c>")
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun buildXlsx(list: List<Expense>): ByteArray {
        val dt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val mo = SimpleDateFormat("yyyy-MM", Locale.US)

        val expenseRows = mutableListOf<List<Any>>(listOf("Date", "Amount", "Category", "Note"))
        list.forEach { expenseRows.add(listOf(dt.format(Date(it.time)), it.amount, it.category, it.note)) }

        val summaryRows = mutableListOf<List<Any>>(listOf("Month", "Category", "Total"))
        list.groupBy { mo.format(Date(it.time)) to it.category }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .forEach { (k, v) -> summaryRows.add(listOf(k.first, k.second, v.sumOf { it.amount })) }

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, content: String) {
                z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            val hdr = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
            val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            put("[Content_Types].xml", hdr +
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                """<Default Extension="xml" ContentType="application/xml"/>""" +
                """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
                """<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
                """</Types>""")
            put("_rels/.rels", hdr +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="$rel/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", hdr +
                """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="$rel"><sheets>""" +
                """<sheet name="Expenses" sheetId="1" r:id="rId1"/><sheet name="Summary" sheetId="2" r:id="rId2"/>""" +
                """</sheets></workbook>""")
            put("xl/_rels/workbook.xml.rels", hdr +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="$rel/worksheet" Target="worksheets/sheet1.xml"/>""" +
                """<Relationship Id="rId2" Type="$rel/worksheet" Target="worksheets/sheet2.xml"/></Relationships>""")
            put("xl/worksheets/sheet1.xml", sheetXml(expenseRows))
            put("xl/worksheets/sheet2.xml", sheetXml(summaryRows))
        }
        return bos.toByteArray()
    }
}
