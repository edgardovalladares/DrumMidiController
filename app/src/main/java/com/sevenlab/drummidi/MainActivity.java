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
    // General MIDI Mapping (matching Steven Slate Drums / SSD5 Preset)
    private static final int NOTE_KICK_RIGHT = 36;      // C1 - Bass Drum 1 (Right Pedal)
    private static final int NOTE_KICK_LEFT = 35;       // B0 - Acoustic Bass Drum (Left Pedal)
    private static final int NOTE_SNARE_WHITE = 38;     // D1 - Snare 1 Hit (White Mapex - Softer)
    private static final int NOTE_SNARE_BLACK = 40;     // E1 - Snare 1 Rimshot / Secondary (Black Mapex - Full)
    private static final int NOTE_FLOOR = 43;           // G1 - Floor Tom
    private static final int NOTE_TOM_LOW = 47;         // B1 - Rack Tom 2 (Low-Mid)
    private static final int NOTE_TOM_HIGH = 50;        // D2 - Rack Tom 1 (High)
    private static final int NOTE_HIHAT_CLOSED = 42;    // F#1 - Closed Hi-Hat
    private static final int NOTE_HIHAT_OPEN = 46;      // A#1 - Open Hi-Hat
    private static final int NOTE_CRASH_1 = 49;         // C#2 - Crash 1
    private static final int NOTE_CRASH_2 = 57;         // A2 - Crash 2
    private static final int NOTE_CRASH_3 = 52;         // E2 - Crash 3
    private static final int NOTE_RIDE_BOW = 59;        // B2 - Ride Bow
    private static final int NOTE_RIDE_BELL = 53;       // F2 - Ride Bell

    private SoundPool soundPool;
    private final Map<Integer, Integer> soundMap = new HashMap<>();
    private MidiSender midiSender;
    private DrumKitView drumKitView;
    private float masterVolume = 1.0f;
    private float velocitySensitivity = 1.0f;
    private int openHiHatStreamId;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder().setMaxStreams(16).setAudioAttributes(attrs).build();

        int kickSound = soundPool.load(this, R.raw.kick, 1);
        int snareSound = soundPool.load(this, R.raw.snare, 1);
        int floorSound = soundPool.load(this, R.raw.floor, 1);
        int tomLowSound = soundPool.load(this, R.raw.tom_low, 1);
        int tomMidSound = soundPool.load(this, R.raw.tom_mid, 1);
        int tomHighSound = soundPool.load(this, R.raw.tom_high, 1);
        int hihatClosedSound = soundPool.load(this, R.raw.hihat_closed, 1);
        int hihatOpenSound = soundPool.load(this, R.raw.hihat_open, 1);
        int crashSound = soundPool.load(this, R.raw.crash, 1);
        int splashSound = soundPool.load(this, R.raw.splash, 1);
        int rideSound = soundPool.load(this, R.raw.ride, 1);

        soundMap.put(NOTE_KICK_RIGHT, kickSound);
        soundMap.put(NOTE_KICK_LEFT, kickSound);
        soundMap.put(NOTE_SNARE_WHITE, snareSound);
        soundMap.put(NOTE_SNARE_BLACK, snareSound);
        soundMap.put(NOTE_FLOOR, floorSound);
        soundMap.put(NOTE_TOM_LOW, tomMidSound);
        soundMap.put(NOTE_TOM_HIGH, tomHighSound);
        soundMap.put(NOTE_HIHAT_CLOSED, hihatClosedSound);
        soundMap.put(NOTE_HIHAT_OPEN, hihatOpenSound);
        soundMap.put(NOTE_CRASH_1, crashSound);
        soundMap.put(NOTE_CRASH_2, crashSound);
        soundMap.put(NOTE_CRASH_3, splashSound);
        soundMap.put(NOTE_RIDE_BOW, rideSound);
        soundMap.put(NOTE_RIDE_BELL, rideSound);

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
        int velocity = pad.baseVelocity; // 100% intensity by default: 127 for full, 96 for soft white snare
        if (velocitySensitivity < 0.95f) {
            float normalized = Math.max(0.25f, Math.min(1f, pressure));
            float curved = (float)Math.pow(normalized, 1.25f - velocitySensitivity * 0.75f);
            velocity = Math.max(35, Math.min(pad.baseVelocity, Math.round(curved * pad.baseVelocity)));
        }

        Integer soundId = soundMap.get(pad.note);
        float volume = masterVolume * pad.volumeFactor * (velocity / 127f);

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
        String label;
        int note;
        RectF rect;
        boolean cymbal;
        boolean rectangular;
        long lastHit;
        int baseVelocity;
        float volumeFactor;

        Pad(String label, int note, RectF rect, boolean cymbal, boolean rectangular, int baseVelocity, float volumeFactor) {
            this.label = label;
            this.note = note;
            this.rect = rect;
            this.cymbal = cymbal;
            this.rectangular = rectangular;
            this.baseVelocity = baseVelocity;
            this.volumeFactor = volumeFactor;
        }
    }

    static class DrumKitView extends View {
        private final ArrayList<Pad> pads = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final KitListener listener;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final RectF designRect = new RectF();
        private final Bitmap drumDesign;

        DrumKitView(Context ctx, KitListener listener) {
            super(ctx);
            this.listener = listener;
            setBackgroundColor(Color.rgb(8, 9, 10));
            setFocusable(true);
            setHapticFeedbackEnabled(true);
            drumDesign = BitmapFactory.decodeResource(getResources(), R.drawable.drum_design);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { layoutPads(w, h); }

        private void layoutPads(int w, int h) {
            pads.clear();
            float sx = w / 1323f;
            float sy = h / 662f;
            float s = Math.min(sx, sy);
            float ox = (w - 1323f * s) / 2f;
            float oy = (h - 662f * s) / 2f;
            designRect.set(ox, oy, ox + 1323f * s, oy + 662f * s);

            // 1. Background Cymbals Layer
            pads.add(new Pad("CRASH 1", NOTE_CRASH_1, svgRect(47f, 0f, 338f, 325f, ox, oy, s), true, false, 127, 1.0f));
            pads.add(new Pad("CRASH 2", NOTE_CRASH_2, svgRect(385f, 0f, 263f, 237f, ox, oy, s), true, false, 127, 1.0f));
            pads.add(new Pad("CRASH 3", NOTE_CRASH_3, svgRect(723f, 21f, 205f, 205f, ox, oy, s), true, false, 127, 1.0f));
            pads.add(new Pad("RIDE", NOTE_RIDE_BOW, svgRect(928f, 39f, 297f, 297f, ox, oy, s), true, false, 127, 1.0f));
            pads.add(new Pad("RIDE BELL", NOTE_RIDE_BELL, svgRect(1040f, 151f, 75f, 75f, ox, oy, s), true, false, 127, 1.0f));

            // 2. Drums Layer
            pads.add(new Pad("FLOOR TOM", NOTE_FLOOR, svgRect(24f, 273f, 314f, 314f, ox, oy, s), false, false, 127, 1.0f));
            pads.add(new Pad("RACK TOM 1", NOTE_TOM_HIGH, svgRect(555f, 96f, 223f, 223f, ox, oy, s), false, false, 127, 1.0f));
            pads.add(new Pad("RACK TOM 2", NOTE_TOM_LOW, svgRect(767f, 187f, 225f, 225f, ox, oy, s), false, false, 127, 1.0f));

            // White Mapex Snare (Left): Softer sound & intensity
            pads.add(new Pad("SNARE (WHITE)", NOTE_SNARE_WHITE, svgRect(294f, 156f, 278f, 278f, ox, oy, s), false, false, 96, 0.70f));

            // Black Mapex Snare (Center): Distinct Snare at 100% full intensity (NOT kick)
            pads.add(new Pad("SNARE (BLACK)", NOTE_SNARE_BLACK, svgRect(503f, 244f, 317f, 317f, ox, oy, s), false, false, 127, 1.0f));

            // 3. Hi-Hat Cymbals
            pads.add(new Pad("CLOSED HH", NOTE_HIHAT_CLOSED, svgRect(1013f, 244f, 250f, 250f, ox, oy, s), true, false, 127, 1.0f));

            // 4. Pedals Layer (Foreground, rectangular for ultra-responsive finger tapping)
            // Exactly 2 Kicks: Left Pedal (B0) and Right Pedal (C1)
            pads.add(new Pad("KICK 2", NOTE_KICK_LEFT, svgRect(372f, 418f, 149f, 180f, ox, oy, s), false, true, 127, 1.0f));
            pads.add(new Pad("KICK 1", NOTE_KICK_RIGHT, svgRect(814f, 405f, 128f, 182f, ox, oy, s), false, true, 127, 1.0f));
            pads.add(new Pad("OPEN HH", NOTE_HIHAT_OPEN, svgRect(1043f, 377f, 107f, 210f, ox, oy, s), true, true, 127, 1.0f));
        }

        private RectF svgRect(float x, float y, float w, float h, float ox, float oy, float s) {
            return new RectF(ox + x*s, oy + y*s, ox + (x+w)*s, oy + (y+h)*s);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            drawDesign(c);
            drawHitFeedback(c);
        }

        private void drawDesign(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.BLACK);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
            if (drumDesign != null) c.drawBitmap(drumDesign, null, designRect, p);
        }

        private void drawHitFeedback(Canvas c) {
            long now = SystemClock.uptimeMillis();
            p.setStyle(Paint.Style.FILL);
            for (Pad pad : pads) {
                long age = now - pad.lastHit;
                if (age >= 110) continue;
                int alpha = Math.max(0, 130 - (int)(age * 130 / 110));
                p.setColor(Color.argb(alpha, pad.cymbal ? 255 : 8, pad.cymbal ? 235 : 210, pad.cymbal ? 120 : 225));
                if (pad.rectangular) {
                    c.drawRoundRect(pad.rect, 16f, 16f, p);
                } else {
                    c.drawOval(pad.rect, p);
                }
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                return hit(e, e.getActionIndex());
            }
            return true;
        }

        private boolean hit(MotionEvent e, int index) {
            float x = e.getX(index), y = e.getY(index);
            float pressure = Math.max(0.25f, e.getPressure(index));
            for (int i = pads.size() - 1; i >= 0; i--) {
                Pad pad = pads.get(i);
                if (isInsidePad(pad, x, y)) {
                    pad.lastHit = SystemClock.uptimeMillis();
                    listener.onHit(pad, pressure);
                    invalidate();
                    handler.postDelayed(this::invalidate, 110);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    break;
                }
            }
            return true;
        }

        private boolean isInsidePad(Pad pad, float x, float y) {
            RectF r = pad.rect;
            if (pad.rectangular) {
                return r.contains(x, y);
            }
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
            for (MidiDeviceInfo.PortInfo pi : info.getPorts()) {
                if (pi.getType() == MidiDeviceInfo.PortInfo.TYPE_INPUT) inputPorts.add(pi.getPortNumber());
            }
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
            for (MidiConnection connection : connections) {
                if (connection.deviceId == deviceId) return connection;
            }
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
                for (MidiInputPort port: connection.ports) {
                    try { port.send(data, 0, data.length, now); } catch(IOException ignored) {}
                }
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
