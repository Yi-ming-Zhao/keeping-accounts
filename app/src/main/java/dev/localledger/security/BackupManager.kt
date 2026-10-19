package dev.localledger.security

import android.net.Uri
import androidx.room.withTransaction
import dev.localledger.LedgerApplication
import org.json.*
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.security.SecureRandom
import javax.crypto.*
import javax.crypto.spec.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class BackupManager(private val app:LedgerApplication,private val database:dev.localledger.data.LedgerDatabase=app.database) {
 private val tables=listOf("accounts","transactions","orders","items","allocations","evidence","categories","rules","corrections","jobs","audit","entry_evidence","order_charges","agent_runs","tool_receipts")
 private val magic="LEDGER01".toByteArray()
 private fun key(password:CharArray,salt:ByteArray):SecretKeySpec {
  require(password.size>=8) { "密码至少 8 位" }
  val spec=PBEKeySpec(password,salt,210_000,256)
  return try {SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES")} finally {spec.clearPassword()}
 }
 suspend fun export(uri:Uri,password:CharArray) = app.maintenance.withLock { exportData(uri,password) }
 private suspend fun exportData(uri:Uri,password:CharArray) {
  try {
   val payload=database.withTransaction {
    val root=JSONObject().put("version",4)
    val db=database.openHelper.readableDatabase
    for(table in tables) {
     val rows=JSONArray()
     db.query("SELECT * FROM `$table`").use { cursor->
      while(cursor.moveToNext()) {
       val row=JSONObject()
       for(i in 0 until cursor.columnCount) row.put(cursor.getColumnName(i),when(cursor.getType(i)) {
        android.database.Cursor.FIELD_TYPE_NULL->JSONObject.NULL
        android.database.Cursor.FIELD_TYPE_INTEGER->cursor.getLong(i)
        android.database.Cursor.FIELD_TYPE_FLOAT->cursor.getDouble(i)
        else->cursor.getString(i)
       })
       // Screenshots expire independently; portable backups retain text evidence only.
       if(table=="evidence") {row.put("imagePath",JSONObject.NULL);row.put("imageExpiresAt",JSONObject.NULL)}
       rows.put(row)
      }
     }
     root.put(table,rows)
    }
    root.toString().toByteArray()
   }
   require(payload.size<=32*1024*1024) { "备份超过当前版本大小限制" }
   val packed=ByteArrayOutputStream().also { out->GZIPOutputStream(out).use { it.write(payload) } }.toByteArray()
   val salt=ByteArray(16).also { SecureRandom().nextBytes(it) };val iv=ByteArray(12).also { SecureRandom().nextBytes(it) }
   val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key(password,salt),GCMParameterSpec(128,iv));updateAAD(magic)}
   app.contentResolver.openOutputStream(uri,"wt")!!.use {it.write(magic);it.write(salt);it.write(iv);it.write(cipher.doFinal(packed))}
  } finally {password.fill('\u0000')}
 }
 suspend fun restore(uri:Uri,password:CharArray) = app.maintenance.withLock { restoreData(uri,password) }
 private suspend fun restoreData(uri:Uri,password:CharArray) {
  try {
   val bytes=app.contentResolver.openInputStream(uri)!!.use { limited(it,34*1024*1024) }
   require(bytes.size>=52&&bytes.copyOfRange(0,8).contentEquals(magic)) { "备份格式不正确" }
   val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(password,bytes.copyOfRange(8,24)),GCMParameterSpec(128,bytes.copyOfRange(24,36)));updateAAD(magic)}
   val clear=try {cipher.doFinal(bytes.copyOfRange(36,bytes.size))} catch(_:Exception){error("密码错误或文件已损坏")}
   val root=JSONObject(GZIPInputStream(ByteArrayInputStream(clear)).use { limited(it,32*1024*1024).toString(Charsets.UTF_8) })
   require(root.getInt("version") in 2..4) { "不支持这个备份版本" }
   if(root.getInt("version")==2) {
    val links=JSONArray();val old=root.getJSONArray("transactions")
    for(i in 0 until old.length()) {val row=old.getJSONObject(i);links.put(JSONObject().put("entryId",row.getString("id")).put("evidenceId",row.getString("evidenceId")))}
    root.put("entry_evidence",links).put("order_charges",JSONArray())
   }
   if(root.getInt("version")<4)root.put("agent_runs",JSONArray()).put("tool_receipts",JSONArray())
   database.withTransaction {
    val db=database.openHelper.writableDatabase
    for(table in tables) {
     val columns=mutableSetOf<String>()
     db.query("PRAGMA table_info(`$table`)").use {c->while(c.moveToNext()) columns.add(c.getString(c.getColumnIndexOrThrow("name")))}
     val rows=root.getJSONArray(table);require(rows.length()<=100_000)
     for(i in 0 until rows.length()) require(rows.getJSONObject(i).keys().asSequence().toSet()==columns) { "备份字段不完整" }
    }
    // Validate essential monetary and relationship invariants before replacing any data.
    val entries=root.getJSONArray("transactions");val evidenceIds=(0 until root.getJSONArray("evidence").length()).map { root.getJSONArray("evidence").getJSONObject(it).getString("id") }.toSet()
    for(i in 0 until entries.length()) {val row=entries.getJSONObject(i);require(row.getLong("amountMinor")>=0&&row.getString("evidenceId") in evidenceIds)}
    tables.reversed().forEach { db.execSQL("DELETE FROM `$it`") }
    db.execSQL("DELETE FROM agent_runs");db.execSQL("DELETE FROM tool_receipts")
    for(table in tables) {
     val rows=root.getJSONArray(table)
     for(i in 0 until rows.length()) {
      val row=rows.getJSONObject(i)
      if(table=="evidence"){row.put("imagePath",JSONObject.NULL);row.put("imageExpiresAt",JSONObject.NULL)}
      val names=row.keys().asSequence().toList()
      db.execSQL("INSERT INTO `$table` (${names.joinToString(","){"`$it`"}}) VALUES (${names.joinToString(","){"?"}})",names.map { if(row.isNull(it))null else row.get(it) }.toTypedArray())
     }
    }
   }
   // Portable backups contain no images. Remove replaced images only after a successful commit.
   if(database===app.database) File(app.filesDir,"evidence").deleteRecursively()
  } finally {password.fill('\u0000')}
 }
 private fun limited(input:InputStream,max:Int):ByteArray {
  val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
  while(true) {val n=input.read(buffer);if(n<0)break;require(out.size()+n<=max){"备份过大"};out.write(buffer,0,n)}
  return out.toByteArray()
 }
}
