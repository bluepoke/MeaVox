// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, TakeEntity::class, SettingEntity::class, LyricsEntity::class, LyricsOffsetEntity::class, MixEntity::class],
    version = 18,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // 2: takes and settings tables (#8)
        AutoMigration(from = 2, to = 3), // 3: pipeline progress and failure reason (#7)
        AutoMigration(from = 3, to = 4), // 4: lyrics table (#11)
        AutoMigration(from = 4, to = 5), // 5: lyrics rows go with their Track (#11)
        AutoMigration(from = 5, to = 6), // 6: lyrics offsets (#12)
        AutoMigration(from = 6, to = 7), // 7: each Take's Transpose (#10)
        AutoMigration(from = 7, to = 8), // 8: when each Track was last sung (#20)
        AutoMigration(from = 8, to = 9), // 9: Vocal-Free Tracks (#25)
        AutoMigration(from = 9, to = 10), // 10: each Track's Backing Audio choice (#34)
        AutoMigration(from = 10, to = 11), // 11: the Separation Model picked for each Track (#17)
        AutoMigration(from = 11, to = 12), // 12: mixes table (#14)
        AutoMigration(from = 12, to = 13), // 13: each Track's Last Transpose (#23)
        AutoMigration(from = 13, to = 14), // 14: failure reasons as a kind and a service, for the UI to word (#43)
        AutoMigration(from = 14, to = 15), // 15: each Mix's Vocal Effects and Guide Vocal level (#16)
        AutoMigration(from = 15, to = 16), // 16: the model whose Instrumental plays while a Track is re-separated (#37)
        AutoMigration(from = 16, to = 17), // 17: the Latency Correction each Take was kept with (#9)
        AutoMigration(from = 17, to = 18), // 18: where each Track's Cover Art is downloaded from (#42)
    ],
)
abstract class MeaVoxDatabase : RoomDatabase() {
    internal abstract fun tracks(): TrackDao

    internal abstract fun takes(): TakeDao

    internal abstract fun settings(): SettingsDao

    internal abstract fun lyrics(): LyricsDao

    internal abstract fun mixes(): MixDao

    companion object {
        fun create(context: Context): MeaVoxDatabase =
            Room.databaseBuilder(context.applicationContext, MeaVoxDatabase::class.java, "meavox.db").build()
    }
}
