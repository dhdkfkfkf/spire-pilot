package sts1solver.mirror;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.Clip;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;

/**
 * B187 (user m01082/m01098): the lazy stand-in a sandbox window parks in every mod static whose
 * declared type is a {@link Clip}.
 *
 * <p>B185 closed the clips a window left behind, but the window still really ran mod audio: a
 * simulated {@code heal} reached YuAi's postfix, whose {@code evaluate()} first called
 * {@code stop()} on the LIVE clip (its listener closed the line and nulled the field), then
 * {@code play()} opened a fresh line, and the window ended by putting the already-closed live clip
 * back. The next window's {@code stop()} then called {@code flush()} on a dead DirectSound line and
 * the JVM died inside {@code DirectAudioDevice.nFlush} (hs_err_pid17280, 2026-11-15 21:22:02).
 *
 * <p>Closing other people's clips cannot fix that. The fix is to make the window's audio code early
 * out instead of running: {@code YuAiMusic.play()} first checks {@code currentClip != null} and
 * returns, so a non-null stand-in means the window never calls {@code AudioSystem.getClip()} at all
 * (acceptance G1), and {@code stop()/pause()} only ever touch this object (G2/I2).
 *
 * <p>Every method is a no-op or a plausible constant, never a device call. The one piece of real
 * state carried over is the audio format of the clip that was replaced, so a mod that inspects
 * {@code getFormat()} sees the same format it saw before; if that read fails a silent PCM default is
 * used instead.
 *
 * <p>The instance is a process-wide singleton: a window may park the same object in several fields,
 * and {@link AudioQuiet} restores the real reference before the window is gone.
 */
public final class SandboxClip implements Clip {

    private static final SandboxClip INSTANCE = new SandboxClip();

    /** A format broad enough that a mod inspecting it sees a usable 44.1 kHz stereo line. */
    private static final AudioFormat DEFAULT_FORMAT =
            new AudioFormat(44100.0f, 16, 2, true, false);

    private static final AtomicLong AUDIO_CALLS = new AtomicLong();

    private volatile AudioFormat format;
    private final Map<Control.Type, Control> controls = new HashMap<Control.Type, Control>();

    private SandboxClip() {
    }

    /** The single stand-in every watched field receives. */
    public static SandboxClip shared() {
        return INSTANCE;
    }

    /** Whether {@code value} is this stand-in (the gate and {@link AudioQuiet} both ask). */
    public static boolean isSandbox(Object value) {
        return value instanceof SandboxClip;
    }

    /** How many audio-lifecycle calls the stand-in swallowed since the process started. */
    public static long swallowedCalls() {
        return AUDIO_CALLS.get();
    }

    /**
     * Remembers the format of the real clip this stand-in replaces, the first time a window needs it.
     * Never throws: a line that refuses the query only costs the exact format, not the isolation.
     */
    static void adoptFormat(Clip live) {
        SandboxClip stand = INSTANCE;
        if (stand.format != null || live == null) {
            return;
        }
        try {
            AudioFormat f = live.getFormat();
            if (f != null) {
                stand.format = f;
            }
        } catch (Throwable ignored) {
            // a closed line may refuse; the default format is used instead
        }
    }

    /** The format a mod would have read from the replaced clip. */
    @Override
    public AudioFormat getFormat() {
        AudioFormat f = format;
        return f == null ? DEFAULT_FORMAT : f;
    }

    // ------------------------------------------------------------------ playback: all no-ops

    @Override
    public void open() throws LineUnavailableException {
        // no device is opened on purpose (acceptance G1)
    }

    @Override
    public void open(AudioFormat fmt, byte[] data, int offset, int bufferSize)
            throws LineUnavailableException {
        if (fmt != null) {
            format = fmt;
        }
    }

    @Override
    public void open(AudioInputStream stream) throws LineUnavailableException {
        if (stream != null && stream.getFormat() != null) {
            format = stream.getFormat();
        }
    }

    @Override
    public void start() {
        AUDIO_CALLS.incrementAndGet();
    }

    @Override
    public void loop(int count) {
        AUDIO_CALLS.incrementAndGet();
    }

    @Override
    public void stop() {
        AUDIO_CALLS.incrementAndGet();
    }

    @Override
    public void flush() {
        AUDIO_CALLS.incrementAndGet();
    }

    @Override
    public void drain() {
        AUDIO_CALLS.incrementAndGet();
    }

    @Override
    public void close() {
        AUDIO_CALLS.incrementAndGet();
    }

    // ------------------------------------------------------------------ state: always "playing"

    @Override
    public boolean isOpen() {
        // must stay true: a mod that reopens on "not open" would open a real line (G1)
        return true;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public int getFramePosition() {
        return 0;
    }

    @Override
    public long getLongFramePosition() {
        return 0L;
    }

    @Override
    public long getMicrosecondPosition() {
        return 0L;
    }

    @Override
    public void setFramePosition(int frames) {
        // no position to move
    }

    @Override
    public void setMicrosecondPosition(long microseconds) {
        // no position to move
    }

    @Override
    public void setLoopPoints(int start, int end) {
        // no loop to move
    }

    @Override
    public int getFrameLength() {
        return 0;
    }

    @Override
    public long getMicrosecondLength() {
        return 0L;
    }

    @Override
    public int getBufferSize() {
        return 0;
    }

    @Override
    public int available() {
        return 0;
    }

    @Override
    public float getLevel() {
        return 0.0f;
    }

    // ------------------------------------------------------------------ line plumbing

    @Override
    public Line.Info getLineInfo() {
        try {
            return new DataLine.Info(Clip.class, getFormat());
        } catch (Throwable ignored) {
            return new Line.Info(Clip.class);
        }
    }

    @Override
    public boolean isControlSupported(Control.Type control) {
        return control != null && getControlOrNull(control) != null;
    }

    @Override
    public Control getControl(Control.Type control) {
        return getControlOrNull(control);
    }

    @Override
    public Control[] getControls() {
        synchronized (controls) {
            return controls.values().toArray(new Control[controls.size()]);
        }
    }

    @Override
    public void addLineListener(LineListener listener) {
        // the window's listeners never need to fire: nothing happens here
    }

    @Override
    public void removeLineListener(LineListener listener) {
        // nothing was registered
    }

    private Control getControlOrNull(Control.Type type) {
        if (type == null) {
            return null;
        }
        if (!(type instanceof FloatControl.Type)) {
            // Only gain-style controls are plausible stand-ins; anything else (pan, reverb,
            // sample rate) is reported as unsupported, which every sane mod handles by skipping.
            return null;
        }
        synchronized (controls) {
            Control cached = controls.get(type);
            if (cached != null) {
                return cached;
            }
            try {
                // The constructor only stores the numbers: no mixer, no device, no line.
                Control created = new SandboxFloatControl((FloatControl.Type) type);
                controls.put(type, created);
                return created;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /**
     * A gain control that only remembers its range. {@code FloatControl} is abstract (it is the
     * superclass of the per-type classes the real mixers hand out), so the stand-in needs its own
     * concrete subclass; this constructor never talks to a device.
     */
    private static final class SandboxFloatControl extends FloatControl {
        SandboxFloatControl(FloatControl.Type type) {
            super(type, -80.0f, 6.0206f, 0.1f, 0, 0.0f, "");
        }
    }

    // ------------------------------------------------------------------ identity

    @Override
    public String toString() {
        return "SandboxClip(sandbox javax.sound stand-in, " + getFormat() + ')';
    }
}
