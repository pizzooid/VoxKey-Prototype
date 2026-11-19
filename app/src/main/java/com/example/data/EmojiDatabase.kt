package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "emoji_meta")
data class EmojiMetaEntity(
  @PrimaryKey val emoji: String,
  val customAlias: String? = null,
  val usageCount: Int = 0,
  val lastUsedAt: Long = 0L,
)

@Dao
interface EmojiMetaDao {
  @Query("SELECT * FROM emoji_meta")
  fun observeAll(): Flow<List<EmojiMetaEntity>>

  @Query("SELECT * FROM emoji_meta WHERE emoji = :emoji LIMIT 1")
  suspend fun getByEmoji(emoji: String): EmojiMetaEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: EmojiMetaEntity)
}

@Database(entities = [EmojiMetaEntity::class], version = 1, exportSchema = false)
abstract class VoxKeyDatabase : RoomDatabase() {
  abstract fun emojiMetaDao(): EmojiMetaDao

  companion object {
    @Volatile private var instance: VoxKeyDatabase? = null

    fun getInstance(context: Context): VoxKeyDatabase =
      instance
        ?: synchronized(this) {
          instance
            ?: Room.databaseBuilder(
                context.applicationContext,
                VoxKeyDatabase::class.java,
                "voxkey_emoji.db",
              )
              .build()
              .also { instance = it }
        }
  }
}

data class EmojiItem(
  val emoji: String,
  val defaultName: String,
  val customAlias: String?,
  val keywords: List<String>,
  val category: EmojiCategory,
  val usageCount: Int,
  val lastUsedAt: Long,
) {
  fun matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    if (defaultName.lowercase().contains(q)) return true
    if (customAlias?.lowercase()?.contains(q) == true) return true
    if (keywords.any { it.lowercase().contains(q) }) return true
    return emoji == q
  }
}

class EmojiRepository private constructor(private val dao: EmojiMetaDao) {

  val emojisFlow: Flow<List<EmojiItem>> =
    dao.observeAll().map { metaList ->
      val metaMap = metaList.associateBy { it.emoji }
      EmojiCatalog.all.map { def ->
        val meta = metaMap[def.emoji]
        EmojiItem(
          emoji = def.emoji,
          defaultName = def.defaultName,
          customAlias = meta?.customAlias?.takeIf { it.isNotBlank() },
          keywords = def.keywords,
          category = def.category,
          usageCount = meta?.usageCount ?: 0,
          lastUsedAt = meta?.lastUsedAt ?: 0L,
        )
      }
    }

  suspend fun recordUsage(emoji: String) {
    val existing = dao.getByEmoji(emoji)
    val now = System.currentTimeMillis()
    val updated =
      if (existing != null) {
        existing.copy(usageCount = existing.usageCount + 1, lastUsedAt = now)
      } else {
        EmojiMetaEntity(emoji = emoji, customAlias = null, usageCount = 1, lastUsedAt = now)
      }
    dao.upsert(updated)
  }

  suspend fun setCustomAlias(emoji: String, alias: String?) {
    val cleanAlias = alias?.trim()?.takeIf { it.isNotEmpty() }
    val existing = dao.getByEmoji(emoji)
    val updated =
      if (existing != null) {
        existing.copy(customAlias = cleanAlias)
      } else {
        EmojiMetaEntity(emoji = emoji, customAlias = cleanAlias, usageCount = 0, lastUsedAt = 0L)
      }
    dao.upsert(updated)
  }

  companion object {
    @Volatile private var instance: EmojiRepository? = null

    fun getInstance(context: Context): EmojiRepository =
      instance
        ?: synchronized(this) {
          val db = VoxKeyDatabase.getInstance(context)
          instance ?: EmojiRepository(db.emojiMetaDao()).also { instance = it }
        }
  }
}
