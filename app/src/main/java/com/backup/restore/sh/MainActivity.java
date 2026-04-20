package com.backup.restore.sh;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.*;
import dev.rikka.shizuku.Shizuku;
import dev.rikka.shizuku.ShizukuRemoteProcess;
import dev.rikka.shizuku.ShizukuShell;

public class MainActivity extends AppCompatActivity {

    private EditText etPackage;
    private TextView tvLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        etPackage = findViewById(R.id.et_package);
        tvLog = findViewById(R.id.tv_log);
        checkFilesPermission();
        setupCards();
    }

    private void setupCards() {
        setupCard(findViewById(R.id.card_uninstall), "حذف تطبيق", "حذف مع إبقاء البيانات (-k)", v -> executeCmd("pm uninstall -k " + getPkg()));
        setupCard(findViewById(R.id.card_reboot), "إعادة التشغيل", "إعادة تشغيل فورية للجهاز", v -> executeCmd("reboot"));
        setupCard(findViewById(R.id.card_backup), "نسخ احتياطي (AB)", "حفظ في Downloads/aa.ab", v -> startBackup(getPkg()));
        setupCard(findViewById(R.id.card_restore), "استعادة (AB)", "استعادة من Downloads/aa.ab", v -> startRestore());
    }

    private void setupCard(View v, String title, String desc, View.OnClickListener l) {
        ((TextView)v.findViewById(R.id.title)).setText(title);
        ((TextView)v.findViewById(R.id.desc)).setText(desc);
        v.findViewById(R.id.btn_action).setOnClickListener(l);
    }

    private String getPkg() { return etPackage.getText().toString().trim(); }

    private void checkFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            new MaterialAlertDialogBuilder(this).setTitle("إذن الملفات").setMessage("يرجى منح إذن الوصول للملفات").setPositiveButton("إعدادات", (d, w) -> {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }).show();
        }
    }

    private boolean isReady() {
        if (!Shizuku.pingBinder()) { showShizukuError(); return false; }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(100); return false; }
        return true;
    }

    private void showShizukuError() {
        new MaterialAlertDialogBuilder(this).setTitle("Shizuku مطلوب").setMessage("الخدمة لا تعمل").setPositiveButton("فتح", (d, w) -> {
            startActivity(getPackageManager().getLaunchIntentForPackage("dev.rikka.shizuku"));
        }).show();
    }

    private void executeCmd(String cmd) {
        if (!isReady()) return;
        try {
            ShizukuShell.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            log("Executed: " + cmd);
        } catch (Exception e) { log("Error: " + e.getMessage()); }
    }

    private void startBackup(String pkg) {
        if (!isReady()) return;
        new Thread(() -> {
            try {
                File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "aa.ab");
                ShizukuRemoteProcess p = ShizukuShell.newProcess(new String[]{"sh", "-c", "bu backup -noapk " + pkg}, null, null);
                InputStream is = p.getInputStream();
                FileOutputStream fos = new FileOutputStream(f);
                byte[] buf = new byte[16384]; int len;
                while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
                fos.close(); is.close();
                runOnUiThread(() -> log("Saved to Downloads/aa.ab"));
            } catch (Exception e) { runOnUiThread(() -> log("Fail: " + e.getMessage())); }
        }).start();
    }

    private void startRestore() {
        if (!isReady()) return;
        new Thread(() -> {
            try {
                File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "aa.ab");
                if (!f.exists()) { runOnUiThread(() -> log("File not found")); return; }
                ShizukuRemoteProcess p = ShizukuShell.newProcess(new String[]{"sh", "-c", "bu restore"}, null, null);
                OutputStream os = p.getOutputStream();
                FileInputStream fis = new FileInputStream(f);
                byte[] buf = new byte[16384]; int len;
                while ((len = fis.read(buf)) != -1) os.write(buf, 0, len);
                os.flush(); os.close(); fis.close();
                runOnUiThread(() -> log("Restore started... Check Screen."));
            } catch (Exception e) { runOnUiThread(() -> log("Fail: " + e.getMessage())); }
        }).start();
    }

    private void log(String m) { runOnUiThread(() -> tvLog.append("\n> " + m)); }
} // القوس الأخير الذي كان مفقوداً!
