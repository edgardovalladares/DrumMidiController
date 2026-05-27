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
            float top = Math.max(60f, h * 0.10f);
            float bottom = h - Math.max(88f, h * 0.14f);
            float kitH = bottom - top;
            float u = Math.min(w, kitH) / 10f;

            pads.add(new Pad("CRASH", NOTE_CRASH, oval(w*.08f, top + kitH*.04f, u*1.22f), true));
            pads.add(new Pad("SPLASH", NOTE_SPLASH, oval(w*.36f, top + kitH*.06f, u*.78f), true));
            pads.add(new Pad("CRASH", NOTE_CRASH, oval(w*.60f, top + kitH*.05f, u*1.02f), true));
            pads.add(new Pad("RIDE", NOTE_RIDE, oval(w*.84f, top + kitH*.06f, u*1.28f), true));
            pads.add(new Pad("CLOSED HH", NOTE_HIHAT_CLOSED, oval(w*.88f, top + kitH*.42f, u*.88f), true));
            pads.add(new Pad("OPEN HH", NOTE_HIHAT_OPEN, oval(w*.92f, top + kitH*.69f, u*.88f), true));
            pads.add(new Pad("FLOOR", NOTE_FLOOR, oval(w*.07f, top + kitH*.70f, u*1.12f), false));
            pads.add(new Pad("TOM", NOTE_TOM_MID, oval(w*.31f, top + kitH*.41f, u*.88f), false));
            pads.add(new Pad("TOM", NOTE_TOM_HIGH, oval(w*.49f, top + kitH*.31f, u*.88f), false));
            pads.add(new Pad("TOM", NOTE_TOM_LOW, oval(w*.68f, top + kitH*.41f, u*.88f), false));
            pads.add(new Pad("KICK", NOTE_KICK, oval(w*.30f, top + kitH*.79f, u*1.58f), false));
            pads.add(new Pad("KICK", NOTE_KICK, oval(w*.62f, top + kitH*.79f, u*1.58f), false));
            pads.add(new Pad("SNARE", NOTE_SNARE, oval(w*.50f, top + kitH*.60f, u*1.12f), false));

            midiButton.set(w - 320f, 16f, w - 18f, 56f);
            volumeTrack.set(94f, h - 52f, Math.min(w * 0.44f, 420f), h - 38f);
            velocityTrack.set(w * 0.56f, h - 52f, w - 96f, h - 38f);
        }
        private RectF oval(float cx, float cy, float r) { return new RectF(cx-r, cy-r*.68f, cx+r, cy+r*.68f); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            drawBg(c);
            drawTopBar(c);
            for (Pad pad : pads) drawPad(c, pad);
            drawControls(c);
        }
        private void drawBg(Canvas c) {
            p.setStrokeWidth(2); p.setColor(Color.rgb(20,22,24));
            for (int i=-getHeight(); i<getWidth(); i+=36) c.drawLine(i, getHeight(), i+getHeight(), 0, p);
        }
        private void drawTopBar(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(185, 10, 12, 14));
            c.drawRect(0, 0, getWidth(), 68, p);

            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            p.setTextSize(24);
            p.setColor(Color.rgb(230,245,245));
            c.drawText("DRUM MIDI CONTROLLER", 24, 42, p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(14, 124, 134));
            c.drawRoundRect(midiButton, 8, 8, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(15);
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
            RectF r = pad.rect;
            p.setStyle(Paint.Style.FILL);
            if (pad.cymbal) {
                RadialGradient g = new RadialGradient(r.centerX(), r.centerY(), r.width()/2,
                        active ? Color.rgb(255,245,205) : Color.rgb(240,170,92),
                        Color.rgb(158,83,40), Shader.TileMode.CLAMP);
                p.setShader(g); c.drawOval(r, p); p.setShader(null);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(12); p.setColor(active ? Color.rgb(255,220,80) : Color.rgb(210,105,70)); c.drawOval(r, p);
                p.setStrokeWidth(0);
            } else {
                p.setColor(Color.rgb(52,52,45)); c.drawOval(r, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(12); p.setColor(active ? Color.rgb(0,190,205) : Color.rgb(13,124,134)); c.drawOval(r, p);
                p.setStrokeWidth(4); p.setColor(Color.rgb(190,160,125)); c.drawOval(r, p);
                if (pad.label.equals("KICK")) { p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(225,228,226)); c.drawCircle(r.centerX(), r.centerY(), r.height()*0.27f, p); }
            }
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER); p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            p.setTextSize(Math.max(18, r.width()*0.10f)); p.setColor(pad.cymbal ? Color.rgb(35,35,30) : Color.rgb(210,245,245));
            c.save(); c.rotate(pad.cymbal ? 12 : 0, r.centerX(), r.centerY()); c.drawText(pad.label, r.centerX(), r.centerY()+p.getTextSize()/3, p); c.restore();
        }
        private void drawControls(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(190, 10, 12, 14));
            c.drawRect(0, getHeight() - 76, getWidth(), getHeight(), p);
            drawSlider(c, "VOL", listener.getMasterVolume(), volumeTrack);
            drawSlider(c, "VEL", listener.getVelocitySensitivity(), velocityTrack);
        }
        private void drawSlider(Canvas c, String label, float value, RectF track) {
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.RIGHT);
            p.setTextSize(18);
            p.setColor(Color.rgb(230,245,245));
            c.drawText(label, track.left - 14, track.centerY() + 7, p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(55, 58, 58));
            c.drawRoundRect(track, 8, 8, p);
            RectF fill = new RectF(track.left, track.top, track.left + track.width() * value, track.bottom);
            p.setColor(Color.rgb(0, 170, 184));
            c.drawRoundRect(fill, 8, 8, p);
            p.setColor(Color.WHITE);
            c.drawCircle(fill.right, track.centerY(), 13, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(14);
            p.setColor(Color.rgb(205, 235, 235));
            c.drawText(Math.round(value * 100) + "%", track.right + 14, track.centerY() + 5, p);
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
