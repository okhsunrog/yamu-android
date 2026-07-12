package dev.okhsunrog.yamusdownloader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String SHARE_CATEGORY =
            "dev.okhsunrog.yamusdownloader.category.DOWNLOAD";
    private static final Pattern YANDEX_MUSIC_LINK = Pattern.compile(
            "https://music\\.yandex\\.ru/[^\\s]+"
    );

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TokenStore tokenStore;
    private EditText tokenInput;
    private EditText trackInput;
    private CheckBox rememberToken;
    private Button downloadButton;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildContent());

        tokenStore = new TokenStore(this);
        publishShareShortcut();
        String savedToken = tokenStore.load();
        tokenInput.setText(savedToken);
        rememberToken.setChecked(!savedToken.isEmpty());
        statusView.setText("Готово к работе (" + NativeBridge.mediaBackend() + ")");
        downloadButton.setOnClickListener(view -> startDownload());
        if (state == null) {
            handleIncomingIntent(getIntent());
        }
    }

    private void publishShareShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null) {
            return;
        }
        Set<String> categories = new HashSet<>();
        categories.add(SHARE_CATEGORY);
        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(this, "download-shared-track")
                .setShortLabel("Скачать")
                .setLongLabel("Скачать из Яндекс Музыки")
                .setIcon(Icon.createWithResource(this, android.R.drawable.stat_sys_download_done))
                .setIntent(new Intent(Intent.ACTION_VIEW).setClass(this, MainActivity.class))
                .setCategories(categories)
                .setRank(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setLongLived(true);
        }
        manager.addDynamicShortcuts(Collections.singletonList(builder.build()));
    }

    private View buildContent() {
        int padding = dp(20);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Yandex Music Downloader");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        form.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("Вставьте ссылку на трек или отправьте её сюда через меню «Поделиться». Скачается лучшее доступное качество.");
        hint.setTextSize(16);
        hint.setPadding(0, dp(8), 0, dp(18));
        form.addView(hint, matchWrap());

        tokenInput = new EditText(this);
        tokenInput.setHint("OAuth token");
        tokenInput.setSingleLine(true);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(tokenInput, matchWrap());

        rememberToken = new CheckBox(this);
        rememberToken.setText("Сохранить token локально");
        form.addView(rememberToken, matchWrap());

        trackInput = new EditText(this);
        trackInput.setHint("https://music.yandex.ru/album/…/track/…");
        trackInput.setSingleLine(false);
        trackInput.setMinLines(2);
        form.addView(trackInput, matchWrap());

        downloadButton = new Button(this);
        downloadButton.setText("Скачать трек");
        form.addView(downloadButton, matchWrap());

        statusView = new TextView(this);
        statusView.setText("Готово к работе");
        statusView.setTextIsSelectable(true);
        statusView.setPadding(0, dp(18), 0, 0);
        form.addView(statusView, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        return scroll;
    }

    private void startDownload() {
        String token = tokenInput.getText().toString().trim();
        String track = extractTrackLink(trackInput.getText().toString());
        if (token.isEmpty() || track == null) {
            Toast.makeText(this, "Нужны token и ссылка на трек", Toast.LENGTH_SHORT).show();
            return;
        }
        trackInput.setText(track);

        if (rememberToken.isChecked()) {
            tokenStore.save(token);
        } else {
            tokenStore.clear();
        }

        File output = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (output == null) {
            statusView.setText("Android не предоставил Music directory");
            return;
        }
        downloadButton.setEnabled(false);
        statusView.setText("Скачивание…");
        executor.execute(() -> {
            try {
                String path = NativeBridge.downloadTrack(token, track, output.getAbsolutePath());
                runOnUiThread(() -> {
                    statusView.setText("Сохранено:\n" + path);
                    downloadButton.setEnabled(true);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    statusView.setText("Ошибка:\n" + error.getMessage());
                    downloadButton.setEnabled(true);
                });
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String candidate = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            candidate = intent.getDataString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())
                && "text/plain".equals(intent.getType())) {
            candidate = intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        String link = extractTrackLink(candidate);
        if (link == null) {
            return;
        }
        trackInput.setText(link);
        if (tokenInput.getText().toString().trim().isEmpty()) {
            statusView.setText("Ссылка получена. Введите token, чтобы скачать трек.");
            tokenInput.requestFocus();
        } else {
            statusView.setText("Ссылка получена, начинаю скачивание…");
            downloadButton.post(this::startDownload);
        }
    }

    private static String extractTrackLink(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = YANDEX_MUSIC_LINK.matcher(text.trim());
        if (!matcher.find()) {
            return null;
        }
        return matcher.group().replaceFirst("[),.;!?]+$", "");
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
