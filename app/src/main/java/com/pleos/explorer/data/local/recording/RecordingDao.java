package com.pleos.explorer.data.local.recording;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface RecordingDao {
    @Query("SELECT * FROM recording_entries WHERE id = :id") RecordingEntry get(String id);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void put(RecordingEntry entry);
    @Query("DELETE FROM recording_entries") void clear();
}
