package ir.semnan.unitvoice;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * اپ نمونه برای تشخیص شماره‌ی واحد از گفتار فارسی.
 *
 * جریان کار:
 *  ۱) ضبط صدا از میکروفن (PCM، ۱۶ کیلوهرتز، تک‌کاناله) و تبدیل به فایل WAV؛
 *  ۲) ارسال فایل با درخواست POST چندبخشی (multipart) به نقطه‌ی پایانی /extract-unit سرور؛
 *  ۳) نمایش عدد برگردانده‌شده (یا پیام «عددی یافت نشد»).
 *
 * تمام پردازش سنگین (Whisper + استخراج عدد) در سمت سرور انجام می‌شود.
 */
public class MainActivity extends Activity {

    private static final int SAMPLE_RATE = 16000;   // نرخ نمونه‌برداری موردنیاز Whisper
    private static final int MAX_SECONDS = 10;      // حداکثر طول ضبط
    private static final int REQ_AUDIO = 101;
    private static final String PREFS = "unit_voice_prefs";
    private static final String KEY_URL = "server_url";

    private EditText serverUrl;
    private Button recordButton;
    private TextView statusText;
    private TextView resultText;
    private TextView rawText;

    private volatile boolean recording = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverUrl = findViewById(R.id.serverUrl);
        recordButton = findViewById(R.id.recordButton);
        statusText = findViewById(R.id.statusText);
        resultText = findViewById(R.id.resultText);
        rawText = findViewById(R.id.rawText);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverUrl.setText(sp.getString(KEY_URL, ""));

        recordButton.setOnClickListener(v -> onRecordClicked());
    }

    // ------------------------------------------------------------------ UI

    private void onRecordClicked() {
        if (recording) {
            recording = false;   // توقف ضبط؛ ارسال خودکار انجام می‌شود
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        String base = normalizeUrl(serverUrl.getText().toString());
        if (base == null) {
            setStatus("آدرس سرور باید با https:// شروع شود.");
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, base).apply();
        startRecordingAndSend(base);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setStatus("اجازه‌ی میکروفن داده شد؛ دوباره دکمه را بزنید.");
            } else {
                setStatus("بدون اجازه‌ی میکروفن امکان ضبط نیست.");
            }
        }
    }

    private void setStatus(final String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    private static String normalizeUrl(String input) {
        String s = input == null ? "" : input.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("/extract-unit")) {
            s = s.substring(0, s.length() - "/extract-unit".length());
        }
        if (!s.startsWith("https://") || s.length() <= "https://".length()) {
            return null;
        }
        return s;
    }

    // ------------------------------------------------------------------ ضبط

    private void startRecordingAndSend(final String baseUrl) {
        final int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            setStatus("میکروفن در دسترس نیست.");
            return;
        }

        final AudioRecord recorder;
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, SAMPLE_RATE));
        } catch (SecurityException | IllegalArgumentException e) {
            setStatus("خطا در راه‌اندازی میکروفن.");
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            setStatus("میکروفن راه‌اندازی نشد.");
            return;
        }

        recording = true;
        recordButton.setText("توقف و ارسال");
        resultText.setText("");
        rawText.setText("");
        setStatus("در حال ضبط… شماره‌ی واحد را بگویید.");

        new Thread(() -> {
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            final long maxBytes = (long) SAMPLE_RATE * 2 * MAX_SECONDS;
            try {
                recorder.startRecording();
                while (recording && pcm.size() < maxBytes) {
                    int n = recorder.read(buf, 0, buf.length);
                    if (n > 0) {
                        pcm.write(buf, 0, n);
                    } else if (n < 0) {
                        break;
                    }
                }
            } catch (Exception e) {
                // ضبط با خطا متوقف شد؛ آنچه ضبط شده بررسی می‌شود
            } finally {
                try {
                    recorder.stop();
                } catch (Exception ignored) {
                    // بی‌اهمیت
                }
                recorder.release();
                recording = false;
            }

            runOnUiThread(() -> recordButton.setText("ضبط صدا"));

            byte[] pcmBytes = pcm.toByteArray();
            if (pcmBytes.length < SAMPLE_RATE) {   // کمتر از نیم‌ثانیه
                setStatus("صدا خیلی کوتاه بود؛ دوباره تلاش کنید.");
                return;
            }

            runOnUiThread(() -> recordButton.setEnabled(false));
            setStatus("در حال ارسال به سرور…");
            sendToServer(baseUrl, buildWav(pcmBytes));
        }).start();
    }

    /** افزودن سرآیند ۴۴‌بایتی WAV به داده‌ی خام PCM. */
    private static byte[] buildWav(byte[] pcm) {
        final int channels = 1;
        final int bits = 16;
        final int byteRate = SAMPLE_RATE * channels * bits / 8;
        ByteBuffer bb = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(36 + pcm.length);
        bb.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        bb.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(16);                              // اندازه‌ی بخش fmt
        bb.putShort((short) 1);                     // PCM
        bb.putShort((short) channels);
        bb.putInt(SAMPLE_RATE);
        bb.putInt(byteRate);
        bb.putShort((short) (channels * bits / 8)); // block align
        bb.putShort((short) bits);
        bb.put("data".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(pcm.length);
        bb.put(pcm);
        return bb.array();
    }

    // ------------------------------------------------------------------ شبکه

    private void sendToServer(String baseUrl, byte[] wav) {
        HttpURLConnection conn = null;
        try {
            String boundary = "----UnitVoice" + System.currentTimeMillis();
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"audio\"; filename=\"voice.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n";
            String tail = "\r\n--" + boundary + "--\r\n";
            byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
            byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(baseUrl + "/extract-unit").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(180000);   // پردازش روی سرور ممکن است طول بکشد
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setFixedLengthStreamingMode(headBytes.length + wav.length + tailBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(headBytes);
                os.write(wav);
                os.write(tailBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(stream);

            if (code != 200) {
                setStatus("خطای سرور (کد " + code + ").");
                return;
            }

            JSONObject json = new JSONObject(body);
            final String status = json.optString("status", "");
            final String raw = json.optString("raw_text", "");
            final boolean found = "success".equals(status) && !json.isNull("unit");
            final String unitText = found ? String.valueOf(json.getInt("unit")) : "";

            runOnUiThread(() -> {
                rawText.setText(raw.isEmpty() ? "" : "متن تشخیص‌داده‌شده: " + raw);
                if (found) {
                    resultText.setText(unitText);
                    statusText.setText("شماره‌ی واحد:");
                } else {
                    resultText.setText("؟");
                    statusText.setText("عددی یافت نشد؛ لطفاً دوباره بگویید.");
                }
            });
        } catch (Exception e) {
            setStatus("ارتباط با سرور برقرار نشد: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            runOnUiThread(() -> recordButton.setEnabled(true));
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
