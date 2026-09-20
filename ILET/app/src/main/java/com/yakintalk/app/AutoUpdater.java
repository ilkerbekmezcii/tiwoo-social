package com.yakintalk.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AutoUpdater {
    private static final String DRIVE_FILE_ID = "14M8sBj7ir2gmOjkd-byo7sSDvQFQCwHJ";
    private static final String DRIVE_VIEW_URL =
            "https://drive.google.com/file/d/" + DRIVE_FILE_ID + "/view";
    private static final String DRIVE_DOWNLOAD_URL =
            "https://drive.usercontent.google.com/download?id=" + DRIVE_FILE_ID +
            "&export=download&confirm=t";

    private static final String PREFS = "ilet_updater";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_PENDING_APK = "pending_apk";
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static final AtomicBoolean checking = new AtomicBoolean(false);

    private AutoUpdater() {}

    public static void check(Activity activity, boolean manual) {
        if (!isWifiConnected(activity)) {
            if (manual) {
                Toast.makeText(activity,
                        "Güncelleme kontrolü yalnızca Wi‑Fi bağlantısında yapılır.",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }

        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_CHECK, 0L);
        if (!manual && now - last < AUTO_CHECK_INTERVAL_MS) return;
        if (!checking.compareAndSet(false, true)) return;

        prefs.edit().putLong(KEY_LAST_CHECK, now).apply();

        new Thread(() -> {
            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                finishWithError(activity, manual, "Güncelleme klasörü açılamadı.");
                return;
            }

            File part = new File(dir, "ilet-latest.apk.part");
            File apk = new File(dir, "ilet-latest.apk");

            try {
                download(part);

                if (!looksLikeApk(part)) {
                    part.delete();
                    throw new IllegalStateException("Drive dosyasına doğrudan erişilemedi");
                }

                if (apk.exists() && !apk.delete()) {
                    throw new IllegalStateException("Eski güncelleme dosyası silinemedi");
                }
                if (!part.renameTo(apk)) {
                    copy(part, apk);
                    part.delete();
                }

                PackageManager pm = activity.getPackageManager();
                PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                PackageInfo current = pm.getPackageInfo(activity.getPackageName(), 0);

                if (candidate == null ||
                        candidate.packageName == null ||
                        !activity.getPackageName().equals(candidate.packageName)) {
                    apk.delete();
                    throw new IllegalStateException("Drive dosyası geçerli bir İLET APK'sı değil");
                }

                long candidateCode = versionCode(candidate);
                long currentCode = versionCode(current);

                if (candidateCode > currentCode) {
                    activity.runOnUiThread(() -> showUpdateDialog(
                            activity,
                            apk,
                            candidate.versionName == null ? "yeni sürüm" : candidate.versionName
                    ));
                } else {
                    apk.delete();
                    if (manual) {
                        activity.runOnUiThread(() ->
                                Toast.makeText(activity,
                                        "İLET zaten güncel.",
                                        Toast.LENGTH_SHORT).show());
                    }
                }
            } catch (Exception e) {
                part.delete();
                if (manual) {
                    activity.runOnUiThread(() -> showDriveFallback(activity));
                }
            } finally {
                checking.set(false);
            }
        }, "ILET-Updater").start();
    }

    public static boolean resumePendingInstall(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String path = prefs.getString(KEY_PENDING_APK, null);
        if (path == null || path.isEmpty()) return false;

        File apk = new File(path);
        if (!apk.exists()) {
            prefs.edit().remove(KEY_PENDING_APK).apply();
            return false;
        }

        if (Build.VERSION.SDK_INT >= 26 &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            return false;
        }

        prefs.edit().remove(KEY_PENDING_APK).apply();
        launchInstaller(activity, apk);
        return true;
    }

    private static void showUpdateDialog(Activity activity, File apk, String versionName) {
        if (activity.isFinishing() ||
                (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;

        new AlertDialog.Builder(activity)
                .setTitle("İLET güncellemesi hazır")
                .setMessage(versionName + " sürümü Drive'dan indirildi. Şimdi güncellensin mi?")
                .setNegativeButton("Sonra", null)
                .setPositiveButton("Güncelle", (d, w) -> install(activity, apk))
                .show();
    }

    private static void install(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= 26 &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PENDING_APK, apk.getAbsolutePath())
                    .apply();

            Intent settings = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())
            );
            activity.startActivity(settings);
            return;
        }

        launchInstaller(activity, apk);
    }

    private static void launchInstaller(Activity activity, File apk) {
        Uri uri = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".fileprovider",
                apk
        );

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(intent);
    }

    private static void showDriveFallback(Activity activity) {
        if (activity.isFinishing() ||
                (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;

        new AlertDialog.Builder(activity)
                .setTitle("Drive güncellemesine erişilemiyor")
                .setMessage("İLET-latest.apk dosyasını Google Drive'da “bağlantıya sahip herkes görüntüleyebilir” yapın. İsterseniz dosyayı Drive'da açabilirsiniz.")
                .setNegativeButton("Kapat", null)
                .setPositiveButton("Drive'ı aç", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(DRIVE_VIEW_URL));
                    activity.startActivity(i);
                })
                .show();
    }

    private static void finishWithError(Activity activity, boolean manual, String text) {
        checking.set(false);
        if (manual) {
            activity.runOnUiThread(() ->
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show());
        }
    }

    private static void download(File destination) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(DRIVE_DOWNLOAD_URL).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "ILET-Android-Updater/1.0");
        connection.connect();

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }

        try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[32768];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
                if (total > 250L * 1024L * 1024L) {
                    throw new IllegalStateException("Güncelleme dosyası çok büyük");
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean looksLikeApk(File file) {
        if (!file.exists() || file.length() < 4) return false;
        try (FileInputStream in = new FileInputStream(file)) {
            return in.read() == 0x50 &&
                    in.read() == 0x4B &&
                    in.read() == 0x03 &&
                    in.read() == 0x04;
        } catch (Exception e) {
            return false;
        }
    }

    private static void copy(File source, File destination) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static long versionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
        return info.versionCode;
    }

    private static boolean isWifiConnected(Context context) {
        try {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= 23) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }

        android.net.NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null &&
                info.isConnected() &&
                info.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (SecurityException e) {
            return false;
        }
    }
}
