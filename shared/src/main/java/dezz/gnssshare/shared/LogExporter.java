/*
 * Copyright © 2025 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.gnssshare.shared;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class LogExporter {
    private static final String TAG = "LogExporter";
    private static final String LOG_FILE_MIDDLE = "-logs--";
    private static final String LOG_FILE_EXT = ".txt";
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd--HH-mm-ss", Locale.US);

    /**
     * Export logs to a file in the app's cache directory
     *
     * @param context Application context
     * @return File object pointing to the exported logs, or null if failed
     */
    public static File exportLogs(Context context, String appName) {
        File logFile = createLogFile(context, appName);
        if (logFile == null) {
            return null;
        }

        try {
            Process process = Runtime.getRuntime().exec("logcat -d *:V --pid=" + android.os.Process.myPid());
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );
            FileOutputStream output = new FileOutputStream(logFile);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output));

            String line;
            while ((line = reader.readLine()) != null) {
                writer.append(line).append("\n");
            }

            writer.close();
            output.close();
            reader.close();

            if (process.waitFor() != 0) {
                Log.e(TAG, "Failed to export logs, logcat command exited with result = " + process.exitValue());
                return null;
            }

            Log.d(TAG, "Logs exported to: " + logFile.getAbsolutePath());
            return logFile;
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "Error exporting logs", e);
            return null;
        }
    }

    /**
     * Create a log file with timestamp in the app's cache directory
     */
    private static File createLogFile(Context context, String appName) {
        try {
            String timeStamp = DATE_FORMAT.format(new Date());
            String fileName = appName + LOG_FILE_MIDDLE + timeStamp + LOG_FILE_EXT;
            File logDir = getLogDir(context);

            if (logDir.mkdirs()) {
                Log.d(TAG, "Created new log directory: " + logDir.getAbsolutePath());
            }

            File logFile = new File(logDir, fileName);
            if (logFile.createNewFile()) {
                Log.d(TAG, "Created log file: " + logFile.getAbsolutePath());
            }
            return logFile;
        } catch (IOException e) {
            Log.e(TAG, "Error creating log file", e);
            return null;
        }
    }

    /**
     * Clean up old log files (keep last 5)
     */
    public static void cleanupOldLogs(Context context, String appName) {
        try {
            File logDir = getLogDir(context);
            if (!logDir.exists() || !logDir.isDirectory()) {
                return;
            }

            File[] logFiles = logDir.listFiles((dir, name) ->
                    name.startsWith(appName + LOG_FILE_MIDDLE) && name.endsWith(LOG_FILE_EXT)
            );

            if (logFiles == null || logFiles.length <= 5) {
                return;
            }

            // Sort by last modified (newest first)
            Arrays.sort(logFiles, (f1, f2) ->
                    Long.compare(f2.lastModified(), f1.lastModified())
            );

            // Keep only the 5 most recent log files
            for (int i = 5; i < logFiles.length; i++) {
                File logFile = logFiles[i];
                String path = logFile.getAbsolutePath();
                if (logFile.delete()) {
                    Log.d(TAG, "Deleted old log file: " + path);
                } else {
                    Log.w(TAG, "Failed to delete old log file: " + path);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up old logs", e);
        }
    }

    private static File getLogDir(Context context) {
        return new File(context.getCacheDir(), "logs");
    }
}
