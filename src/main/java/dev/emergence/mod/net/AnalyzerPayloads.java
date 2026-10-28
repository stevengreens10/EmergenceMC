package dev.emergence.mod.net;

import dev.emergence.mod.EmergenceMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The logic analyser's messages: the screen's controls and view requests, and the server's traces. */
public final class AnalyzerPayloads {
    private AnalyzerPayloads() {
    }

    /** Operations of {@link Control}. */
    public static final int RUN = 0, SINGLE = 1, HOLD = 2, CLEAR = 3, TRIGGER_CHANNEL = 4, TRIGGER_EDGE = 5, REMOVE_PROBE = 6, WINDOW_US = 7,
            TRIGGER_LEVEL_MV = 8, DECODE = 9, EXPORT = 10;
    /** Decoders ({@link #DECODE}'s argument): none, SPI (CH1 clock, CH2 data, CH3 select if probed), UART (every level channel). */
    public static final int DECODE_OFF = 0, DECODE_SPI = 1, DECODE_UART = 2;

    /** Client → server: a control of the player's analyser (an operation and its argument). */
    public record Control(int op, int arg) implements CustomPacketPayload {
        public static final Type<Control> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "analyzer_control"));
        public static final StreamCodec<FriendlyByteBuf, Control> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.op);
                    buf.writeVarInt(p.arg);
                },
                buf -> new Control(buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client → server: the window the screen shows — {@code span} seconds ending {@code back} seconds before the newest
     * record (or, in a held single capture, before its end) — in {@code bins} columns.
     */
    public record View(double span, double back, int bins) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "analyzer_view"));
        public static final StreamCodec<FriendlyByteBuf, View> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeDouble(p.span);
                    buf.writeDouble(p.back);
                    buf.writeVarInt(p.bins);
                },
                buf -> new View(buf.readDouble(), buf.readDouble(), Math.max(8, Math.min(1024, buf.readVarInt()))));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server → client: the traces over the window asked for — per channel its name ("" if no probe), whether it is
     * digital, and its (min, max) per bin (NaN where nothing is recorded) — with the window's end time, the newest
     * record, and the capture's state (mode, armed, holding, trigger channel/edge/time, window after the trigger); and
     * the decoder's bytes in the window (channel, start, end, text) with a note on how it reads the bus.
     */
    public record Data(String[] names, boolean[] digital, float[][] min, float[][] max, double end, double span, double newest,
                       int mode, boolean armed, boolean frozen, int trigChannel, boolean trigRising, double trigTime, double window,
                       boolean grounded, int decode, String decodeNote, int[] frameCh, double[] frameStart, double[] frameEnd,
                       String[] frameText) implements CustomPacketPayload {
        public static final int MAX_FRAMES = 512;

        public static final Type<Data> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "analyzer_data"));
        public static final StreamCodec<FriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.of(Data::write, Data::read);

        private static void write(FriendlyByteBuf buf, Data d) {
            buf.writeVarInt(d.names.length);
            for (int k = 0; k < d.names.length; k++) {
                buf.writeUtf(d.names[k], 80);
                buf.writeBoolean(d.digital[k]);
                buf.writeVarInt(d.min[k].length);
                for (int i = 0; i < d.min[k].length; i++) {
                    buf.writeFloat(d.min[k][i]);
                    buf.writeFloat(d.max[k][i]);
                }
            }
            buf.writeDouble(d.end);
            buf.writeDouble(d.span);
            buf.writeDouble(d.newest);
            buf.writeVarInt(d.mode);
            buf.writeBoolean(d.armed);
            buf.writeBoolean(d.frozen);
            buf.writeVarInt(d.trigChannel);
            buf.writeBoolean(d.trigRising);
            buf.writeDouble(d.trigTime);
            buf.writeDouble(d.window);
            buf.writeBoolean(d.grounded);
            buf.writeVarInt(d.decode);
            buf.writeUtf(d.decodeNote, 200);
            buf.writeVarInt(d.frameCh.length);
            for (int i = 0; i < d.frameCh.length; i++) {
                buf.writeVarInt(d.frameCh[i]);
                buf.writeDouble(d.frameStart[i]);
                buf.writeDouble(d.frameEnd[i]);
                buf.writeUtf(d.frameText[i], 8);
            }
        }

        private static Data read(FriendlyByteBuf buf) {
            int n = Math.min(8, buf.readVarInt());
            String[] names = new String[n];
            boolean[] dig = new boolean[n];
            float[][] mn = new float[n][], mx = new float[n][];
            for (int k = 0; k < n; k++) {
                names[k] = buf.readUtf(80);
                dig[k] = buf.readBoolean();
                int bins = Math.min(1024, buf.readVarInt());
                mn[k] = new float[bins];
                mx[k] = new float[bins];
                for (int i = 0; i < bins; i++) {
                    mn[k][i] = buf.readFloat();
                    mx[k][i] = buf.readFloat();
                }
            }
            double end = buf.readDouble(), span = buf.readDouble(), newest = buf.readDouble();
            int mode = buf.readVarInt();
            boolean armed = buf.readBoolean(), frozen = buf.readBoolean();
            int trig = buf.readVarInt();
            boolean rising = buf.readBoolean();
            double trigTime = buf.readDouble(), window = buf.readDouble();
            boolean grounded = buf.readBoolean();
            int decode = buf.readVarInt();
            String note = buf.readUtf(200);
            int f = Math.min(MAX_FRAMES, buf.readVarInt());
            int[] ch = new int[f];
            double[] f0 = new double[f], f1 = new double[f];
            String[] tx = new String[f];
            for (int i = 0; i < f; i++) {
                ch[i] = buf.readVarInt();
                f0[i] = buf.readDouble();
                f1[i] = buf.readDouble();
                tx[i] = buf.readUtf(8);
            }
            return new Data(names, dig, mn, mx, end, span, newest, mode, armed, frozen, trig, rising, trigTime, window, grounded, decode, note, ch, f0, f1, tx);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
