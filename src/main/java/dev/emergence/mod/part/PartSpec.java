package dev.emergence.mod.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialDatabase;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Map;
import java.util.TreeMap;

/**
 * Item data describing a primitive part before it is placed: material, size in grid cells
 * ({@code length} along the part's axis, {@code cross} cells square across it) and physical
 * parameters in SI units.
 */
public record PartSpec(String material, int length, int cross, Map<String, Double> params, String text) {

    public static final Codec<PartSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("material").forGetter(PartSpec::material),
            Codec.INT.fieldOf("length").forGetter(PartSpec::length),
            Codec.INT.fieldOf("cross").forGetter(PartSpec::cross),
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("params", Map.of()).forGetter(PartSpec::params),
            Codec.STRING.optionalFieldOf("text", "").forGetter(PartSpec::text)
    ).apply(i, PartSpec::new));

    public static final StreamCodec<ByteBuf, PartSpec> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

    /** Without text (most parts). */
    public PartSpec(String material, int length, int cross, Map<String, Double> params) {
        this(material, length, cross, params, "");
    }

    /** The same part carrying {@code text} (a chip's design source). */
    public PartSpec withText(String text) {
        return new PartSpec(material, length, cross, params, text == null ? "" : text);
    }

    public PartSpec {
        params = Map.copyOf(new TreeMap<>(params));
        text = text == null ? "" : text;
        length = Math.max(1, Math.min(16, length));
        cross = Math.max(1, Math.min(16, cross));
    }

    public static PartSpec of(String material, int length, int cross, Object... kv) {
        Map<String, Double> m = new TreeMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put((String) kv[k], ((Number) kv[k + 1]).doubleValue());
        return new PartSpec(material, length, cross, m);
    }

    public Material resolveMaterial() {
        Material m = MaterialDatabase.standard().getOrNull(material);
        return m != null ? m : MaterialDatabase.standard().get("steel_1045");
    }
}
