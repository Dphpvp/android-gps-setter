package io.github.jqssun.gpssetter.room
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

        //insert data to room database
        @Insert(onConflict = OnConflictStrategy.IGNORE)
        suspend fun insertToRoomDatabase(favorite: Favorite) : Long

        // for update single favorite
        @Update
        suspend fun updateUserDetails(favorite: Favorite)

        //delete single favorite
        @Delete
        suspend fun deleteSingleFavorite(favorite: Favorite)

       //get all Favorite inserted to room database...normally this is supposed to be a list of Favorites
        @Transaction
        @Query("SELECT * FROM favorite ORDER BY id DESC")
        fun getAllFavorites() : Flow<List<Favorite>>

        //get single favorite inserted to room database
        @Transaction
        @Query("SELECT * FROM favorite WHERE id = :id ORDER BY id DESC")
        fun getSingleFavorite(id: Long) : Favorite?

}