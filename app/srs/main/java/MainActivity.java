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
import android.widget.Toast;
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

        // طلب إذن الملفات عند تشغيل التطبيق
        checkFilesPermission();
        
        initializeUI();
    }

    private void initializeUI() {
        // 1. زر إلغاء التثبيت
        setupCommandCard(findViewById(R.id.card_uninstall), "حذف تطبيق", "حذف مع إبقاء البيانات (-k)", 
            v -> executeSimpleCmd("pm uninstall -k " + getPackageNameInput()));

        // 2. زر إعادة التشغيل
        setupCommandCard(findViewById(R.id.card_reboot), "إعادة التشغيل", "إعادة تشغيل الجهاز فوراً", 
            v -> executeSimpleCmd("reboot"));

        // 3. زر النسخ الاحتياطي
        setupCommandCard(findViewById(R.id.card_backup), "نسخ احتياطي AB", "إنشاء ملف aa.ab في الذاكرة", 
            v -> performBackup(getPackageNameInput()));

        // 4. زر الاستعادة
        setupCommandCard(findViewById(R.id.card_restore), "استعادة بيانات AB", "استعادة من ملف aa.ab المحلي", 
            v -> performRestore());
    }

    private void setupCommandCard(View card, String title, String desc, View.OnClickListener action) {
        ((TextView) card.findViewById(R.id.title)).setText(title);
        ((TextView) card.findViewById(R.id.desc)).setText(desc);
        card.findViewById(R.id.btn_action).setOnClickListener(action);
    }

    private String getPackageNameInput() {
        return etPackage.getText().toString().trim();
    }

    // --- نظام فحص الأذونات (الملفات و Shizuku) ---

    private void checkFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new MaterialAlertDialogBuilder(this)
                    .setTitle("إذن الوصول للملفات")
                    .setMessage("يحتاج التطبيق إلى إذن الوصول لجميع الملفات لحفظ واستعادة نسخ .ab بنجاح. يرجى تفعيله من الإعدادات.")
                    .setPositiveButton("تفعيل الآن", (d, w) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.addCategory("android.intent.category.DEFAULT");
                            intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                            startActivity(intent);
                        } catch (Exception e) {
                            Intent intent = new Intent();
                            intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivity(intent);
                        }
                    })
                    .setCancelable(false)
                    .show();
            }
        }
    }

    private boolean isSystemReady() {
        // فحص Shizuku
        if (!Shizuku.pingBinder()) {
            showShizukuError("خدمة Shizuku متوقفة! يرجى تشغيلها أولاً.");
            return false;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(100);
            return false;
        }
        // فحص إذن الملفات في أندرويد 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, "يرجى منح إذن الوصول للملفات أولاً", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void showShizukuError(String msg) {
        new MaterialAlertDialogBuilder(this)
            .setTitle("تنبيه: Shizuku مطلوب")
            .setMessage(msg)
            .setPositiveButton("فتح Shizuku", (d, w) -> {
                Intent i = getPackageManager().getLaunchIntentForPackage("dev.rikka.shizuku");
                if (i != null) startActivity(i);
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    // --- تنفيذ الأوامر عبر الأنابيب (Pipes) ---

    private void executeSimpleCmd(String cmd) {
        if (!isSystemReady()) return;
        try {
            ShizukuShell.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            log("تم التنفيذ: " + cmd);
        } catch (Exception e) { log("خطأ: " + e.getMessage()); }
    }

    private void performBackup(String pkg) {
        if (!isSystemReady()) return;
        log("بدء النسخ... وافق على شاشة التأكيد.");
        new Thread(() -> {
            try {
                // حفظ الملف في مجلد التحميلات الرئيسي ليسهل العثور عليه
                File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "aa.ab");
                ShizukuRemoteProcess p = ShizukuShell.newProcess(new String[]{"sh", "-c", "bu backup -noapk " + pkg}, null, null);
                InputStream is = p.getInputStream();
                FileOutputStream fos = new FileOutputStream(file);
                byte[] buffer = new byte[16384]; // 16KB
                int len;
                while ((len = is.read(buffer)) != -1) fos.write(buffer, 0, len);
                fos.close();
                is.close();
                runOnUiThread(() -> log("تم الحفظ في: Downloads/aa.ab"));
            } catch (Exception e) { runOnUiThread(() -> log("فشل: " + e.getMessage())); }
        }).start();
    }

    private void performRestore() {
        if (!isSystemReady()) return;
        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "aa.ab");
        if (!file.exists()) { log("الملف غير موجود في Downloads/aa.ab"); return; }

        log("بدء الاستعادة... وافق على الشاشة.");
        new Thread(() -> {
            try {
                ShizukuRemoteProcess p = ShizukuShell.newProcess(new String[]{"sh", "-c", "bu restore"}, null, null);
                OutputStream os = p.getOutputStream();
                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[16384];
                int len;
                while ((len = fis.read(buffer)) != -1) os.write(buffer, 0, len);
                os.flush(); os.close();
                fis.close();
                runOnUiThread(() -> log("اكتمل ضخ البيانات بنجاح."));
            } catch (Exception e) { runOnUiThread(() -> log("فشل الاستعادة: " + e.getMessage())); }
        }).start();
    }

    private void log(String msg) {
        runOnUiThread(() -> tvLog.append("\n> " + msg));
    }
}
