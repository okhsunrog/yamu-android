package dev.okhsunrog.yamusdownloader;

import android.app.Activity;
import android.graphics.Typeface;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
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
        String savedToken = tokenStore.load();
        tokenInput.setText(savedToken);
        rememberToken.setChecked(!savedToken.isEmpty());
        statusView.setText("Готово к работе (" + NativeBridge.mediaBackend() + ")");
        downloadButton.setOnClickListener(view -> startDownload());
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
        hint.setText("Первый Android MVP: скачивает один трек в лучшем доступном качестве через Rust и in-process FFmpeg.");
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
        trackInput.setHint("Track ID или ссылка music.yandex.ru");
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
        String track = trackInput.getText().toString().trim();
        if (token.isEmpty() || track.isEmpty()) {
            Toast.makeText(this, "Нужны token и ссылка на трек", Toast.LENGTH_SHORT).show();
            return;
        }

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
