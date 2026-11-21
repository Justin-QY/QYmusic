package com.qymusic.app.utils

import android.util.Log
import org.litepal.LitePal

/**
 * 本地数据库结构迁移。
 *
 * <p>历史问题：FavoriteModel 新增 source 字段后没有同步提升 litepal.xml 的版本号，
 * 老用户设备上的 favoritemodel 表缺少该列，导致
 * `SELECT * FROM FavoriteModel WHERE musicId = ? AND source = ?` 直接抛
 * `no such column: source`，收藏判断失败后连搜索/首页一起报错。</p>
 *
 * <p>这里在应用启动时做一次幂等的兜底检查：缺列就补列，历史数据统一按旧音源 kw 回填，
 * 这样即使 LitePal 自动升级没有触发，也不会再出现缺列异常。</p>
 */
object DatabaseMigration {

  private const val TAG = "DatabaseMigration"

  /** LitePal 由类名生成的表名（全小写） */
  private const val FAVORITE_TABLE = "favoritemodel"

  /**
   * 确保收藏表存在 source 列，并回填历史数据。
   *
   * 整个流程都包在 try/catch 里：迁移失败也不能拦住应用启动，
   * 后续查询本身也有容错处理。
   */
  fun ensureSchema() {
    try {
      // 打开数据库；LitePal 会在这里完成建表与版本升级
      val db = LitePal.getDatabase()

      // 1.0.10 新增收藏歌单 / 歌手表：即使用户数据库版本号未同步升级，也保证表存在
      db.execSQL(
        "CREATE TABLE IF NOT EXISTS favoriteplaylistmodel (" +
          "id INTEGER PRIMARY KEY AUTOINCREMENT, playlistId INTEGER, name TEXT, " +
          "cover TEXT, author TEXT, \"desc\" TEXT, source TEXT, createAt INTEGER)"
      )
      db.execSQL(
        "CREATE TABLE IF NOT EXISTS favoriteartistmodel (" +
          "id INTEGER PRIMARY KEY AUTOINCREMENT, artistId INTEGER, name TEXT, " +
          "cover TEXT, source TEXT, createAt INTEGER)"
      )

      val columns = mutableSetOf<String>()
      db.rawQuery("PRAGMA table_info($FAVORITE_TABLE)", null).use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex >= 0) {
          while (cursor.moveToNext()) {
            columns.add(cursor.getString(nameIndex))
          }
        }
      }

      if (columns.isEmpty()) {
        // 表还没建出来，交给 LitePal 处理
        return
      }

      if (!columns.contains("source")) {
        db.execSQL("ALTER TABLE $FAVORITE_TABLE ADD COLUMN source TEXT")
        Log.i(TAG, "favoritemodel 已补充 source 列")
      }

      // 旧版本只有 kw 曲库，历史收藏的 source 为空时按 kw 回填，
      // 保证旧收藏能继续被识别为已收藏。
      db.execSQL("UPDATE $FAVORITE_TABLE SET source = 'kw' WHERE source IS NULL OR source = ''")
    } catch (e: Exception) {
      Log.w(TAG, "数据库结构检查失败，跳过", e)
    }
  }
}
