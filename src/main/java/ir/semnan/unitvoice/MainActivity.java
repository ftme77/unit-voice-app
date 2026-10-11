package ir.semnan.unitvoice;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
 *  ۲) ارسال فایل با درخواست POST چندبخشی (multipart) به /extract-unit-async سرور؛
 *     سرور فوراً یک job_id برمی‌گرداند؛
 *  ۳) اپ هر ۳ ثانیه از /result/{job_id} می‌پرسد «آماده شد؟» تا نتیجه برسد
 *     (این روش برای سروری که روی CPU کند است لازم است، چون ارتباط طولانی قطع می‌شود)؛
 *  ۴) نمایش عدد برگردانده‌شده (یا پیام «عددی یافت نشد»).
 *
 * اگر سرور مسیر ناهمزمان را نداشته باشد (کد ۴۰۴)، اپ به مسیر قدیمی /extract-unit برمی‌گردد.
 * ظاهر اپ: صفحه‌ی اصلی فقط دکمه‌ی ضبط و نتیجه را نشان می‌دهد؛ آدرس سرور داخل «تنظیمات سرور» است.
 * تمام پردازش سنگین (Whisper + استخراج عدد) در سمت سرور انجام می‌شود.
 */
public class MainActivity extends Activity {

    private static final int SAMPLE_RATE = 16000;   // نرخ نمونه‌برداری موردنیاز Whisper
    private static final int MAX_SECONDS = 10;      // حداکثر طول ضبط
    private static final int REQ_AUDIO = 101;
    private static final String PREFS = "unit_voice_prefs";
    private static final String KEY_URL = "server_url";
    private static final int POLL_INTERVAL_MS = 3000;       // هر چند میلی‌ثانیه بپرس «آماده شد؟»
    private static final int POLL_MAX_SECONDS = 420;        // حداکثر انتظار برای نتیجه (۷ دقیقه)
    private static final int POLL_MAX_FAILS = 6;            // چند خطای پشت‌سرهم تحمل شود

    // وضعیت‌های «برچسب وضعیت» (رنگ برچسب را تعیین می‌کنند)
    private static final int CHIP_OK = 0;     // عادی (آبی)
    private static final int CHIP_REC = 1;    // در حال ضبط (قرمز)
    private static final int CHIP_WARN = 2;   // هشدار یا خطا (کهربایی)

    private static final String TIP_TITLE_DEFAULT = "راهنما";
    private static final String TIP_BODY_DEFAULT = "دکمه را بزنید و مثلاً بگویید «واحد هشتاد و دو»";

    private EditText serverUrl;
    private EditText manualInput;
    private TextView manualButton;
    private View manualCard;
    private ScrollView scroll;
    private TextView statusText;
    private TextView resultText;
    private TextView rawText;
    private TextView resultLabel;
    private TextView recordLabel;
    private TextView timerText;
    private TextView tipTitle;
    private TextView tipBody;
    private View recordWrap;
    private View recordCircle;
    private View settingsToggle;
    private View settingsPanel;
    private View tipCard;
    private View resultCard;
    private ImageView recordIcon;
    private ImageView recordRing;
    private ProgressBar spinner;
    private ProgressBar progressBar;

    private Typeface fontRegular;
    private Typeface fontBold;
    private ObjectAnimator pulse;

    private volatile boolean recording = false;
    private volatile boolean gotResult = false;   // کارت نتیجه روی صفحه است؟

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverUrl = findViewById(R.id.serverUrl);
        statusText = findViewById(R.id.statusText);
        resultText = findViewById(R.id.resultText);
        rawText = findViewById(R.id.rawText);
        resultLabel = findViewById(R.id.resultLabel);
        recordLabel = findViewById(R.id.recordLabel);
        timerText = findViewById(R.id.timerText);
        tipTitle = findViewById(R.id.tipTitle);
        tipBody = findViewById(R.id.tipBody);
        recordWrap = findViewById(R.id.recordWrap);
        recordCircle = findViewById(R.id.recordCircle);
        settingsToggle = findViewById(R.id.settingsToggle);
        settingsPanel = findViewById(R.id.settingsPanel);
        tipCard = findViewById(R.id.tipCard);
        resultCard = findViewById(R.id.resultCard);
        recordIcon = findViewById(R.id.recordIcon);
        recordRing = findViewById(R.id.recordRing);
        spinner = findViewById(R.id.spinner);
        progressBar = findViewById(R.id.progressBar);
        manualInput = findViewById(R.id.manualInput);
        manualButton = findViewById(R.id.manualButton);
        manualCard = findViewById(R.id.manualCard);
        scroll = findViewById(R.id.scroll);

        loadFonts();
        applyFonts(findViewById(android.R.id.content));
        styleSystemBars();

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = sp.getString(KEY_URL, "");
        serverUrl.setText(saved);

        settingsToggle.setOnClickListener(v -> showSettings(settingsPanel.getVisibility() != View.VISIBLE));
        recordWrap.setOnClickListener(v -> onRecordClicked());
        manualButton.setOnClickListener(v -> onManualSubmit());
        manualInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onManualSubmit();
                return true;
            }
            return false;
        });

        if (saved.trim().isEmpty()) {
            // بار اول: آدرس سرور هنوز وارد نشده؛ بخش تنظیمات را باز نشان بده
            showSettings(true);
            setWarn("اول آدرس سرور را در «تنظیمات سرور» بگذارید.");
        }
    }

    @Override
    protected void onDestroy() {
        stopPulse();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private void onRecordClicked() {
        if (recording) {
            recordWrap.setEnabled(false);   // جلوگیری از لمس دوباره تا پایان ضبط
            recording = false;   // توقف ضبط؛ ارسال خودکار انجام می‌شود
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        String typed = serverUrl.getText().toString();
        String base = normalizeUrl(typed);
        if (base == null) {
            showSettings(true);
            serverUrl.requestFocus();
            setWarn(typed.trim().isEmpty()
                    ? "اول آدرس سرور را در «تنظیمات سرور» بگذارید."
                    : "آدرس سرور باید با https:// شروع شود.");
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, base).apply();
        showSettings(false);
        startRecordingAndSend(base);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setStatus("اجازه‌ی میکروفن داده شد؛ دوباره دکمه را بزنید.");
            } else {
                setWarn("بدون اجازه‌ی میکروفن امکان ضبط نیست.");
            }
        }
    }

    /** پیام عادی در برچسب وضعیت. */
    private void setStatus(final String text) {
        setChip(text, CHIP_OK);
    }

    /** پیام هشدار/خطا در برچسب وضعیت. */
    private void setWarn(final String text) {
        setChip(text, CHIP_WARN);
    }

    private void setChip(final String text, final int kind) {
        runOnUiThread(() -> {
            int bg;
            int color;
            if (kind == CHIP_REC) {
                bg = R.drawable.chip_rec;
                color = 0xFF9A3A22;
            } else if (kind == CHIP_WARN) {
                bg = R.drawable.chip_warn;
                color = 0xFF8A5A12;
            } else {
                bg = R.drawable.chip_ok;
                color = 0xFF1A4C7C;
            }
            statusText.setText(text);
            statusText.setBackgroundResource(bg);
            statusText.setTextColor(color);
        });
    }

    private void showSettings(boolean show) {
        settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void loadFonts() {
        try {
            fontRegular = Typeface.createFromAsset(getAssets(), "fonts/Vazirmatn-Regular.ttf");
            fontBold = Typeface.createFromAsset(getAssets(), "fonts/Vazirmatn-Bold.ttf");
        } catch (Exception e) {
            // اگر فونت پیدا نشد، اپ با فونت پیش‌فرض گوشی کار می‌کند
            fontRegular = null;
            fontBold = null;
        }
    }

    /** فونت وزیرمتن را روی همه‌ی نوشته‌های صفحه می‌گذارد (متن‌های پررنگ با فایل پررنگ). */
    private void applyFonts(View v) {
        if (fontRegular == null || fontBold == null || v == null) {
            return;
        }
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Typeface cur = tv.getTypeface();
            boolean bold = cur != null && cur.isBold();
            tv.setTypeface(bold ? fontBold : fontRegular);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyFonts(g.getChildAt(i));
            }
        }
    }

    /** نوار بالا و پایین گوشی هم‌رنگ پس‌زمینه‌ی اپ می‌شود. */
    private void styleSystemBars() {
        getWindow().setStatusBarColor(0xFFF5F2ED);
        getWindow().setNavigationBarColor(0xFFF5F2ED);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    /** اعداد لاتین ← اعداد فارسی (فقط برای نمایش). */
    private static String fa(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= '0' && c <= '9' ? (char) ('\u06F0' + (c - '0')) : c);
        }
        return sb.toString();
    }

    private static String two(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private void startPulse() {
        stopPulse();
        recordRing.setVisibility(View.VISIBLE);
        pulse = ObjectAnimator.ofPropertyValuesHolder(recordRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.0f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.0f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1.0f, 0.35f));
        pulse.setDuration(900);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (recordRing != null) {
            recordRing.setScaleX(1f);
            recordRing.setScaleY(1f);
            recordRing.setAlpha(1f);
            recordRing.setVisibility(View.INVISIBLE);
        }
    }

    /** حالت آماده: دکمه‌ی آبی میکروفن. (روی رشته‌ی اصلی صدا زده شود) */
    private void showIdleUi() {
        stopPulse();
        spinner.setVisibility(View.GONE);
        recordCircle.setVisibility(View.VISIBLE);
        recordCircle.setBackgroundResource(R.drawable.bg_rec_blue);
        recordIcon.setVisibility(View.VISIBLE);
        recordIcon.setImageResource(R.drawable.ic_mic);
        recordWrap.setEnabled(true);
        setManualEnabled(true);
        timerText.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        recordLabel.setText(gotResult ? "ضبط دوباره" : "ضبط صدا");
        if (gotResult) {
            tipCard.setVisibility(View.GONE);
        } else {
            tipCard.setVisibility(View.VISIBLE);
            tipTitle.setText(TIP_TITLE_DEFAULT);
            tipBody.setText(TIP_BODY_DEFAULT);
        }
    }

    /** حالت ضبط: دکمه‌ی قرمز توقف با حلقه‌ی ضرباندار و زمان‌سنج. */
    private void showRecordingUi() {
        gotResult = false;
        resultCard.setVisibility(View.GONE);
        tipCard.setVisibility(View.VISIBLE);
        tipTitle.setText("در حال ضبط");
        tipBody.setText("شماره‌ی واحد را شمرده و واضح بگویید؛ بعد دوباره دکمه را بزنید.");
        spinner.setVisibility(View.GONE);
        recordCircle.setVisibility(View.VISIBLE);
        recordCircle.setBackgroundResource(R.drawable.bg_rec_red);
        recordIcon.setVisibility(View.VISIBLE);
        recordIcon.setImageResource(R.drawable.ic_stop);
        recordWrap.setEnabled(true);
        setManualEnabled(false);
        recordLabel.setText("توقف و ارسال");
        timerText.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        updateTimer(0, 0);
        startPulse();
    }

    /** حالت پردازش: چرخنده‌ی آبی و پیام صبر. */
    private void showProcessingUi() {
        stopPulse();
        recordCircle.setVisibility(View.INVISIBLE);
        recordIcon.setVisibility(View.INVISIBLE);
        spinner.setVisibility(View.VISIBLE);
        recordWrap.setEnabled(false);
        setManualEnabled(false);
        recordLabel.setText("در حال تشخیص…");
        timerText.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        tipCard.setVisibility(View.VISIBLE);
        tipTitle.setText("در حال تشخیص");
        tipBody.setText("روی سرور رایگان ممکن است ۲ تا ۳ دقیقه طول بکشد؛ لطفاً برنامه را نبندید.");
    }

    // ------------------------------------------------------------------ ورود دستی شماره

    /** ثبت شماره‌ای که کاربر خودش تایپ کرده (بدون نیاز به صدا و سرور). */
    private void onManualSubmit() {
        if (!manualButton.isEnabled()) {
            return;   // وسط ضبط یا پردازش هستیم
        }
        String digits = latinDigits(manualInput.getText().toString().trim());
        if (digits.isEmpty()) {
            setWarn("شماره‌ی واحد را در باکس بنویسید.");
            return;
        }
        int unit = -1;
        if (digits.length() <= 3 && digits.matches("[0-9]+")) {
            unit = Integer.parseInt(digits);
        }
        if (unit < 0 || unit > 999) {
            setWarn("شماره‌ی واحد باید عددی بین ۰ تا ۹۹۹ باشد.");
            return;
        }
        hideKeyboard();
        manualInput.setText("");
        renderResultCard(true, "شماره‌ی واحد", fa(String.valueOf(unit)), "ثبت‌شده با تایپ دستی");
        setChip("شماره ثبت شد", CHIP_OK);
        showIdleUi();   // برچسب دکمه‌ی ضبط به «ضبط دوباره» تغییر می‌کند
        scroll.post(() -> scroll.smoothScrollTo(0, 0));   // نتیجه بالای صفحه است
    }

    private void setManualEnabled(boolean enabled) {
        manualInput.setEnabled(enabled);
        manualButton.setEnabled(enabled);
        manualCard.setAlpha(enabled ? 1f : 0.5f);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(manualInput.getWindowToken(), 0);
        }
        manualInput.clearFocus();
    }

    /** اعداد فارسی و عربی ← لاتین (برای خواندن آنچه کاربر تایپ کرده). */
    private static String latinDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u06F0' && c <= '\u06F9') {
                sb.append((char) ('0' + (c - '\u06F0')));
            } else if (c >= '\u0660' && c <= '\u0669') {
                sb.append((char) ('0' + (c - '\u0660')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** کارت نتیجه (آبی = موفق، کهربایی = هشدار). */
    private void renderResultCard(boolean ok, String label, String big, String detail) {
        gotResult = true;
        tipCard.setVisibility(View.GONE);
        resultCard.setVisibility(View.VISIBLE);
        resultCard.setBackgroundResource(ok ? R.drawable.bg_result : R.drawable.bg_result_warn);
        int sub = ok ? 0xFFCFE3EE : 0xFFFFF4DE;
        resultLabel.setTextColor(sub);
        rawText.setTextColor(sub);
        resultLabel.setText(label);
        resultText.setText(big);
        rawText.setText(detail);
    }

    private void updateTimer(int sec, int percent) {
        timerText.setText(fa(two(sec / 60) + ":" + two(sec % 60)) + " از " + fa(two(MAX_SECONDS / 60) + ":" + two(MAX_SECONDS % 60)));
        progressBar.setProgress(percent);
    }

    private static String normalizeUrl(String input) {
        String s = input == null ? "" : input.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("/extract-unit-async")) {
            s = s.substring(0, s.length() - "/extract-unit-async".length());
        } else if (s.endsWith("/extract-unit")) {
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
            setWarn("میکروفن در دسترس نیست.");
            return;
        }

        final AudioRecord recorder;
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, SAMPLE_RATE));
        } catch (SecurityException | IllegalArgumentException e) {
            setWarn("خطا در راه‌اندازی میکروفن.");
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            setWarn("میکروفن راه‌اندازی نشد.");
            return;
        }

        hideKeyboard();
        recording = true;
        resultText.setText("");
        rawText.setText("");
        showRecordingUi();
        setChip("در حال ضبط… شماره‌ی واحد را بگویید.", CHIP_REC);

        new Thread(() -> {
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            final long maxBytes = (long) SAMPLE_RATE * 2 * MAX_SECONDS;
            int lastPct = -1;
            try {
                recorder.startRecording();
                while (recording && pcm.size() < maxBytes) {
                    int n = recorder.read(buf, 0, buf.length);
                    if (n > 0) {
                        pcm.write(buf, 0, n);
                        final int pct = (int) Math.min(100L, pcm.size() * 100L / maxBytes);
                        if (pct != lastPct) {
                            lastPct = pct;
                            final int sec = pcm.size() / (SAMPLE_RATE * 2);
                            runOnUiThread(() -> updateTimer(sec, pct));
                        }
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

            byte[] pcmBytes = pcm.toByteArray();
            if (pcmBytes.length < SAMPLE_RATE) {   // کمتر از نیم‌ثانیه
                runOnUiThread(this::showIdleUi);
                setWarn("صدا خیلی کوتاه بود؛ دوباره تلاش کنید.");
                return;
            }

            runOnUiThread(this::showProcessingUi);
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

    /** پاسخ ساده‌ی HTTP: کد وضعیت + متن بدنه. */
    private static final class Reply {
        final int code;
        final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /** ارسال فایل صوتی به یک آدرس (multipart) و دریافت پاسخ. */
    private static Reply postAudio(String url, byte[] wav, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            String boundary = "----UnitVoice" + System.currentTimeMillis();
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"audio\"; filename=\"voice.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n";
            String tail = "\r\n--" + boundary + "--\r\n";
            byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
            byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(readTimeoutMs);
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
            return new Reply(code, readAll(stream));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** درخواست GET ساده (برای پرسیدن نتیجه). */
    private static Reply httpGet(String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Reply(code, readAll(stream));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void sendToServer(String baseUrl, byte[] wav) {
        final long startMs = System.currentTimeMillis();
        try {
            // ۱) ارسال صدا؛ سرور فوراً شماره‌ی کار (job_id) می‌دهد
            Reply submit = postAudio(baseUrl + "/extract-unit-async", wav, 60000);

            if (submit.code == 404) {
                // سرور قدیمی است و مسیر ناهمزمان ندارد → روش قدیمیِ یک‌مرحله‌ای
                setStatus("در حال پردازش روی سرور…");
                Reply old = postAudio(baseUrl + "/extract-unit", wav, 180000);
                if (old.code != 200) {
                    setWarn("خطای سرور (کد " + fa(String.valueOf(old.code)) + ").");
                    return;
                }
                showResult(old.body, startMs);
                return;
            }
            if (submit.code != 200) {
                setWarn("خطای سرور (کد " + fa(String.valueOf(submit.code)) + ").");
                return;
            }

            String jobId = new JSONObject(submit.body).optString("job_id", "");
            if (jobId.isEmpty()) {
                setWarn("پاسخ سرور نامعتبر بود.");
                return;
            }

            // ۲) هر چند ثانیه بپرس «آماده شد؟»
            int fails = 0;
            while (true) {
                long elapsed = (System.currentTimeMillis() - startMs) / 1000;
                if (elapsed > POLL_MAX_SECONDS) {
                    setWarn("سرور بیش از حد طول کشید؛ دوباره تلاش کنید.");
                    return;
                }
                setStatus("در حال پردازش روی سرور… " + fa(String.valueOf(elapsed)) + " ثانیه");
                Thread.sleep(POLL_INTERVAL_MS);

                try {
                    Reply r = httpGet(baseUrl + "/result/" + jobId);
                    if (r.code == 404) {
                        setWarn("کار روی سرور پیدا نشد (شاید سرور دوباره روشن شده)؛ دوباره ضبط کنید.");
                        return;
                    }
                    if (r.code != 200) {
                        throw new IOException("HTTP " + r.code);
                    }
                    JSONObject json = new JSONObject(r.body);
                    if ("processing".equals(json.optString("status", ""))) {
                        fails = 0;
                        continue;
                    }
                    showResult(r.body, startMs);
                    return;
                } catch (IOException | org.json.JSONException e) {
                    fails++;
                    if (fails >= POLL_MAX_FAILS) {
                        setWarn("ارتباط با سرور قطع شد: " + e.getMessage());
                        return;
                    }
                }
            }
        } catch (Exception e) {
            setWarn("ارتباط با سرور برقرار نشد: " + e.getMessage());
        } finally {
            runOnUiThread(this::showIdleUi);
        }
    }

    /** نمایش نتیجه‌ی نهایی سرور روی صفحه. */
    private void showResult(String body, long startMs) throws org.json.JSONException {
        JSONObject json = new JSONObject(body);
        final String status = json.optString("status", "");
        final String raw = json.optString("raw_text", "");
        final boolean found = "success".equals(status) && !json.isNull("unit");
        final String unitText = found ? String.valueOf(json.getInt("unit")) : "";
        final long seconds = (System.currentTimeMillis() - startMs) / 1000;
        final boolean serverError = "error".equals(status);

        runOnUiThread(() -> {
            String info = raw.isEmpty() ? "" : "متن تشخیص‌داده‌شده: " + raw + "\n";
            String timeLine = "زمان پاسخ: " + fa(String.valueOf(seconds)) + " ثانیه";
            if (found) {
                renderResultCard(true, "شماره‌ی واحد", fa(unitText), info + timeLine);
                setChip("نتیجه آماده است", CHIP_OK);
            } else if (serverError) {
                renderResultCard(false, "خطا در پردازش روی سرور", "؟", "لطفاً دوباره تلاش کنید.\n" + info + timeLine);
                setChip("پاسخ ایمن؛ فقط تکرار لازم است", CHIP_WARN);
            } else {
                renderResultCard(false, "عددی یافت نشد", "؟", "لطفاً دوباره و شمرده‌تر بگویید.\n" + info + timeLine);
                setChip("پاسخ ایمن؛ فقط تکرار لازم است", CHIP_WARN);
            }
        });
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