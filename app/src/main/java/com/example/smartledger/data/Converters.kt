package com.example.smartledger.data

import androidx.room.TypeConverter

/** Room 不支持枚举开箱即用，这里统一转成字符串存储，可读性也更好 */
class Converters {

    @TypeConverter
    fun toDirection(value: String): Direction = Direction.valueOf(value)

    @TypeConverter
    fun fromDirection(value: Direction): String = value.name

    @TypeConverter
    fun toSource(value: String): TxSource = TxSource.valueOf(value)

    @TypeConverter
    fun fromSource(value: TxSource): String = value.name

    @TypeConverter
    fun toStatus(value: String): TxStatus = TxStatus.valueOf(value)

    @TypeConverter
    fun fromStatus(value: TxStatus): String = value.name
}
