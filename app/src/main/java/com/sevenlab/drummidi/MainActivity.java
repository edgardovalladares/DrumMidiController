package com.sevenlab.drummidi;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.media.midi.*;
import android.os.*;
import android.view.*;

import java.io.IOException;
import java.util.*;

public class MainActivity extends Activity {
    private static final int NOTE_KICK = 36;
    private static final int NOTE_SNARE = 38;
    private static final int NOTE_FLOOR = 41;
    private static final int NOTE_HIHAT_CLOSED = 42;
    private static final int NOTE_TOM_LOW = 45;
    private static final int NOTE_HIHAT_OPEN = 46;
    private static final int NOTE_TOM_MID = 47;
    private static final int NOTE_CRASH = 49;
    private static final int NOTE_TOM_HIGH = 50;
    private static final int NOTE_RIDE = 51;
    private static final int NOTE_SPLASH = 55;

    private SoundPool soundPool;
    private final Map<Integer, Integer> soundMap = new HashMap<>();
    private MidiSender midiSender;
    private DrumKitView drumKitView;
    private float masterVolume = 0.85f;
    private float velocitySensitivity = 0.85f;
    private int openHiHatStreamId;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build();

        soundMap.put(NOTE_KICK, soundPool.load(this, R.raw.kick, 1));
        soundMap.put(NOTE_SNARE, soundPool.load(this, R.raw.snare, 1));
        soundMap.put(NOTE_TOM_LOW, soundPool.load(this, R.raw.tom_low, 1));
        soundMap.put(NOTE_TOM_MID, soundPool.load(this, R.raw.tom_mid, 1));
        soundMap.put(NOTE_TOM_HIGH, soundPool.load(this, R.raw.tom_high, 1));
        soundMap.put(NOTE_FLOOR, soundPool.load(this, R.raw.floor, 1));
        soundMap.put(NOTE_HIHAT_CLOSED, soundPool.load(this, R.raw.hihat_closed, 1));
        soundMap.put(NOTE_HIHAT_OPEN, soundPool.load(this, R.raw.hihat_open, 1));
        soundMap.put(NOTE_CRASH, soundPool.load(this, R.raw.crash, 1));
        soundMap.put(NOTE_RIDE, soundPool.load(this, R.raw.ride, 1));
        soundMap.put(NOTE_SPLASH, soundPool.load(this, R.raw.splash, 1));

        midiSender = new MidiSender(this, this::invalidateKit);
        midiSender.openAllInputs();
        drumKitView = new DrumKitView(this, new KitListener() {
            @Override public void onHit(Pad pad, float pressure) { triggerPad(pad, pressure); }
            @Override public void onMasterVolumeChanged(float value) { masterVolume = value; }
            @Override public void onVelocitySensitivityChanged(float value) { velocitySensitivity = value; }
            @Override public void onSelectMidiOutput() {
                midiSender.selectNextOutput();
                invalidateKit();
            }
            @Override public float getMasterVolume() { return masterVolume; }
            @Override public float getVelocitySensitivity() { return velocitySensitivity; }
            @Override public String getMidiTargetName() { return midiSender.getSelectedTargetName(); }
            @Override public String getMidiStatus() { return midiSender.getStatusLabel(); }
        });
        setContentView(drumKitView);
    }

    private void triggerPad(Pad pad, float pressure) {
        int velocity = velocityFromPressure(pressure);
        Integer soundId = soundMap.get(pad.note);
        float volume = masterVolume * Math.min(1f, 0.35f + (velocity / 127f) * 0.75f);

        if (pad.note == NOTE_HIHAT_CLOSED) {
            chokeOpenHiHat();
        }

        if (soundId != null && soundId != 0) {
            int streamId = soundPool.play(soundId, volume, volume, 1, 0, 1f);
            if (pad.note == NOTE_HIHAT_OPEN) openHiHatStreamId = streamId;
        }

        if (pad.note == NOTE_HIHAT_CLOSED) {
            midiSender.sendNoteOff(NOTE_HIHAT_OPEN);
        }
        midiSender.sendNote(pad.note, velocity);
    }

    private int velocityFromPressure(float pressure) {
        float normalized = Math.max(0.25f, Math.min(1f, pressure));
        float curved = (float)Math.pow(normalized, 1.25f - velocitySensitivity * 0.75f);
        return Math.max(35, Math.min(127, Math.round(curved * 127f)));
    }

    private void chokeOpenHiHat() {
        if (openHiHatStreamId != 0) {
            soundPool.stop(openHiHatStreamId);
            openHiHatStreamId = 0;
        }
    }

    private void invalidateKit() {
        if (drumKitView != null) drumKitView.invalidate();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (soundPool != null) soundPool.release();
        if (midiSender != null) midiSender.close();
    }

    interface KitListener {
        void onHit(Pad pad, float pressure);
        void onMasterVolumeChanged(float value);
        void onVelocitySensitivityChanged(float value);
        void onSelectMidiOutput();
        float getMasterVolume();
        float getVelocitySensitivity();
        String getMidiTargetName();
        String getMidiStatus();
    }

    static class Pad {
        String label; int note; RectF rect; boolean cymbal; long lastHit;
        Pad(String label, int note, RectF rect, boolean cymbal) { this.label = label; this.note = note; this.rect = rect; this.cymbal = cymbal; }
    }

    static class DrumKitView extends View {
        private static final int CONTROL_NONE = 0;
        private static final int CONTROL_VOLUME = 1;
        private static final int CONTROL_VELOCITY = 2;

        private final ArrayList<Pad> pads = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final KitListener listener;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final RectF midiButton = new RectF();
        private final RectF volumeTrack = new RectF();
        private final RectF velocityTrack = new RectF();
        private final RectF tempRect = new RectF();
        private int activeControl = CONTROL_NONE;

        DrumKitView(Context ctx, KitListener listener) {
            super(ctx);
            this.listener = listener;
            setBackgroundColor(Color.rgb(8, 9, 10));
            setFocusable(true);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { layoutPads(w, h); }

        private void layoutPads(int w, int h) {
            pads.clear();
            float top = Math.max(24f, h * 0.04f);
            float bottom = h - Math.max(44f, h * 0.07f);
            float kitH = bottom - top;
            float u = Math.min(w, kitH) / 10f;

            pads.add(new Pad("CRASH", NOTE_CRASH, oval(w*.10f, top + kitH*.04f, u*1.46f), true));
            pads.add(new Pad("SPLASH", NOTE_SPLASH, oval(w*.36f, top + kitH*.05f, u*.88f), true));
            pads.add(new Pad("CRASH", NOTE_CRASH, oval(w*.60f, top + kitH*.04f, u*1.08f), true));
            pads.add(new Pad("RIDE", NOTE_RIDE, oval(w*.84f, top + kitH*.06f, u*1.44f), true));
            pads.add(new Pad("CLOSED HH", NOTE_HIHAT_CLOSED, oval(w*.88f, top + kitH*.42f, u*.92f), true));
            pads.add(new Pad("OPEN HH", NOTE_HIHAT_OPEN, oval(w*.94f, top + kitH*.67f, u*.90f), true));
            pads.add(new Pad("FLOOR", NOTE_FLOOR, oval(w*.08f, top + kitH*.71f, u*1.22f), false));
            pads.add(new Pad("TOM", NOTE_TOM_MID, oval(w*.31f, top + kitH*.43f, u*.92f), false));
            pads.add(new Pad("TOM", NOTE_TOM_HIGH, oval(w*.49f, top + kitH*.31f, u*.96f), false));
            pads.add(new Pad("TOM", NOTE_TOM_LOW, oval(w*.68f, top + kitH*.43f, u*.94f), false));
            pads.add(new Pad("KICK", NOTE_KICK, oval(w*.31f, top + kitH*.82f, u*1.72f), false));
            pads.add(new Pad("KICK", NOTE_KICK, oval(w*.63f, top + kitH*.82f, u*1.72f), false));
            pads.add(new Pad("SNARE", NOTE_SNARE, oval(w*.50f, top + kitH*.62f, u*1.16f), false));

            midiButton.set(w - Math.min(300f, w*.30f), 14f, w - 16f, 54f);
            volumeTrack.set(78f, h - 34f, Math.min(w * 0.36f, 360f), h - 24f);
            velocityTrack.set(w * 0.59f, h - 34f, w - 82f, h - 24f);
        }
        private RectF oval(float cx, float cy, float r) { return new RectF(cx-r, cy-r*.68f, cx+r, cy+r*.68f); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            drawBg(c);
            drawHardware(c);
            drawTopBar(c);
            for (Pad pad : pads) drawPad(c, pad);
            drawControls(c);
        }
        private void drawBg(Canvas c) {
            LinearGradient bg = new LinearGradient(0, 0, getWidth(), getHeight(),
                    Color.rgb(6, 7, 8), Color.rgb(22, 20, 18), Shader.TileMode.CLAMP);
            p.setStyle(Paint.Style.FILL);
            p.setShader(bg);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
            p.setShader(null);

            p.setStrokeWidth(3);
            for (int i=-getHeight(); i<getWidth(); i+=42) {
                p.setColor(Color.argb(120, 0, 0, 0));
                c.drawLine(i, getHeight(), i + getHeight(), 0, p);
                p.setColor(Color.argb(42, 255, 255, 255));
                c.drawLine(i + 4, getHeight(), i + getHeight() + 4, 0, p);
            }
            p.setStyle(Paint.Style.FILL);
            RadialGradient vignette = new RadialGradient(getWidth()/2f, getHeight()/2f, getWidth()*.74f,
                    Color.argb(0, 0, 0, 0), Color.argb(175, 0, 0, 0), Shader.TileMode.CLAMP);
            p.setShader(vignette);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
            p.setShader(null);
        }
        private void drawHardware(Canvas c) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(5);
            p.setColor(Color.argb(145, 180, 178, 168));
            drawStand(c, getWidth()*.36f, getHeight()*.12f, getWidth()*.45f, getHeight()*.54f);
            drawStand(c, getWidth()*.60f, getHeight()*.12f, getWidth()*.55f, getHeight()*.56f);
            drawStand(c, getWidth()*.84f, getHeight()*.16f, getWidth()*.76f, getHeight()*.63f);
            drawStand(c, getWidth()*.88f, getHeight()*.44f, getWidth()*.82f, getHeight()*.70f);
            p.setStrokeCap(Paint.Cap.BUTT);
        }
        private void drawStand(Canvas c, float x1, float y1, float x2, float y2) {
            c.drawLine(x1, y1, x2, y2, p);
            c.drawLine(x2, y2, x2 - 34, y2 + 44, p);
            c.drawLine(x2, y2, x2 + 38, y2 + 42, p);
        }
        private void drawTopBar(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(92, 8, 10, 12));
            tempRect.set(14, 12, Math.min(322, getWidth()*.42f), 54);
            c.drawRoundRect(tempRect, 10, 10, p);

            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            p.setTextSize(18);
            p.setColor(Color.rgb(218,238,238));
            c.drawText("DRUM MIDI", 28, 39, p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(185, 9, 102, 113));
            c.drawRoundRect(midiButton, 8, 8, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(14);
            p.setColor(Color.WHITE);
            c.drawText("MIDI: " + fitText(listener.getMidiTargetName(), 24), midiButton.centerX(), 34, p);
            p.setTextSize(12);
            p.setColor(Color.rgb(205, 235, 235));
            c.drawText(listener.getMidiStatus(), midiButton.centerX(), 50, p);
        }
        private String fitText(String text, int max) {
            if (text.length() <= max) return text;
            return text.substring(0, Math.max(0, max - 3)) + "...";
        }
        private void drawPad(Canvas c, Pad pad) {
            long age = SystemClock.uptimeMillis() - pad.lastHit;
            boolean active = age < 90;
            if (pad.cymbal) drawCymbal(c, pad, active);
            else drawDrum(c, pad, active);
        }
        private void drawCymbal(Canvas c, Pad pad, boolean active) {
            RectF r = pad.rect;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(118, 0, 0, 0));
            tempRect.set(r);
            tempRect.offset(0, r.height() * .12f);
            c.drawOval(tempRect, p);

            RadialGradient radial = new RadialGradient(r.centerX(), r.centerY(), r.width()/2f,
                    new int[]{
                            active ? Color.rgb(255, 250, 215) : Color.rgb(255, 218, 164),
                            Color.rgb(223, 132, 70),
                            Color.rgb(116, 62, 34)
                    },
                    new float[]{0f, .58f, 1f},
                    Shader.TileMode.CLAMP);
            p.setShader(radial);
            c.drawOval(r, p);
            p.setShader(null);

            SweepGradient sweep = new SweepGradient(r.centerX(), r.centerY(),
                    new int[]{
                            Color.argb(70,255,255,255), Color.argb(0,255,255,255),
                            Color.argb(105,70,35,16), Color.argb(0,255,255,255),
                            Color.argb(90,255,244,206), Color.argb(0,255,255,255)
                    },
                    null);
            p.setShader(sweep);
            p.setAlpha(170);
            c.drawOval(r, p);
            p.setAlpha(255);
            p.setShader(null);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(5f, r.width()*.04f));
            p.setColor(accentColor(pad, active));
            c.drawArc(r, 210, 92, false, p);
            p.setStrokeWidth(2);
            p.setColor(Color.argb(70, 70, 35, 12));
            for (float scale = .32f; scale <= .82f; scale += .17f) {
                tempRect.set(r.centerX() - r.width()*scale/2f, r.centerY() - r.height()*scale/2f,
                        r.centerX() + r.width()*scale/2f, r.centerY() + r.height()*scale/2f);
                c.drawOval(tempRect, p);
            }

            drawHub(c, r.centerX(), r.centerY(), r.height()*.09f);
            drawLabel(c, pad.label, r.centerX(), r.bottom - r.height()*.22f, r.width()*.11f, Color.rgb(42, 37, 28), 16);
        }
        private void drawDrum(Canvas c, Pad pad, boolean active) {
            RectF r = pad.rect;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(142, 0, 0, 0));
            tempRect.set(r);
            tempRect.offset(0, r.height()*.13f);
            c.drawOval(tempRect, p);

            boolean kick = pad.label.equals("KICK");
            boolean snare = pad.note == NOTE_SNARE;
            float rim = Math.max(10f, r.width() * (kick ? .035f : .055f));

            LinearGradient shell = new LinearGradient(r.left, r.top, r.right, r.bottom,
                    kick ? Color.rgb(72, 61, 44) : Color.rgb(12, 120, 132),
                    kick ? Color.rgb(36, 32, 25) : Color.rgb(6, 72, 82),
                    Shader.TileMode.CLAMP);
            p.setShader(shell);
            c.drawOval(r, p);
            p.setShader(null);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(rim);
            p.setColor(Color.rgb(84, 86, 82));
            c.drawOval(r, p);
            p.setStrokeWidth(Math.max(3f, rim*.28f));
            p.setColor(Color.rgb(218, 214, 200));
            c.drawOval(r, p);

            tempRect.set(r);
            tempRect.inset(r.width()*(kick ? .19f : .18f), r.height()*(kick ? .22f : .18f));
            RadialGradient head = new RadialGradient(tempRect.centerX(), tempRect.centerY(), tempRect.width()*.58f,
                    snare ? Color.rgb(218, 221, 212) : Color.rgb(88, 80, 65),
                    snare ? Color.rgb(76, 70, 60) : Color.rgb(54, 48, 38),
                    Shader.TileMode.CLAMP);
            p.setStyle(Paint.Style.FILL);
            p.setShader(head);
            c.drawOval(tempRect, p);
            p.setShader(null);

            if (kick) {
                tempRect.inset(tempRect.width()*.25f, tempRect.height()*.18f);
                p.setColor(Color.rgb(225, 228, 226));
                c.drawOval(tempRect, p);
                drawLabel(c, "KICK", tempRect.centerX(), tempRect.centerY()+tempRect.height()*.12f, r.width()*.20f, Color.rgb(10, 126, 137), 26);
            } else {
                drawLabel(c, pad.label, r.centerX(), r.bottom - r.height()*.16f, r.width()*.12f,
                        snare ? Color.rgb(10, 126, 137) : Color.rgb(226, 244, 244), 18);
            }

            drawLugs(c, r, kick ? 10 : 8);
            if (active) drawActiveGlow(c, r);
        }
        private int accentColor(Pad pad, boolean active) {
            if (active) return Color.rgb(255, 236, 105);
            if (pad.note == NOTE_RIDE) return Color.rgb(236, 168, 24);
            if (pad.note == NOTE_SPLASH) return Color.rgb(142, 151, 38);
            return Color.rgb(207, 78, 62);
        }
        private void drawHub(Canvas c, float cx, float cy, float radius) {
            RadialGradient hub = new RadialGradient(cx, cy, radius,
                    Color.rgb(230, 230, 218), Color.rgb(37, 42, 43), Shader.TileMode.CLAMP);
            p.setStyle(Paint.Style.FILL);
            p.setShader(hub);
            c.drawCircle(cx, cy, radius, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(Color.argb(160, 0, 0, 0));
            c.drawCircle(cx, cy, radius*.66f, p);
        }
        private void drawLugs(Canvas c, RectF r, int count) {
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < count; i++) {
                double angle = Math.PI * 2 * i / count;
                float x = r.centerX() + (float)Math.cos(angle) * r.width()*.49f;
                float y = r.centerY() + (float)Math.sin(angle) * r.height()*.49f;
                p.setColor(Color.rgb(220, 216, 205));
                c.drawCircle(x, y, Math.max(4f, r.width()*.018f), p);
                p.setColor(Color.rgb(68, 70, 68));
                c.drawCircle(x, y, Math.max(2f, r.width()*.009f), p);
            }
        }
        private void drawActiveGlow(Canvas c, RectF r) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(8f, r.width()*.035f));
            p.setColor(Color.argb(190, 0, 214, 232));
            c.drawOval(r, p);
        }
        private void drawLabel(Canvas c, String label, float x, float y, float size, int color, float minSize) {
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            p.setTextSize(Math.max(minSize, size));
            p.setColor(color);
            c.drawText(label, x, y, p);
        }
        private void drawControls(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(108, 8, 10, 12));
            tempRect.set(14, getHeight() - 52, getWidth() - 14, getHeight() - 10);
            c.drawRoundRect(tempRect, 10, 10, p);
            drawSlider(c, "VOL", listener.getMasterVolume(), volumeTrack);
            drawSlider(c, "VEL", listener.getVelocitySensitivity(), velocityTrack);
        }
        private void drawSlider(Canvas c, String label, float value, RectF track) {
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.RIGHT);
            p.setTextSize(15);
            p.setColor(Color.rgb(230,245,245));
            c.drawText(label, track.left - 12, track.centerY() + 5, p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(42, 45, 45));
            c.drawRoundRect(track, 8, 8, p);
            RectF fill = new RectF(track.left, track.top, track.left + track.width() * value, track.bottom);
            p.setColor(Color.rgb(16, 149, 160));
            c.drawRoundRect(fill, 8, 8, p);
            p.setColor(Color.rgb(235, 238, 232));
            c.drawCircle(fill.right, track.centerY(), 10, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(12);
            p.setColor(Color.rgb(205, 235, 235));
            c.drawText(Math.round(value * 100) + "%", track.right + 12, track.centerY() + 4, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) return hit(e, e.getActionIndex());
            if (action == MotionEvent.ACTION_MOVE && activeControl != CONTROL_NONE) {
                updateControl(e.getX(0));
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_UP) activeControl = CONTROL_NONE;
            return true;
        }
        private boolean hit(MotionEvent e, int index) {
            float x=e.getX(index), y=e.getY(index);
            if (midiButton.contains(x, y)) {
                listener.onSelectMidiOutput();
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
                return true;
            }
            if (isNearTrack(volumeTrack, x, y)) {
                activeControl = CONTROL_VOLUME;
                updateControl(x);
                return true;
            }
            if (isNearTrack(velocityTrack, x, y)) {
                activeControl = CONTROL_VELOCITY;
                updateControl(x);
                return true;
            }

            float pressure=Math.max(.25f, e.getPressure(index));
            for (int i=pads.size()-1; i>=0; i--) {
                Pad pad=pads.get(i);
                if (isInsidePad(pad, x, y)) { pad.lastHit = SystemClock.uptimeMillis(); listener.onHit(pad, pressure); invalidate(); handler.postDelayed(this::invalidate, 110); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); break; }
            }
            return true;
        }
        private boolean isNearTrack(RectF track, float x, float y) {
            return x >= track.left - 22 && x <= track.right + 22 && y >= track.top - 24 && y <= track.bottom + 24;
        }
        private void updateControl(float x) {
            RectF track = activeControl == CONTROL_VOLUME ? volumeTrack : velocityTrack;
            float value = Math.max(0f, Math.min(1f, (x - track.left) / track.width()));
            if (activeControl == CONTROL_VOLUME) listener.onMasterVolumeChanged(value);
            if (activeControl == CONTROL_VELOCITY) listener.onVelocitySensitivityChanged(value);
            invalidate();
        }
        private boolean isInsidePad(Pad pad, float x, float y) {
            RectF r = pad.rect;
            float dx = (x - r.centerX()) / (r.width() / 2f);
            float dy = (y - r.centerY()) / (r.height() / 2f);
            return dx * dx + dy * dy <= 1f;
        }
    }

    static class MidiSender {
        private static final int ALL_OUTPUTS = -1;

        private final Context context;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable onChanged;
        private final ArrayList<MidiConnection> connections = new ArrayList<>();
        private MidiManager.DeviceCallback callback;
        private int selectedDeviceId = ALL_OUTPUTS;

        MidiSender(Context context, Runnable onChanged) {
            this.context = context;
            this.onChanged = onChanged;
        }
        void openAllInputs() {
            if (Build.VERSION.SDK_INT < 23) return;
            MidiManager mm = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
            if (mm == null) return;
            for (MidiDeviceInfo info : mm.getDevices()) openInputs(mm, info);
            callback = new MidiManager.DeviceCallback() {
                @Override public void onDeviceAdded(MidiDeviceInfo info) { openInputs(mm, info); }
                @Override public void onDeviceRemoved(MidiDeviceInfo info) { closeDevice(info.getId()); }
            };
            mm.registerDeviceCallback(callback, handler);
        }
        private void openInputs(MidiManager mm, MidiDeviceInfo info) {
            if (findConnection(info.getId()) != null) return;
            ArrayList<Integer> inputPorts = new ArrayList<>();
            for (MidiDeviceInfo.PortInfo pi : info.getPorts()) if (pi.getType() == MidiDeviceInfo.PortInfo.TYPE_INPUT) inputPorts.add(pi.getPortNumber());
            if (inputPorts.isEmpty()) return;
            mm.openDevice(info, device -> {
                if (device == null) return;
                MidiConnection connection = new MidiConnection(info.getId(), deviceName(info), device);
                for (Integer portNumber : inputPorts) {
                    MidiInputPort port = device.openInputPort(portNumber);
                    if (port != null) connection.ports.add(port);
                }
                if (connection.ports.isEmpty()) {
                    try { device.close(); } catch(IOException ignored) {}
                    return;
                }
                connections.add(connection);
                onChanged.run();
            }, handler);
        }
        private MidiConnection findConnection(int deviceId) {
            for (MidiConnection connection : connections) if (connection.deviceId == deviceId) return connection;
            return null;
        }
        private String deviceName(MidiDeviceInfo info) {
            Bundle props = info.getProperties();
            String name = props.getString(MidiDeviceInfo.PROPERTY_NAME);
            if (name == null) name = props.getString(MidiDeviceInfo.PROPERTY_PRODUCT);
            if (name == null) name = props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER);
            return name != null ? name : "Device " + info.getId();
        }
        void selectNextOutput() {
            if (connections.isEmpty()) {
                selectedDeviceId = ALL_OUTPUTS;
                return;
            }
            if (selectedDeviceId == ALL_OUTPUTS) {
                selectedDeviceId = connections.get(0).deviceId;
                return;
            }
            for (int i = 0; i < connections.size(); i++) {
                if (connections.get(i).deviceId == selectedDeviceId) {
                    selectedDeviceId = i == connections.size() - 1 ? ALL_OUTPUTS : connections.get(i + 1).deviceId;
                    return;
                }
            }
            selectedDeviceId = ALL_OUTPUTS;
        }
        String getSelectedTargetName() {
            if (connections.isEmpty()) return "No output";
            if (selectedDeviceId == ALL_OUTPUTS) return "All outputs";
            MidiConnection connection = findConnection(selectedDeviceId);
            return connection != null ? connection.name : "All outputs";
        }
        String getStatusLabel() {
            int portCount = 0;
            for (MidiConnection connection : connections) portCount += connection.ports.size();
            return portCount == 1 ? "1 input port" : portCount + " input ports";
        }
        void sendNote(int note, int velocity) {
            sendRaw(new byte[]{(byte)0x99, (byte)note, (byte)velocity});
            handler.postDelayed(() -> sendNoteOff(note), 90);
        }
        void sendNoteOff(int note) {
            sendRaw(new byte[]{(byte)0x89, (byte)note, 0});
        }
        private void sendRaw(byte[] data) {
            long now = System.nanoTime();
            for (MidiConnection connection : connections) {
                if (selectedDeviceId != ALL_OUTPUTS && connection.deviceId != selectedDeviceId) continue;
                for (MidiInputPort port: connection.ports) try { port.send(data,0,data.length,now); } catch(IOException ignored) {}
            }
        }
        private void closeDevice(int deviceId) {
            for (int i = connections.size() - 1; i >= 0; i--) {
                MidiConnection connection = connections.get(i);
                if (connection.deviceId == deviceId) {
                    connection.close();
                    connections.remove(i);
                }
            }
            if (selectedDeviceId == deviceId) selectedDeviceId = ALL_OUTPUTS;
            onChanged.run();
        }
        void close() {
            if (Build.VERSION.SDK_INT >= 23 && callback != null) {
                MidiManager mm = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
                if (mm != null) mm.unregisterDeviceCallback(callback);
            }
            for (MidiConnection connection: connections) connection.close();
            connections.clear();
        }
    }

    static class MidiConnection {
        final int deviceId;
        final String name;
        final MidiDevice device;
        final ArrayList<MidiInputPort> ports = new ArrayList<>();

        MidiConnection(int deviceId, String name, MidiDevice device) {
            this.deviceId = deviceId;
            this.name = name;
            this.device = device;
        }
        void close() {
            for (MidiInputPort port: ports) try { port.close(); } catch(IOException ignored) {}
            try { device.close(); } catch(IOException ignored) {}
        }
    }
}
