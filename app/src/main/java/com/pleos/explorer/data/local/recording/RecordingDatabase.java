package com.pleos.explorer.data.local.recording;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {RecordingEntry.class}, version = 1, exportSchema = true)
public abstract class RecordingDatabase extends RoomDatabase {
    public abstract RecordingDao entries();
}
