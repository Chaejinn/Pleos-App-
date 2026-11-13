package com.pleos.explorer.data.local.recording;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Only opaque IDs and ciphertext are persisted; coordinates stay inside payload. */
@Entity(tableName = "recording_entries")
public class RecordingEntry {
    @PrimaryKey @NonNull public String id;
    @NonNull public byte[] payload;
    public RecordingEntry(@NonNull String id, @NonNull byte[] payload) {
        this.id = id;
        this.payload = payload;
    }
}
