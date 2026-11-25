package com.asistan.cip;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WakeWordService extends Service {
    static final String ACTION_START = "com.asistan.cip.action.START_WAKE";
    static final String ACTION_STOP = "com.asistan.cip.action.STOP_WAKE";
    static final String ACTION_PAUSE = "com.asistan.cip.action.PAUSE_WAKE";
    static final String ACTION_RESUME = "com.asistan.cip.action.RESUME_WAKE";
    static final String ACTION_STATUS = "com.asistan.cip.action.WAKE_STATUS";
    static final String ACTION_COMMAND = "com.asistan.cip.action.WAKE_COMMAND";
    static final String ACTION_LOCAL_COMMAND = "com.asistan.cip.action.WAKE_LOCAL_COMMAND";
    static final String ACTION_RESULT = "com.asistan.cip.action.WAKE_RESULT";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_COMMAND = "command";
    static final String EXTRA_ANSWER = "answer";
    static final String PENDING_COMMAND = "pending_wake_command";
    static final String PENDING_NATIVE_COMMAND = "pending_native_wake_command";

    private static final String CHANNEL_ID = "cip_wake_word";
    private static final int NOTIFICATION_ID = 7301;
    private static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService assistantExecutor = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> speechQueue = new ArrayDeque<>();
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private boolean armed;
    private boolean paused;
    private boolean assistantBusy;
    private boolean textToSpeechReady;
    private String notificationStatus = "Hey Çip için cihaz üstünde dinleme açık.";

    static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(notificationStatus));
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && textToSpeech != null) {
                int languageStatus = textToSpeech.setLanguage(new Locale("tr", "TR"));
                textToSpeechReady = languageStatus != TextToSpeech.LANG_MISSING_DATA
                        && languageStatus != TextToSpeech.LANG_NOT_SUPPORTED;
            }
            if (textToSpeech != null) {
                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }

                    @Override
                    public void onDone(String utteranceId) {
                        main.post(WakeWordService.this::speakNextChunk);
                    }

                    @Override
                    public void onError(String utteranceId) {
                        main.post(WakeWordService.this::speakNextChunk);
                    }
                });
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopListening();
            return START_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            paused = true;
            cancelRecognition();
            publishStatus("Dinleme geçici olarak duraklatıldı.");
            return START_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            paused = false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            stopListening("Bu cihazda Android cihaz-üstü konuşma tanıma kullanılamıyor. "
                    + "Android 12+ ve çevrimdışı Türkçe dil desteği gerekir.");
            return START_NOT_STICKY;
        }
        String pending = intent == null
                ? getSharedPreferences("cip_settings", MODE_PRIVATE).getString(PENDING_COMMAND, "")
                : "";
        if (!pending.isEmpty()) {
            runAssistantCommand(pending);
        } else if (!paused) {
            startRecognition();
        }
        return START_STICKY;
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void startRecognition() {
        if (paused || !running) {
            return;
        }
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                speechRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(android.os.Bundle params) { }
                    @Override public void onBeginningOfSpeech() { }
                    @Override public void onRmsChanged(float rmsdB) { }
                    @Override public void onBufferReceived(byte[] buffer) { }
                    @Override public void onEndOfSpeech() { }
                    @Override public void onPartialResults(android.os.Bundle partialResults) { }
                    @Override public void onEvent(int eventType, android.os.Bundle params) { }

                    @Override
                    public void onError(int error) {
                        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                            stopListening("Cihazın çevrimdışı konuşma tanıması Türkçe'yi desteklemiyor. "
                                    + "Android dil ayarlarını kontrol et.");
                        } else if (!paused) {
                            publishStatus("Dinleme kesildi; yeniden deneniyor.");
                            main.postDelayed(WakeWordService.this::startRecognition, 800);
                        }
                    }

                    @Override
                    public void onResults(android.os.Bundle results) {
                        ArrayList<String> alternatives =
                                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        processTranscript(alternatives == null || alternatives.isEmpty()
                                ? "" : alternatives.get(0));
                    }
                });
            }
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, new Locale("tr", "TR").toLanguageTag());
            request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            speechRecognizer.startListening(request);
            publishStatus(armed ? "Komutunu bekliyorum..." : "Hey Çip için cihaz üstünde dinliyorum.");
        } catch (RuntimeException error) {
            publishStatus("Cihaz-üstü dinleme başlatılamadı: " + error.getMessage());
            main.postDelayed(this::startRecognition, 1500);
        }
    }

    private void processTranscript(String transcript) {
        if (paused) {
            return;
        }
        String command = "";
        if (armed) {
            if (WakeCommandParser.containsWakePhrase(transcript)) {
                command = WakeCommandParser.commandAfterWakePhrase(transcript);
            } else {
                command = transcript.trim();
            }
            if (!command.isEmpty()) {
                armed = false;
            } else {
                publishStatus("Hey Çip algılandı. Komutunu söyle.");
            }
        } else if (WakeCommandParser.containsWakePhrase(transcript)) {
            command = WakeCommandParser.commandAfterWakePhrase(transcript);
            if (command.isEmpty()) {
                armed = true;
                publishStatus("Hey Çip algılandı. Komutunu söyle.");
            }
        }
        if (!command.isEmpty()) {
            paused = true;
            cancelRecognition();
            if (DeviceCommandRouter.route(command) != null) {
                getSharedPreferences("cip_settings", MODE_PRIVATE).edit()
                        .putString(PENDING_NATIVE_COMMAND, command).apply();
                publishStatus("Bu işlem için dokunup Çip'i aç.");
                Intent localCommand = new Intent(ACTION_LOCAL_COMMAND)
                        .setPackage(getPackageName())
                        .putExtra(EXTRA_COMMAND, command);
                sendBroadcast(localCommand);
                return;
            }
            getSharedPreferences("cip_settings", MODE_PRIVATE).edit()
                    .putString(PENDING_COMMAND, command).apply();
            publishStatus("Çip düşünüyor…");
            Intent broadcast = new Intent(ACTION_COMMAND)
                    .setPackage(getPackageName())
                    .putExtra(EXTRA_COMMAND, command);
            sendBroadcast(broadcast);
            runAssistantCommand(command);
            return;
        }
        main.postDelayed(this::startRecognition, 250);
    }

    private void runAssistantCommand(String command) {
        if (assistantBusy || command == null || command.trim().isEmpty()) {
            return;
        }
        assistantBusy = true;
        paused = true;
        publishStatus("Çip düşünüyor…");
        String history = ConversationHistory.shared().toJson();
        String session = getSharedPreferences("cip_settings", MODE_PRIVATE)
                .getString("session_id", "");
        assistantExecutor.execute(() -> {
            String answer;
            boolean succeeded = false;
            try {
                answer = AssistantApiClient.sendCommand(this, command, session, history);
                succeeded = true;
            } catch (Exception error) {
                answer = "İstek tamamlanamadı. Bağlantını ve sunucu ayarlarını kontrol edip tekrar dene.";
            }
            String result = answer;
            boolean completed = succeeded;
            getSharedPreferences("cip_settings", MODE_PRIVATE).edit()
                    .remove(PENDING_COMMAND).apply();
            if (completed) {
                ConversationHistory.shared().addTurn(command, result);
            }
            main.post(() -> {
                assistantBusy = false;
                Intent broadcast = new Intent(ACTION_RESULT)
                        .setPackage(getPackageName())
                        .putExtra(EXTRA_COMMAND, command)
                        .putExtra(EXTRA_ANSWER, result);
                sendBroadcast(broadcast);
                if (textToSpeechReady && textToSpeech != null && !result.isEmpty()) {
                    notificationStatus = "Çip yanıtlıyor…";
                    NotificationManager manager = getSystemService(NotificationManager.class);
                    if (manager != null) {
                        manager.notify(NOTIFICATION_ID, buildNotification(notificationStatus));
                    }
                    speakAssistantReply(result);
                } else {
                    resumeAfterAssistantReply();
                }
            });
        });
    }

    private void resumeAfterAssistantReply() {
        if (!running) {
            return;
        }
        speechQueue.clear();
        paused = false;
        armed = false;
        publishStatus("Yanıt hazır. Hey Çip için cihaz üstünde dinliyorum.");
        main.postDelayed(this::startRecognition, 700);
    }

    private void speakAssistantReply(String text) {
        speechQueue.clear();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + 3500, text.length());
            if (end < text.length()) {
                int boundary = text.lastIndexOf(' ', end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                speechQueue.addLast(chunk);
            }
            start = end;
        }
        speakNextChunk();
    }

    private void speakNextChunk() {
        if (!running) {
            speechQueue.clear();
            return;
        }
        String chunk = speechQueue.pollFirst();
        if (chunk == null) {
            resumeAfterAssistantReply();
            return;
        }
        int result = textToSpeech.speak(chunk, TextToSpeech.QUEUE_FLUSH, null,
                "cip-wake-" + UUID.randomUUID());
        if (result == TextToSpeech.ERROR) {
            main.post(this::speakNextChunk);
        }
    }

    private void cancelRecognition() {
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
        }
    }

    private void stopListening() {
        stopListening("Sürekli dinleme kapatıldı.");
    }

    private void stopListening(String status) {
        paused = true;
        running = false;
        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, status));
        main.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void publishStatus(String status) {
        notificationStatus = status;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && running) {
            manager.notify(NOTIFICATION_ID, buildNotification(status));
        }
        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, status));
    }

    private Notification buildNotification(String status) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopService = new Intent(this, WakeWordService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopService,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_cip_notification)
                .setContentTitle("Çip uyandırma sözcüğü")
                .setContentText(status)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "Durdur", stopPendingIntent).build())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Çip sürekli dinleme", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Hey Çip uyandırma sözcüğünü cihaz üstünde dinler.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        assistantExecutor.shutdownNow();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
